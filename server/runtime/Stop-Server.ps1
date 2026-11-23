$ErrorActionPreference='Stop'
$serverRoot=Split-Path -Parent $PSScriptRoot
$dataRoot='C:\Users\Meta\AppData\Local\ExpressAssistantServer\data'
Stop-ScheduledTask -TaskName 'ExpressAssistant Server'
$ownedProcesses=@(Get-CimInstance Win32_Process | Where-Object {$_.Name -eq 'python.exe' -and $_.CommandLine -match '\-m\s+express_server(\s|$)' -and $_.CommandLine -like ('*'+$dataRoot+'*')})
foreach($ownedProcess in $ownedProcesses){Stop-Process -Id $ownedProcess.ProcessId -Force -ErrorAction SilentlyContinue}
Remove-Item -LiteralPath (Join-Path $dataRoot 'server.pid') -ErrorAction SilentlyContinue
Write-Output ('Stopped ExpressAssistant process group: '+$ownedProcesses.Count)
