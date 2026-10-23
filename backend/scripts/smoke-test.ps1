# End-to-end API smoke test. Starts the backend, exercises login, role checks, ownership,
# validation, the admin workflow and the AI-unavailable fallback, then stops the backend.
#
#   powershell -ExecutionPolicy Bypass -File .\scripts\smoke-test.ps1
#   powershell -ExecutionPolicy Bypass -File .\scripts\smoke-test.ps1 -PsqlPath "<path to psql.exe>"
#
# Nothing is hardcoded: connection settings come from backend\.env, secrets and demo passwords
# from the environment (DB_PASSWORD, AUTH_SECRET, SEED_AUTHOR_PASSWORD, SEED_ADMIN_PASSWORD), and
# the expected books and royalty figures are read from the sample dataset the app was seeded with.
# LLM_API_KEY is blanked for the run so the AI-unavailable fallback is what gets tested.
# Test tickets are subject-prefixed and removed afterwards (this needs psql; pass -PsqlPath if it
# is not on PATH, otherwise the cleanup is skipped with a warning).

param(
    [string]$PsqlPath,
    [string]$TicketSubjectPrefix = "[smoke]"
)

$ErrorActionPreference = "Stop"
$backend = Split-Path -Parent $PSScriptRoot

# ---- configuration ----
$settings = @{}
$envFile = Join-Path $backend ".env"
if (Test-Path $envFile) {
    Get-Content $envFile | ForEach-Object {
        if ($_ -match '^\s*([A-Za-z0-9_]+)\s*=\s*(.*)$') { $settings[$Matches[1]] = $Matches[2].Trim() }
    }
}
foreach ($name in "DB_PASSWORD", "AUTH_SECRET", "SEED_AUTHOR_PASSWORD", "SEED_ADMIN_PASSWORD") {
    if (-not [Environment]::GetEnvironmentVariable($name, "Process")) {
        [Environment]::SetEnvironmentVariable($name, [Environment]::GetEnvironmentVariable($name, "User"), "Process")
    }
    if (-not [Environment]::GetEnvironmentVariable($name, "Process")) { throw "Environment variable $name is not set." }
}
foreach ($name in "SERVER_PORT", "SEED_ADMIN_EMAIL", "DB_URL", "DB_USERNAME") {
    if (-not $settings[$name]) { throw "$name is missing from $envFile" }
}
$authorPw = $env:SEED_AUTHOR_PASSWORD
$adminPw = $env:SEED_ADMIN_PASSWORD
$adminEmail = $settings["SEED_ADMIN_EMAIL"]
$base = "http://localhost:$($settings['SERVER_PORT'])"
$env:LLM_API_KEY = ""

# ---- expectations come from the sample dataset ----
$dataset = Get-Content (Join-Path $backend "src\main\resources\seed\bookleaf_sample_data.json") -Raw -Encoding UTF8 | ConvertFrom-Json
$authorA = $dataset.authors | Where-Object { $_.books.Count -ge 2 } | Select-Object -First 1
$authorB = $dataset.authors | Where-Object { $_.author_id -ne $authorA.author_id -and ($_.books | Where-Object { $null -eq $_.mrp }) } | Select-Object -First 1
$bookA = $authorA.books[0]
$productionBook = $authorB.books | Where-Object { $null -eq $_.mrp } | Select-Object -First 1
$authorAEmail = $authorA.email
$authorBEmail = $authorB.email

