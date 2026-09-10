param()

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$runDirectory = Join-Path $projectRoot 'run/recoveryProcessTest'
$resultFile = Join-Path $runDirectory 'recovery-process-result.txt'
$crashMarker = Join-Path $runDirectory 'recovery-process-crash.txt'

if (Test-Path -LiteralPath $runDirectory) {
    throw "Isolated test directory already exists. Preserve or move it before retrying: $runDirectory"
}
New-Item -ItemType Directory -Path $runDirectory | Out-Null
Set-Content -LiteralPath (Join-Path $runDirectory 'eula.txt') -Value 'eula=true' -Encoding ascii
Set-Content -LiteralPath (Join-Path $runDirectory 'server.properties') -Encoding ascii -Value @(
    'online-mode=false', 'server-port=0', 'view-distance=2', 'simulation-distance=2',
    'generate-structures=false', 'level-type=minecraft:flat', 'max-tick-time=60000'
)

Push-Location $projectRoot
try {
    & (Join-Path $projectRoot 'gradlew.bat') --offline --no-daemon runServer -PrecoveryProcessTest=crash
    $crashExit = $LASTEXITCODE
    if ($crashExit -eq 0 -or -not (Test-Path -LiteralPath $crashMarker)) {
        throw "Crash phase did not reach the durable partial-write boundary (exit=$crashExit)."
    }

    & (Join-Path $projectRoot 'gradlew.bat') --offline --no-daemon runServer -PrecoveryProcessTest=verify
    if ($LASTEXITCODE -ne 0) {
        throw "Verify server failed to exit normally (exit=$LASTEXITCODE)."
    }
    if (-not (Test-Path -LiteralPath $resultFile)) {
        throw 'Verify phase did not write a result.'
    }
    $result = (Get-Content -LiteralPath $resultFile -Raw).Trim()
    if ($result -ne 'PASS') {
        throw "Recovery restart test failed: $result"
    }
    Write-Output 'Recovery restart test passed.'
} finally {
    Pop-Location
}
