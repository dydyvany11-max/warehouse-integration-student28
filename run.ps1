param([ValidateSet('sync','results','export')][string]$Command = 'sync')
$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    if (-not (Test-Path -LiteralPath '.env')) { throw 'Create .env from .env.example and fill the passwords first.' }
    docker compose up -d --wait db
    if ($LASTEXITCODE -ne 0) { throw 'Could not start PostgreSQL.' }
    if (Test-Path -LiteralPath 'target/warehouse-integration-1.0.0.jar') {
        java -jar target/warehouse-integration-1.0.0.jar $Command
    } else {
        docker compose --profile app run --build --rm app $Command
    }
    if ($LASTEXITCODE -ne 0) { throw 'Integration did not complete. See the error above.' }
} finally { Pop-Location }
