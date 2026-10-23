# Sets up the BookLeaf database on the local PostgreSQL server.
#
# It creates the bookleaf_app role and the bookleaf_support database if they do
# not exist, sets the bookleaf_app password, and stores that password in the
# DB_PASSWORD user environment variable. Passwords are typed at masked prompts
# and are never written to disk or printed.
#
# Run from the backend folder:
#   powershell -ExecutionPolicy Bypass -File .\scripts\setup-db.ps1

$ErrorActionPreference = "Stop"

$psql = "C:\Program Files\PostgreSQL\17\bin\psql.exe"
if (-not (Test-Path $psql)) { throw "psql not found at $psql. Edit the path at the top of this script." }

$portInput = Read-Host "PostgreSQL port (press Enter for 5432)"
$port = if ([string]::IsNullOrWhiteSpace($portInput)) { "5432" } else { $portInput.Trim() }

$superSecure = Read-Host "Password for the postgres superuser" -AsSecureString
$appSecure = Read-Host "New password for bookleaf_app (avoid double quotes)" -AsSecureString
$appConfirmSecure = Read-Host "Type the bookleaf_app password again" -AsSecureString

function ConvertTo-PlainText([Security.SecureString]$secure) {
    $ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr) }
}

$superPw = ConvertTo-PlainText $superSecure
$appPw = ConvertTo-PlainText $appSecure
$appPwConfirm = ConvertTo-PlainText $appConfirmSecure

if ([string]::IsNullOrEmpty($appPw)) { throw "The bookleaf_app password cannot be empty." }
if ($appPw -ne $appPwConfirm) { throw "The two bookleaf_app passwords do not match. Nothing was changed." }

# SQL file contains no secrets: the password is passed in as the psql variable appPw.
$sqlFile = Join-Path $env:TEMP "bookleaf_setup_role.sql"
@'
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'bookleaf_app') THEN
    CREATE ROLE bookleaf_app WITH LOGIN;
  END IF;
END
$$;

ALTER ROLE bookleaf_app WITH LOGIN PASSWORD :'appPw';
'@ | ForEach-Object { [System.IO.File]::WriteAllText($sqlFile, $_, (New-Object System.Text.UTF8Encoding $false)) }

try {
    $env:PGPASSWORD = $superPw
    $common = @("-h", "localhost", "-p", $port, "-U", "postgres", "-v", "ON_ERROR_STOP=1")

    Write-Host "Creating or updating the bookleaf_app role..."
    & $psql @common -d postgres -v "appPw=$appPw" -f $sqlFile
    if ($LASTEXITCODE -ne 0) { throw "Could not create or update the bookleaf_app role." }

    $dbExists = & $psql @common -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname = 'bookleaf_support'"
    if ($dbExists -ne "1") {
        Write-Host "Creating the bookleaf_support database..."
        & $psql @common -d postgres -c "CREATE DATABASE bookleaf_support OWNER bookleaf_app"
        if ($LASTEXITCODE -ne 0) { throw "Could not create the bookleaf_support database." }
    } else {
        Write-Host "The bookleaf_support database already exists."
    }

    & $psql @common -d bookleaf_support -c "GRANT ALL ON SCHEMA public TO bookleaf_app"
    if ($LASTEXITCODE -ne 0) { throw "Could not grant schema rights to bookleaf_app." }

    # Confirm that the new password actually logs in as bookleaf_app.
    $env:PGPASSWORD = $appPw
    $who = & $psql -h localhost -p $port -U bookleaf_app -d bookleaf_support -tAc "SELECT current_user"
    if ($who -ne "bookleaf_app") { throw "Login as bookleaf_app failed." }

    [Environment]::SetEnvironmentVariable("DB_PASSWORD", $appPw, "User")
    Write-Host ""
    Write-Host "Done. bookleaf_app can log in, and DB_PASSWORD is saved for your Windows user."
    Write-Host "Close and reopen your terminal and IDE before starting the backend."
}
finally {
    Remove-Item -Path $sqlFile -ErrorAction SilentlyContinue
    Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue
    $superPw = $null
    $appPw = $null
    $appPwConfirm = $null
}