$log = Join-Path $env:TEMP "smoke-backend.log"
Set-Location $backend
$proc = Start-Process -FilePath "cmd.exe" -ArgumentList "/c", ".\mvnw.cmd -q spring-boot:run" `
    -RedirectStandardOutput $log -RedirectStandardError "$log.err" -PassThru -WindowStyle Hidden

$script:fail = 0

function Call($method, $path, $token, $body) {
    $headers = @{}
    if ($token) { $headers["Authorization"] = "Bearer $token" }
    $params = @{ Uri = "$base$path"; Method = $method; Headers = $headers; UseBasicParsing = $true }
    if ($null -ne $body) { $params["Body"] = ($body | ConvertTo-Json -Depth 5); $params["ContentType"] = "application/json" }
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
    return @{ status = $status; json = $json; text = $text }
}

function Check($name, $ok) {
    if ($ok) { "PASS  $name" } else { "FAIL  $name"; $script:fail++ }
}

# Waits until background triage has finished for a ticket (outcome no longer PENDING).
function WaitForTriage($ticketId, $adminToken) {
    for ($i = 0; $i -lt 20; $i++) {
        $r = Call GET "/api/v1/admin/tickets/$ticketId" $adminToken $null
        if ($r.json.ai.outcome -ne "PENDING") { return $r }
        Start-Sleep -Milliseconds 500
    }
    return $r
}

try {
    $up = $false
    for ($i = 0; $i -lt 40; $i++) {
        try { $h = Invoke-WebRequest -Uri "$base/api/v1/health" -UseBasicParsing -TimeoutSec 2; if ($h.StatusCode -eq 200) { $up = $true; break } } catch { }
        Start-Sleep -Seconds 3
    }
    if (-not $up) { throw "Backend did not start. See $log and $log.err" }

    $p = $TicketSubjectPrefix

    # ---- public endpoints ----
    $r = Call GET "/api/v1/health" $null $null
    Check "health is public and reports only UP" ($r.status -eq 200 -and $r.json.status -eq "UP" -and $r.text -notmatch "postgres")
    $r = Call GET "/actuator/health" $null $null
    Check "actuator health shows no component details" ($r.status -eq 200 -and $null -eq $r.json.components)
    $r = Call GET "/api/v1/config" $null $null
    Check "client config is public and complete (brand, locale, limits, policy, intervals)" ($r.status -eq 200 -and $r.json.brandName -and $r.json.locale -and $r.json.validation.subjectMax -gt 0 -and $r.json.policy.royaltyPayoutDays -gt 0 -and $r.json.ticketPollMs -gt 0 -and $r.text -notmatch "secret|password|apiKey")
    $limits = $r.json.validation
    $opts = $r.json.options
    Check "config serves the six categories, four priorities and the status transition table" ($opts.categories.Count -eq 6 -and $opts.priorities.Count -eq 4 -and $opts.statuses.Count -eq 4 -and (($opts.statuses | Where-Object { $_.value -eq "CLOSED" }).allowedNext -join ",") -eq "OPEN")

    # ---- authentication ----
    $r = Call GET "/api/v1/author/books" $null $null
    Check "no token -> 401 UNAUTHENTICATED" ($r.status -eq 401 -and $r.json.error.code -eq "UNAUTHENTICATED" -and $r.json.requestId)
    $r = Call GET "/api/v1/author/books" "not-a-real-token" $null
    Check "garbage token -> 401" ($r.status -eq 401)
    $r = Call POST "/api/v1/auth/login" $null @{ email = $authorAEmail; password = "$authorPw-wrong" }
    Check "wrong password -> 401 INVALID_CREDENTIALS" ($r.status -eq 401 -and $r.json.error.code -eq "INVALID_CREDENTIALS")
    $r = Call POST "/api/v1/auth/login" $null @{ email = "nobody.$authorAEmail"; password = "whatever" }
    Check "unknown email -> same 401 message" ($r.status -eq 401 -and $r.json.error.message -eq "Invalid email or password.")
    $r = Call POST "/api/v1/auth/login" $null @{ email = ""; password = "" }
    Check "empty login -> 400 with field details" ($r.status -eq 400 -and $r.json.error.details.Count -ge 2)

    $r = Call POST "/api/v1/auth/login" $null @{ email = $authorAEmail.ToUpper(); password = $authorPw }
    $tokenA = $r.json.token
    Check "author login (email case-insensitive) -> 200 + token, no password hash" ($r.status -eq 200 -and $tokenA -and -not ($r.json.user.PSObject.Properties.Name -contains "passwordHash"))
    $r = Call POST "/api/v1/auth/login" $null @{ email = $authorBEmail; password = $authorPw }
    $tokenB = $r.json.token
    $r = Call POST "/api/v1/auth/login" $null @{ email = $adminEmail; password = $adminPw }
    $admin = $r.json.token; $adminId = $r.json.user.id
    Check "admin login -> ADMIN" ($r.status -eq 200 -and $r.json.user.role -eq "ADMIN")
    $r = Call GET "/api/v1/auth/me" $tokenA $null
    Check "GET /auth/me -> AUTHOR" ($r.status -eq 200 -and $r.json.role -eq "AUTHOR")

    # ---- author: books (compared with the dataset) ----
    $r = Call GET "/api/v1/author/books" $tokenA $null
    $booksA = $r.json
    $expectedIds = ($authorA.books | ForEach-Object { $_.book_id } | Sort-Object) -join ","
    Check "author A sees exactly the books in the dataset ($expectedIds)" ($r.status -eq 200 -and (($booksA.sourceBookId | Sort-Object) -join ",") -eq $expectedIds)
    $apiBookA = $booksA | Where-Object { $_.sourceBookId -eq $bookA.book_id }
    Check "$($bookA.book_id) royalty figures match the dataset exactly" ($apiBookA.totalRoyaltyEarned -eq $bookA.total_royalty_earned -and $apiBookA.royaltyPaid -eq $bookA.royalty_paid -and $apiBookA.royaltyPending -eq $bookA.royalty_pending -and $apiBookA.isbn -eq $bookA.isbn)
    $r = Call GET "/api/v1/author/books" $tokenB $null
    $booksB = $r.json
    $apiProduction = $booksB | Where-Object { $_.sourceBookId -eq $productionBook.book_id }
    Check "in-production book $($productionBook.book_id): null MRP/date, zero royalties, empty platforms" ($null -eq $apiProduction.mrp -and $null -eq $apiProduction.publicationDate -and $apiProduction.royaltyPending -eq $productionBook.royalty_pending -and $apiProduction.availableOn.Count -eq 0)

    # ---- role separation ----
    $r = Call GET "/api/v1/admin/tickets" $tokenA $null
    Check "author token on admin route -> 403 FORBIDDEN" ($r.status -eq 403 -and $r.json.error.code -eq "FORBIDDEN")
    $r = Call GET "/api/v1/author/books" $admin $null
    Check "admin token on author route -> 403" ($r.status -eq 403)
    $r = Call GET "/api/v1/nope" $tokenA $null
    Check "unknown API route -> 404 with request id" ($r.status -eq 404 -and $r.json.requestId)

    # ---- author: tickets ----
    $tooShort = "x" * ([Math]::Max($limits.subjectMin - 1, 0))
    $r = Call POST "/api/v1/author/tickets" $tokenA @{ subject = $tooShort; description = "short" }
    Check "subject/description below the limits -> 400 with details" ($r.status -eq 400 -and $r.json.error.details.Count -eq 2)
    $tooLong = "x" * ($limits.subjectMax + 1)
    $r = Call POST "/api/v1/author/tickets" $tokenA @{ subject = $tooLong; description = "A long enough description for this test." }
    Check "subject above the limit -> 400 (subject)" ($r.status -eq 400 -and $r.json.error.details[0].field -eq "subject")
    $r = Call POST "/api/v1/author/tickets" $tokenA @{ bookId = ($booksB | Select-Object -First 1).id; subject = "$p Not my book"; description = "Trying another author's book id here." }
    Check "ticket on another author's book -> 400 (bookId)" ($r.status -eq 400 -and $r.json.error.details[0].field -eq "bookId")

    $r = Call POST "/api/v1/author/tickets" $tokenA @{ bookId = $apiBookA.id; subject = "$p Royalty not received"; description = "I have not received my royalty for this book for 6 months." }
    $royaltyId = $r.json.id
    Check "create ticket on own book -> 201 OPEN" ($r.status -eq 201 -and $r.json.status -eq "OPEN" -and $royaltyId)
    $r = Call POST "/api/v1/author/tickets" $tokenA @{ subject = "$p Update my bio"; description = "Please update my author bio on the website." }
    $bioId = $r.json.id
    Check "create General / Account Level ticket (no bookId) -> 201" ($r.status -eq 201 -and $null -eq $r.json.bookId)
    $r = Call POST "/api/v1/author/tickets" $tokenA @{ subject = "$p Thank you"; description = "Just wanted to say thank you for the great experience." }
    $generalId = $r.json.id
    $r = Call POST "/api/v1/author/tickets" $tokenA @{ bookId = $apiBookA.id; subject = "$p ISBN mismatch"; description = "The ISBN on Amazon is different from the one on my physical copy." }
    $isbnId = $r.json.id

    $r = Call GET "/api/v1/author/tickets" $tokenA $null
    Check "author A's ticket list has the tickets, with pagination" ($r.status -eq 200 -and $r.json.data.Count -ge 4 -and $r.json.pagination.page -eq 0)
    $r = Call GET "/api/v1/author/tickets/$royaltyId" $tokenA $null
    Check "own ticket detail -> 200 with no public messages yet" ($r.status -eq 200 -and $r.json.messages.Count -eq 0)
    Check "author ticket JSON has no AI, priority, category or assignee fields" ($r.text -notmatch '"(priority|category|ai[A-Za-z]*|assignedAdmin[A-Za-z]*)"')
    $r = Call GET "/api/v1/author/tickets/$royaltyId" $tokenB $null
    Check "another author's ticket -> 404" ($r.status -eq 404 -and $r.json.error.code -eq "TICKET_NOT_FOUND")
    $r = Call GET "/api/v1/author/tickets" $tokenB $null
    Check "author B's list does not contain author A's tickets" ($r.status -eq 200 -and -not ($r.json.data.id -contains $royaltyId))
    $r = Call GET "/api/v1/author/tickets/not-a-uuid" $tokenA $null
    Check "malformed ticket id -> 400" ($r.status -eq 400)

    # ---- AI triage with the AI unavailable (rule-based fallback) ----
    $royalty = WaitForTriage $royaltyId $admin
    Check "AI off: royalty ticket -> ROYALTY_PAYMENTS / HIGH, flagged for manual review" ($royalty.json.summary.category -eq "ROYALTY_PAYMENTS" -and $royalty.json.summary.priority -eq "HIGH" -and $royalty.json.ai.outcome -eq "FALLBACK" -and $royalty.json.summary.needsManualReview -eq $true)
    Check "AI off: no AI suggestion is stored" ($null -eq $royalty.json.ai.suggestedCategory -and $null -eq $royalty.json.ai.suggestedPriority)
    $bio = WaitForTriage $bioId $admin
    Check "AI off: bio update -> ISBN_METADATA / LOW" ($bio.json.summary.priority -eq "LOW" -and $bio.json.summary.category -eq "ISBN_METADATA")
    $general = WaitForTriage $generalId $admin
    Check "AI off: general thanks -> GENERAL_INQUIRY / MEDIUM" ($general.json.summary.category -eq "GENERAL_INQUIRY" -and $general.json.summary.priority -eq "MEDIUM")
    $isbn = WaitForTriage $isbnId $admin
    Check "AI off: ISBN mismatch -> ISBN_METADATA / HIGH" ($isbn.json.summary.category -eq "ISBN_METADATA" -and $isbn.json.summary.priority -eq "HIGH")

    # ---- admin: queue ----
    $r = Call GET "/api/v1/admin/tickets?size=50" $admin $null
    $smokeRows = @($r.json.data | Where-Object { $_.subject.StartsWith($p) })
    Check "admin queue lists the smoke tickets" ($r.status -eq 200 -and $smokeRows.Count -eq 4)
    $order = ($smokeRows | ForEach-Object { $_.priority }) -join ","
    Check "queue sorts by priority: HIGH, HIGH, MEDIUM, LOW (got $order)" ($order -eq "HIGH,HIGH,MEDIUM,LOW")
    $highRows = @($smokeRows | Where-Object { $_.priority -eq "HIGH" })
    Check "equal priority: older ticket first" ([DateTimeOffset]::Parse([string]$highRows[0].createdAt) -lt [DateTimeOffset]::Parse([string]$highRows[1].createdAt))
    Check "queue rows show author, age and created time" ($smokeRows[0].authorName -and $null -ne $smokeRows[0].ageHours -and $smokeRows[0].createdAt)
    $r = Call GET "/api/v1/admin/tickets?priority=LOW&category=ISBN_METADATA&size=50" $admin $null
    Check "filter by priority + category" ($r.status -eq 200 -and ($r.json.data | Where-Object { $_.priority -ne "LOW" -or $_.category -ne "ISBN_METADATA" }).Count -eq 0 -and ($r.json.data.id -contains $bioId))
    $r = Call GET "/api/v1/admin/tickets?status=CLOSED&size=50" $admin $null
    Check "filter by status with no matches -> empty list" ($r.status -eq 200 -and $r.json.data.Count -eq 0 -and $r.json.pagination.totalItems -eq 0)
    $today = (Get-Date).ToUniversalTime().ToString("yyyy-MM-dd")
    $r = Call GET "/api/v1/admin/tickets?from=$today&to=$today&size=50" $admin $null
    Check "filter by date range includes today's tickets" ($r.status -eq 200 -and ($r.json.data.id -contains $royaltyId))
    $future = (Get-Date).ToUniversalTime().AddYears(50).ToString("yyyy-MM-dd")
    $r = Call GET "/api/v1/admin/tickets?from=$future&size=50" $admin $null
    Check "date filter in the future -> empty list" ($r.status -eq 200 -and $r.json.data.Count -eq 0)
    $r = Call GET "/api/v1/admin/tickets?from=$today&to=$((Get-Date).ToUniversalTime().AddDays(-1).ToString('yyyy-MM-dd'))" $admin $null
    Check "end date before start date -> 400" ($r.status -eq 400)
    $r = Call GET "/api/v1/admin/tickets?status=NOT_A_STATUS" $admin $null
    Check "invalid status filter -> 400" ($r.status -eq 400)
    $r = Call GET "/api/v1/admin/tickets?size=100000" $admin $null
    Check "page size is capped by configuration" ($r.status -eq 200 -and $r.json.pagination.size -lt 100000)
    $r = Call GET "/api/v1/admin/tickets/summary" $admin $null
    Check "queue summary has every status and priority and counts the smoke tickets" ($r.status -eq 200 -and $r.json.byStatus.OPEN -ge 4 -and $null -ne $r.json.byStatus.CLOSED -and $null -ne $r.json.unresolvedByPriority.CRITICAL -and $r.json.unassignedUnresolved -ge 4)
    $r = Call GET "/api/v1/admin/tickets/summary" $tokenA $null
    Check "author cannot read the admin summary -> 403" ($r.status -eq 403)

    # ---- admin: ticket detail and management ----
    $r = Call GET "/api/v1/admin/tickets/$royaltyId" $admin $null
    Check "admin detail shows author, book record and AI info" ($r.status -eq 200 -and $r.json.author.email -eq $authorAEmail -and $r.json.book.royaltyPending -eq $bookA.royalty_pending -and $r.json.ai.outcome)
    $r = Call GET "/api/v1/admin/tickets/00000000-0000-0000-0000-000000000000" $admin $null
    Check "missing ticket -> 404" ($r.status -eq 404 -and $r.json.error.code -eq "TICKET_NOT_FOUND")

    $r = Call PATCH "/api/v1/admin/tickets/$royaltyId" $admin @{ priority = "CRITICAL"; category = "ISBN_METADATA"; assignedAdminId = $adminId; status = "IN_PROGRESS" }
    Check "override category/priority, assign self, set IN_PROGRESS" ($r.status -eq 200 -and $r.json.summary.priority -eq "CRITICAL" -and $r.json.summary.category -eq "ISBN_METADATA" -and $r.json.summary.assignedAdminId -eq $adminId -and $r.json.summary.status -eq "IN_PROGRESS")
    $r = Call PATCH "/api/v1/admin/tickets/$royaltyId" $admin @{ category = "ROYALTY_PAYMENTS"; priority = "HIGH" }
    Check "overrides keep AI fields untouched (fallback leaves them empty)" ($r.status -eq 200 -and $r.json.ai.outcome -eq "FALLBACK" -and $null -eq $r.json.ai.suggestedPriority)
    $r = Call PATCH "/api/v1/admin/tickets/$royaltyId" $admin @{}
    Check "empty patch -> 400" ($r.status -eq 400)
    $r = Call PATCH "/api/v1/admin/tickets/$royaltyId" $admin @{ assignedAdminId = $apiBookA.id }
    Check "assigning a non-admin -> 400 (assignedAdminId)" ($r.status -eq 400 -and $r.json.error.details[0].field -eq "assignedAdminId")
    $r = Call PATCH "/api/v1/admin/tickets/$royaltyId" $admin @{ priority = "NOT_A_PRIORITY" }
    Check "invalid priority value -> 400" ($r.status -eq 400)
    $r = Call PATCH "/api/v1/author/tickets/$royaltyId" $tokenA @{ status = "CLOSED" }
    Check "author cannot change a ticket -> 405" ($r.status -eq 405)

    # ---- admin: AI draft with the AI unavailable ----
    $r = Call POST "/api/v1/admin/tickets/$royaltyId/ai-draft" $admin $null
    Check "AI off: draft -> 503 AI_UNAVAILABLE with manual-reply message, no provider details" ($r.status -eq 503 -and $r.json.error.code -eq "AI_UNAVAILABLE" -and $r.json.error.message -match "manually" -and $r.json.requestId)
    $r = Call POST "/api/v1/admin/tickets/$royaltyId/ai-draft" $tokenA $null
    Check "author cannot request an AI draft -> 403" ($r.status -eq 403)
    $r = Call POST "/api/v1/admin/tickets/$royaltyId/ai-draft" $null $null
    Check "unauthenticated AI draft -> 401" ($r.status -eq 401)

    # ---- admin: internal notes and public replies ----
    $secret = "INTERNAL-ONLY-NOTE-$([guid]::NewGuid().ToString('N').Substring(0, 8))"
    $r = Call POST "/api/v1/admin/tickets/$royaltyId/notes" $admin @{ body = $secret }
    Check "add internal note -> 201, marked INTERNAL" ($r.status -eq 201 -and ($r.json.messages | Where-Object { $_.visibility -eq "INTERNAL" -and $_.body -eq $secret }))
    $r = Call POST "/api/v1/admin/tickets/$royaltyId/notes" $admin @{ body = "   " }
    Check "blank note -> 400" ($r.status -eq 400)
    $r = Call GET "/api/v1/author/tickets/$royaltyId" $tokenA $null
    Check "author does not see the internal note (body or text)" ($r.status -eq 200 -and $r.json.messages.Count -eq 0 -and $r.text -notmatch $secret)

    $r = Call POST "/api/v1/admin/tickets/$royaltyId/replies" $admin @{ body = "   " }
    Check "blank reply -> 400" ($r.status -eq 400)
    $r = Call POST "/api/v1/admin/tickets/$royaltyId/replies" $admin @{ body = "Hi, we are looking into this and will update you shortly." }
    Check "public reply without status keeps the status (IN_PROGRESS)" ($r.status -eq 201 -and $r.json.summary.status -eq "IN_PROGRESS")
    $r = Call GET "/api/v1/author/tickets/$royaltyId" $tokenA $null
    Check "author sees the public reply and still no note" ($r.json.messages.Count -eq 1 -and $r.json.messages[0].senderRole -eq "ADMIN" -and $r.text -notmatch $secret)
    $r = Call POST "/api/v1/admin/tickets/$royaltyId/replies" $admin @{ body = "This has now been resolved."; status = "RESOLVED" }
    Check "reply with explicit status RESOLVED" ($r.status -eq 201 -and $r.json.summary.status -eq "RESOLVED" -and $r.json.resolvedAt)
    $r = Call GET "/api/v1/author/tickets/$royaltyId" $tokenA $null
    Check "author sees RESOLVED status and 2 public replies" ($r.json.status -eq "RESOLVED" -and $r.json.messages.Count -eq 2)
    $r = Call PATCH "/api/v1/admin/tickets/$royaltyId" $admin @{ status = "IN_PROGRESS" }
    Check "invalid transition RESOLVED -> IN_PROGRESS -> 400 (status)" ($r.status -eq 400 -and $r.json.error.details[0].field -eq "status")
    $r = Call PATCH "/api/v1/admin/tickets/$royaltyId" $admin @{ status = "OPEN" }
    Check "reopen RESOLVED -> OPEN clears resolvedAt" ($r.status -eq 200 -and $r.json.summary.status -eq "OPEN" -and $null -eq $r.json.resolvedAt)

    $r = Call POST "/api/v1/auth/logout" $tokenA $null
    Check "logout -> 204" ($r.status -eq 204)
}
finally {
    if ($proc) { taskkill /PID $proc.Id /T /F | Out-Null }

    # Remove the test tickets, their messages and their AI events.
    if (-not $PsqlPath) {
        $found = Get-Command psql -ErrorAction SilentlyContinue
        if ($found) { $PsqlPath = $found.Source }
    }
    if ($PsqlPath -and (Test-Path $PsqlPath) -and $settings["DB_URL"] -match '^jdbc:postgresql://([^:/]+):?(\d*)/([^?]+)') {
        $dbHost = $Matches[1]; $dbPort = if ($Matches[2]) { $Matches[2] } else { "5432" }; $dbName = $Matches[3]
        $env:PGPASSWORD = $env:DB_PASSWORD
        $pattern = $TicketSubjectPrefix -replace "'", "''"
        $cleanup = @"
DELETE FROM ai_events WHERE ticket_id IN (SELECT id FROM tickets WHERE subject LIKE '$pattern%');
DELETE FROM ticket_messages WHERE ticket_id IN (SELECT id FROM tickets WHERE subject LIKE '$pattern%');
DELETE FROM tickets WHERE subject LIKE '$pattern%';
"@
        & $PsqlPath -h $dbHost -p $dbPort -U $settings["DB_USERNAME"] -d $dbName -c $cleanup | Out-Null
        Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue
    } else {
        Write-Warning "psql not found: test tickets with subjects starting '$TicketSubjectPrefix' were NOT deleted. Pass -PsqlPath."
    }
}
"---"
if ($script:fail -eq 0) { "ALL CHECKS PASSED" } else { "$($script:fail) CHECK(S) FAILED" }
