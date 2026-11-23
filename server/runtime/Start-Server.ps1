$ErrorActionPreference = 'Stop'
$serverRoot = Split-Path -Parent $PSScriptRoot
$dataRoot = 'C:\Users\Meta\AppData\Local\ExpressAssistantServer\data'
$pythonPath = Join-Path $serverRoot '.venv\Scripts\python.exe'
if (Get-NetTCPConnection -State Listen -LocalPort 18769 -ErrorAction SilentlyContinue) {
    throw 'Port 18769 is already in use; refusing to start a second writer.'
}
$arguments = @('-u','-m','express_server','--host','0.0.0.0','--port','18769','--data',('"' + $dataRoot + '"'),'--browser','chrome','--login-mode','remote')
$serverProcess = Start-Process -FilePath $pythonPath -ArgumentList $arguments -WorkingDirectory $serverRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $dataRoot 'server.log') -RedirectStandardError (Join-Path $dataRoot 'server-error.log')
Set-Content -LiteralPath (Join-Path $dataRoot 'server.pid') -Value $serverProcess.Id -Encoding ASCII
$serverProcess.WaitForExit()
$serverProcess.Refresh()
$exitCode = $serverProcess.ExitCode
if ($null -eq $exitCode) { exit 1 }
exit $exitCode
