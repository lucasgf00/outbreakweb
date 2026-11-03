# fetch-backup-task.ps1 (Windows PowerShell 5.1): daily download of the newest VPS backup to this PC.
#
#   powershell -ExecutionPolicy Bypass -File fetch-backup-task.ps1            install (or update) the task
#   powershell -ExecutionPolicy Bypass -File fetch-backup-task.ps1 -RunNow    install and run it once now
#   powershell -ExecutionPolicy Bypass -File fetch-backup-task.ps1 -Remove    delete the task
#   (the task itself calls: ... -File C:\reo-secrets\fetch-backup-task.ps1 -Run)
#
# The task "reo-fetch-backup" runs as you, only while you are logged on (WSL and the SSH key in
# C:\Users\<you>\.ssh need your session), every day at 12:00 and 5 minutes after you log on. If the PC was off
# at 12:00 it runs as soon as it can. It calls `deploy.sh fetch-backup` inside WSL, which skips a file it already
# has, verifies the sha256, keeps the newest 30 (REO_BACKUP_KEEP) in C:\reo-secrets\backups and marks the download
# on the VPS so reo-alert can warn in Discord when no backup reached the PC for 3 days.
# Log: C:\reo-secrets\backups\fetch.log (cut to the last 400 lines).
param(
    [switch]$Run,
    [switch]$RunNow,
    [switch]$Remove,
    [string]$Kit = '~/reo-web/deploy',      # the kit inside WSL
    [string]$Distro = '',                   # WSL distribution ('' = default)
    [string]$Time = '12:00'
)
$ErrorActionPreference = 'Stop'
$TaskName = 'reo-fetch-backup'
$Home2 = 'C:\reo-secrets'
$Dest = Join-Path $Home2 'backups'
$Log = Join-Path $Dest 'fetch.log'
$Installed = Join-Path $Home2 'fetch-backup-task.ps1'

if ($Run) {
    New-Item -ItemType Directory -Force $Dest | Out-Null
    $wslArgs = @()
    if ($Distro -ne '') { $wslArgs += @('-d', $Distro) }
    # date + the command, all output appended to the log; ANSI colors removed
    $cmd = "cd $Kit && { date -u '+%F %T UTC'; ./deploy.sh fetch-backup; echo exit=`$?; } 2>&1 | sed 's/\x1b\[[0-9;]*m//g' >> /mnt/c/reo-secrets/backups/fetch.log"
    $wslArgs += @('-e', 'bash', '-lc', $cmd)
    & wsl.exe @wslArgs
    if (Test-Path $Log) {
        $lines = Get-Content $Log
        if ($lines.Count -gt 400) { $lines | Select-Object -Last 400 | Set-Content -Encoding ascii $Log }
    }
    exit $LASTEXITCODE
}

if ($Remove) {
    try { Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false -ErrorAction Stop; Write-Output "removed task $TaskName" }
    catch { Write-Output "no task $TaskName" }
    exit 0
}

# install: copy this script next to the secrets (a \\wsl.localhost path is not there before WSL starts)
New-Item -ItemType Directory -Force $Dest | Out-Null
if ($PSCommandPath -ne $Installed) { Copy-Item -Force $PSCommandPath $Installed }
$argList = "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$Installed`" -Run -Kit `"$Kit`""
if ($Distro -ne '') { $argList += " -Distro `"$Distro`"" }
$action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument $argList
$daily = New-ScheduledTaskTrigger -Daily -At $Time
$logon = New-ScheduledTaskTrigger -AtLogOn -User "$env:USERDOMAIN\$env:USERNAME"
$logon.Delay = 'PT5M'
$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
    -ExecutionTimeLimit (New-TimeSpan -Minutes 30) -MultipleInstances IgnoreNew
$principal = New-ScheduledTaskPrincipal -UserId "$env:USERDOMAIN\$env:USERNAME" -LogonType Interactive -RunLevel Limited
Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger @($daily, $logon) -Settings $settings `
    -Principal $principal -Description 'Downloads the newest outbreakweb.com backup (deploy.sh fetch-backup)' -Force | Out-Null
Write-Output "task $TaskName installed: daily at $Time and 5 min after logon; log $Log"
if ($RunNow) {
    Start-ScheduledTask -TaskName $TaskName
    Write-Output "started; look at $Log in a minute"
}
