# Sets up the application's PostgreSQL role and database, then stores the role's password in the
# DB_PASSWORD user environment variable.
#
# It reads the host, port, database name and application user from backend\.env (DB_URL and
# DB_USERNAME) when present; anything missing is asked for. Passwords are typed at masked prompts
# and are never written to disk or printed. Nothing here is hardcoded.
#
#   powershell -ExecutionPolicy Bypass -File .\scripts\setup-db.ps1
#   powershell -ExecutionPolicy Bypass -File .\scripts\setup-db.ps1 -PsqlPath "<path to psql.exe>"

param(
    [string]$PsqlPath,
    [string]$DbHost,
    [string]$Port,
    [string]$DbName,
    [string]$AppUser,
    [string]$AdminUser
)

$ErrorActionPreference = "Stop"

# --- read non-secret defaults from backend\.env, if it exists ---
$envFile = Join-Path (Split-Path -Parent $PSScriptRoot) ".env"
$settings = @{}
if (Test-Path $envFile) {
    Get-Content $envFile | ForEach-Object {
        if ($_ -match '^\s*([A-Za-z0-9_]+)\s*=\s*(.*)$') { $settings[$Matches[1]] = $Matches[2].Trim() }
    }
}
if ($settings["DB_URL"] -match '^jdbc:postgresql://([^:/]+):?(\d*)/([^?]+)') {
    if (-not $DbHost) { $DbHost = $Matches[1] }
    if (-not $Port -and $Matches[2]) { $Port = $Matches[2] }
    if (-not $DbName) { $DbName = $Matches[3] }
}
if (-not $AppUser -and $settings["DB_USERNAME"]) { $AppUser = $settings["DB_USERNAME"] }

function Ask($label, $current) {
    if ($current) { return $current }
    $answer = Read-Host $label
    if ([string]::IsNullOrWhiteSpace($answer)) { throw "$label is required." }
    return $answer.Trim()
}

if (-not $PsqlPath) {
    $found = Get-Command psql -ErrorAction SilentlyContinue
    if ($found) { $PsqlPath = $found.Source }
}
$PsqlPath = Ask "Full path to psql.exe" $PsqlPath
if (-not (Test-Path $PsqlPath)) { throw "psql not found at $PsqlPath" }

$DbHost = Ask "PostgreSQL host" $DbHost
$Port = Ask "PostgreSQL port" $Port
$DbName = Ask "Database name to create" $DbName
$AppUser = Ask "Application database user to create" $AppUser
$AdminUser = Ask "PostgreSQL administrator user" $AdminUser

$adminSecure = Read-Host "Password for the PostgreSQL administrator user $AdminUser" -AsSecureString
$appSecure = Read-Host "New password for $AppUser (avoid double quotes)" -AsSecureString
$appConfirmSecure = Read-Host "Type the $AppUser password again" -AsSecureString

function ConvertTo-PlainText([Security.SecureString]$secure) {
    $ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr) }
}

$adminPw = ConvertTo-PlainText $adminSecure
$appPw = ConvertTo-PlainText $appSecure
$appPwConfirm = ConvertTo-PlainText $appConfirmSecure

if ([string]::IsNullOrEmpty($appPw)) { throw "The $AppUser password cannot be empty." }
if ($appPw -ne $appPwConfirm) { throw "The two $AppUser passwords do not match. Nothing was changed." }

# The SQL file holds no secrets or names: they are passed in as psql variables, and psql quotes
# them as identifiers (:"x") or literals (:'x').
$sqlFile = Join-Path $env:TEMP "app_setup_role.sql"
$sql = @'
SELECT format('CREATE ROLE %I WITH LOGIN', :'appUser')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'appUser') \gexec

ALTER ROLE :"appUser" WITH LOGIN PASSWORD :'appPw';

SELECT format('CREATE DATABASE %I OWNER %I', :'dbName', :'appUser')
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = :'dbName') \gexec

\connect :"dbName"
GRANT ALL ON SCHEMA public TO :"appUser";
'@
[System.IO.File]::WriteAllText($sqlFile, $sql, (New-Object System.Text.UTF8Encoding $false))

try {
    $env:PGPASSWORD = $adminPw
    Write-Host "Creating or updating the role and database..."
    & $PsqlPath -h $DbHost -p $Port -U $AdminUser -d postgres -v ON_ERROR_STOP=1 `
        -v "appUser=$AppUser" -v "appPw=$appPw" -v "dbName=$DbName" -f $sqlFile
    if ($LASTEXITCODE -ne 0) { throw "Setup failed. Check the administrator user and password." }

    # Confirm that the new password really logs in as the application user.
    $env:PGPASSWORD = $appPw
    $who = & $PsqlPath -h $DbHost -p $Port -U $AppUser -d $DbName -tAc "SELECT current_user"
    if ($who -ne $AppUser) { throw "Login as $AppUser failed." }

    [Environment]::SetEnvironmentVariable("DB_PASSWORD", $appPw, "User")
    Write-Host ""
    Write-Host "Done. $AppUser can log in to $DbName, and DB_PASSWORD is saved for your Windows user."
    Write-Host "Close and reopen your terminal and IDE before starting the backend."
}
finally {
    Remove-Item -Path $sqlFile -ErrorAction SilentlyContinue
    Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue
    $adminPw = $null
    $appPw = $null
    $appPwConfirm = $null
}
