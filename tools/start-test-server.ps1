$ErrorActionPreference = 'Stop'
if (!(Test-Path -LiteralPath (Join-Path $PSScriptRoot '.pv-groups-gui-test-server.json'))) {
    throw 'Run the start.ps1 copied into the dedicated test server directory.'
}
if (Get-NetTCPConnection -State Listen -LocalPort 25567,24467 -ErrorAction SilentlyContinue) {
    throw 'A test server TCP port (25567 or 24467) is already in use. No existing server was stopped.'
}
if (Get-NetUDPEndpoint -LocalPort 24467 -ErrorAction SilentlyContinue) {
    throw 'Voice UDP port 24467 is already in use.'
}
$javaExecutable = (Get-Command java -ErrorAction Stop).Source
Push-Location -LiteralPath $PSScriptRoot
try {
    & $javaExecutable -Xms1G -Xmx2G -jar paper.jar --nogui
} finally {
    Pop-Location
}
