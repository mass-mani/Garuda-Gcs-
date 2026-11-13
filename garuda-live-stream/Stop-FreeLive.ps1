$ErrorActionPreference = 'Stop'
$State = Join-Path $PSScriptRoot '.local'
$File = Join-Path $State 'processes.json'
if (!(Test-Path $File)) { Write-Host 'No saved free live server is running.'; return }
$Processes = Get-Content $File -Raw | ConvertFrom-Json
foreach ($Kind in @('server','tunnel')) {
    $IdValue = $Processes."${Kind}Id"
    $Started = $Processes."${Kind}Started"
    $Process = Get-Process -Id $IdValue -ErrorAction SilentlyContinue
    if ($Process -and $Process.StartTime.ToUniversalTime().ToString('o') -eq $Started) { Stop-Process -Id $IdValue }
}
Remove-Item -LiteralPath $File
$Connection = Join-Path $State 'G12-connection.txt'
if (Test-Path $Connection) { Remove-Item -LiteralPath $Connection }
Write-Host 'Free signaling server and tunnel stopped. The previous links are no longer available.'
