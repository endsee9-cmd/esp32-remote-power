[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [switch]$Remove
)

$ErrorActionPreference = 'Stop'
$taskName = 'UU-Remote-System-Startup'
$taskPath = '\'
$currentUser = [Security.Principal.WindowsPrincipal]::new(
    [Security.Principal.WindowsIdentity]::GetCurrent()
)
if (-not $WhatIfPreference -and
    -not $currentUser.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Run this script from an administrator Command Prompt or PowerShell.'
}

if ($Remove) {
    $task = Get-ScheduledTask -TaskName $taskName -TaskPath $taskPath -ErrorAction SilentlyContinue
    if (-not $task) {
        Write-Host 'The UU startup task is not installed.'
        return
    }
    if ($PSCmdlet.ShouldProcess($taskName, 'Stop and remove the startup task')) {
        if ($task.State -eq 'Running') {
            Stop-ScheduledTask -InputObject $task
        }
        Unregister-ScheduledTask -InputObject $task -Confirm:$false
        Write-Host 'Removed the UU startup task.'
    }
    return
}

$serviceKey = 'HKLM:\SYSTEM\CurrentControlSet\Services\GameViewerService'
$serviceCommand = (Get-ItemProperty -LiteralPath $serviceKey -Name ImagePath).ImagePath
if ($serviceCommand -notmatch '^\s*(?:"(?<exe>[^"]+\.exe)"|(?<exe>[^"\r\n]+?\.exe))(?:\s|$)') {
    throw 'Could not read the registered UU Remote service executable path.'
}
$serviceExe = [Environment]::ExpandEnvironmentVariables($Matches['exe'])
if ((Split-Path -Leaf $serviceExe) -ne 'GameViewerService.exe' -or
    -not (Test-Path -LiteralPath $serviceExe -PathType Leaf)) {
    throw 'The registered GameViewerService.exe file was not found.'
}

$workingDirectory = Split-Path -Parent $serviceExe
$launcher = Join-Path $workingDirectory 'GameViewer.exe'
if (-not (Test-Path -LiteralPath $launcher -PathType Leaf)) {
    throw "GameViewer.exe was not found beside GameViewerService.exe in '$workingDirectory'."
}

Write-Host "UU executable: $launcher"
if ($PSCmdlet.ShouldProcess($taskName, 'Start GameViewer.exe as SYSTEM 30 seconds after boot, with no time limit')) {
    $action = New-ScheduledTaskAction -Execute $launcher -WorkingDirectory $workingDirectory
    $trigger = New-ScheduledTaskTrigger -AtStartup
    $trigger.Delay = 'PT30S'
    $taskPrincipal = New-ScheduledTaskPrincipal -UserId 'SYSTEM' -LogonType ServiceAccount
    $settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
        -MultipleInstances IgnoreNew -ExecutionTimeLimit ([TimeSpan]::Zero)
    $settings.ExecutionTimeLimit = 'PT0S'

    Register-ScheduledTask -TaskName $taskName -TaskPath $taskPath -Action $action -Trigger $trigger `
        -Principal $taskPrincipal -Settings $settings -Force `
        -Description 'Starts the installed UU Remote launcher before Windows sign-in.' | Out-Null

    Write-Host 'UU startup task registered with no run time limit.'
    Write-Host 'Restart manually, wait at the Windows sign-in screen, and test UU Remote over mobile data.'
    Write-Host 'Run this script with -Remove to undo the task setup.'
}
