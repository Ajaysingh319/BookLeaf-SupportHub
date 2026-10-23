$ErrorActionPreference = "Stop"
$env:DB_PASSWORD = [Environment]::GetEnvironmentVariable("DB_PASSWORD", "User")
$env:AUTH_SECRET = [Environment]::GetEnvironmentVariable("AUTH_SECRET", "User")
if (-not $env:DB_PASSWORD -or -not $env:AUTH_SECRET) { throw "DB_PASSWORD or AUTH_SECRET missing" }

$backend = "C:\Users\hp\Desktop\BookLeaf SupportHub\backend"
$log = Join-Path $env:TEMP "bookleaf-smoke.log"
Set-Location $backend
$proc = Start-Process -FilePath "cmd.exe" -ArgumentList "/c", ".\mvnw.cmd -q spring-boot:run" `
    -RedirectStandardOutput $log -RedirectStandardError "$log.err" -PassThru -WindowStyle Hidden

$base = "http://localhost:8080"
$script:fail = 0

function Call($method, $path, $token, $body) {
    $headers = @{}
    if ($token) { $headers["Authorization"] = "Bearer $token" }
    $params = @{ Uri = "$base$path"; Method = $method; Headers = $headers; UseBasicParsing = $true }
    if ($body) { $params["Body"] = ($body | ConvertTo-Json -Depth 5); $params["ContentType"] = "application/json" }
    try {
        $r = Invoke-WebRequest @params
        $status = [int]$r.StatusCode; $text = $r.Content
    } catch {
        $resp = $_.Exception.Response
        if (-not $resp) { throw }
        $status = [int]$resp.StatusCode
        $text = $_.ErrorDetails.Message
        if (-not $text) {
            $reader = New-Object System.IO.StreamReader($resp.GetResponseStream())
            $text = $reader.ReadToEnd()
        }
    }
    $json = $null
    if ($text) { try { $json = $text | ConvertFrom-Json } catch { } }
    return @{ status = $status; json = $json }
}

function Check($name, $ok) {
    if ($ok) { "PASS  $name" } else { "FAIL  $name"; $script:fail++ }
}

try {
    $up = $false
    for ($i = 0; $i -lt 40; $i++) {
        try { $h = Invoke-WebRequest -Uri "$base/actuator/health" -UseBasicParsing -TimeoutSec 2; if ($h.StatusCode -eq 200) { $up = $true; break } } catch { }
        Start-Sleep -Seconds 3
    }
    if (-not $up) { throw "Backend did not start. See $log and $log.err" }

    $authorPw = "BookLeaf@Demo2026"; $adminPw = "Admin@BookLeaf2026"

    $r = Call GET "/api/v1/author/books" $null $null
    Check "no token -> 401 UNAUTHENTICATED" ($r.status -eq 401 -and $r.json.error.code -eq "UNAUTHENTICATED" -and $r.json.requestId)

    $r = Call GET "/api/v1/author/books" "not-a-real-token" $null
    Check "garbage token -> 401" ($r.status -eq 401)

    $r = Call POST "/api/v1/auth/login" $null @{ email = "priya.sharma@email.com"; password = "wrong-password" }
    Check "wrong password -> 401 INVALID_CREDENTIALS" ($r.status -eq 401 -and $r.json.error.code -eq "INVALID_CREDENTIALS")

    $r = Call POST "/api/v1/auth/login" $null @{ email = "nobody@email.com"; password = "whatever" }
    Check "unknown email -> same 401 message" ($r.status -eq 401 -and $r.json.error.message -eq "Invalid email or password.")

    $r = Call POST "/api/v1/auth/login" $null @{ email = ""; password = "" }
    Check "empty login -> 400 with field details" ($r.status -eq 400 -and $r.json.error.details.Count -ge 2)

    $r = Call POST "/api/v1/auth/login" $null @{ email = "Priya.Sharma@Email.com"; password = $authorPw }
    $priya = $r.json.token
    Check "author login (email case-insensitive) -> 200 + token, no password hash" ($r.status -eq 200 -and $priya -and -not ($r.json.user.PSObject.Properties.Name -contains "passwordHash"))

    $r = Call GET "/api/v1/auth/me" $priya $null
    Check "GET /auth/me -> AUTHOR" ($r.status -eq 200 -and $r.json.role -eq "AUTHOR")

    $r = Call GET "/api/v1/author/books" $priya $null
    $priyaBooks = $r.json
    Check "Priya sees exactly her 2 books" ($r.status -eq 200 -and $priyaBooks.Count -eq 2 -and ($priyaBooks.sourceBookId -join ",") -eq "BK001,BK002")
    $bk1 = $priyaBooks | Where-Object { $_.sourceBookId -eq "BK001" }
    Check "BK001 royalty figures exact (11970 / 8400 / 3570)" ($bk1.totalRoyaltyEarned -eq 11970 -and $bk1.royaltyPaid -eq 8400 -and $bk1.royaltyPending -eq 3570)

    $r = Call POST "/api/v1/auth/login" $null @{ email = "kavita.deshmukh@email.com"; password = $authorPw }
    $kavita = $r.json.token
    $r = Call GET "/api/v1/author/books" $kavita $null
    $kBooks = $r.json
    $bk15 = $kBooks | Where-Object { $_.sourceBookId -eq "BK015" }
    Check "In-production book BK015: null MRP/date, zero royalties, empty platforms" ($null -eq $bk15.mrp -and $null -eq $bk15.publicationDate -and $bk15.royaltyPending -eq 0 -and $bk15.availableOn.Count -eq 0)

    $r = Call GET "/api/v1/admin/tickets" $priya $null
    Check "author token on admin route -> 403 FORBIDDEN" ($r.status -eq 403 -and $r.json.error.code -eq "FORBIDDEN")

    $r = Call POST "/api/v1/author/tickets" $priya @{ subject = "Hi"; description = "short" }
    Check "short subject/description -> 400 with details" ($r.status -eq 400 -and $r.json.error.details.Count -eq 2)

    $kavitaBookId = ($kBooks | Select-Object -First 1).id
    $r = Call POST "/api/v1/author/tickets" $priya @{ bookId = $kavitaBookId; subject = "Not my book"; description = "Trying another author's book id here." }
    Check "ticket on another author's book -> 400 (bookId)" ($r.status -eq 400 -and $r.json.error.details[0].field -eq "bookId")

    $r = Call POST "/api/v1/author/tickets" $priya @{ bookId = $priyaBooks[0].id; subject = "Royalty not received"; description = "I have not received my royalty for this book yet." }
    $ticketId = $r.json.id
    Check "create ticket on own book -> 201 OPEN" ($r.status -eq 201 -and $r.json.status -eq "OPEN" -and $ticketId)

    $r = Call POST "/api/v1/author/tickets" $priya @{ subject = "Update my bio"; description = "Please update my author bio on the website." }
    Check "create General / Account Level ticket (no bookId) -> 201" ($r.status -eq 201 -and $null -eq $r.json.bookId)

    $r = Call GET "/api/v1/author/tickets" $priya $null
    Check "Priya's ticket list has her 2 tickets with pagination" ($r.status -eq 200 -and $r.json.data.Count -ge 2 -and $r.json.pagination.page -eq 0)

    $r = Call GET "/api/v1/author/tickets/$ticketId" $priya $null
    Check "own ticket detail -> 200 with empty public messages" ($r.status -eq 200 -and $r.json.messages.Count -eq 0)

    $r = Call GET "/api/v1/author/tickets/$ticketId" $kavita $null
    Check "another author's ticket -> 404" ($r.status -eq 404 -and $r.json.error.code -eq "TICKET_NOT_FOUND")

    $r = Call GET "/api/v1/author/tickets" $kavita $null
    Check "Kavita's list does not contain Priya's tickets" ($r.status -eq 200 -and -not ($r.json.data.id -contains $ticketId))

    $r = Call GET "/api/v1/author/tickets/not-a-uuid" $priya $null
    Check "malformed ticket id -> 400" ($r.status -eq 400)

    $r = Call POST "/api/v1/auth/login" $null @{ email = "admin@bookleaf.local"; password = $adminPw }
    $admin = $r.json.token
    Check "admin login -> ADMIN" ($r.status -eq 200 -and $r.json.user.role -eq "ADMIN")

    $r = Call GET "/api/v1/author/books" $admin $null
    Check "admin token on author route -> 403" ($r.status -eq 403)

    $r = Call GET "/api/v1/nope" $priya $null
    Check "unknown API route -> 404 with request id" ($r.status -eq 404 -and $r.json.requestId)

    $r = Call POST "/api/v1/auth/logout" $priya $null
    Check "logout -> 204" ($r.status -eq 204)
}
finally {
    if ($proc) { taskkill /PID $proc.Id /T /F | Out-Null }
}
"---"
if ($script:fail -eq 0) { "ALL CHECKS PASSED" } else { "$($script:fail) CHECK(S) FAILED" }
