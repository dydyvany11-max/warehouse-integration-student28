$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    New-Item -ItemType Directory -Force -Path backups | Out-Null
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    docker compose exec -T db sh -c 'pg_dump -U "$POSTGRES_USER" -d integration -Fc -f /tmp/integration-backup.dump'
    if ($LASTEXITCODE -ne 0) { throw 'Database backup failed.' }
    docker compose cp db:/tmp/integration-backup.dump "backups/integration-$stamp.dump"
    if ($LASTEXITCODE -ne 0) { throw 'Could not copy the database backup.' }
    Write-Output "Backup saved to backups/integration-$stamp.dump"
} finally { Pop-Location }
