<#
.SYNOPSIS
    Fallback installer for the PC Wake Controller Windows agent (no MSI required).

.DESCRIPTION
    Copies the published single-file binaries, registers the PcWakeAgent Windows
    service with sc.exe, opens only TCP 47101 and UDP 47100 on Private/Domain
    firewall profiles, and restricts %ProgramData%\PcWakeAgent to SYSTEM and
    Administrators. Run from an elevated PowerShell prompt.

    The MSI is the preferred installation path. This script provides equivalent
    behavior if MSI deployment is not available.
#>
[CmdletBinding()]
param(
    [string]$InstallDir = (Join-Path $env:ProgramFiles "PcWake Agent"),
    [string]$SourceDir = (Join-Path $PSScriptRoot "..\artifacts\publish"),
    [switch]$SkipFirewall
)

$ErrorActionPreference = "Stop"

function Assert-Administrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = [Security.Principal.WindowsPrincipal]::new($identity)
    if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw "Run PowerShell as Administrator (UAC is never bypassed)."
    }
}

function Invoke-Sc {
    param([string[]]$Arguments)
    & sc.exe @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "sc.exe failed with exit code $LASTEXITCODE: $($Arguments -join ' ')"
    }
}

Assert-Administrator

$serviceSource = Join-Path $SourceDir "service"
$traySource = Join-Path $SourceDir "tray"
$serviceExeSource = Join-Path $serviceSource "PcWake.Agent.Service.exe"
$trayExeSource = Join-Path $traySource "PcWake.Agent.Tray.exe"
if (-not (Test-Path $serviceExeSource)) {
    throw "Published service not found at $serviceExeSource. Run installer\Build-Installer.ps1 or publish first."
}
if (-not (Test-Path $trayExeSource)) {
    throw "Published tray not found at $trayExeSource. Run installer\Build-Installer.ps1 or publish first."
}

$serviceName = "PcWakeAgent"
$existing = Get-Service -Name $serviceName -ErrorAction SilentlyContinue
if ($null -ne $existing) {
    Write-Host "Stopping existing $serviceName service"
    if ($existing.Status -ne "Stopped") {
        Stop-Service -Name $serviceName -Force
        (Get-Service -Name $serviceName).WaitForStatus("Stopped", [TimeSpan]::FromSeconds(30))
    }

    Write-Host "Recreating service registration"
    Invoke-Sc @("delete", $serviceName)
    Start-Sleep -Seconds 2
}

New-Item -ItemType Directory -Path $InstallDir -Force | Out-Null
Copy-Item -Path (Join-Path $serviceSource "*") -Destination $InstallDir -Recurse -Force
Copy-Item -Path (Join-Path $traySource "*") -Destination $InstallDir -Recurse -Force

$serviceExe = Join-Path $InstallDir "PcWake.Agent.Service.exe"
$trayExe = Join-Path $InstallDir "PcWake.Agent.Tray.exe"
$serviceBinPath = "`"$serviceExe`""

Write-Host "Creating the all-users Start Menu shortcut"
$startMenuDir = Join-Path $env:ProgramData "Microsoft\Windows\Start Menu\Programs\PC Wake Controller"
New-Item -ItemType Directory -Path $startMenuDir -Force | Out-Null
$shortcutPath = Join-Path $startMenuDir "PC Wake Agent.lnk"
$shell = New-Object -ComObject WScript.Shell
$shortcut = $shell.CreateShortcut($shortcutPath)
$shortcut.TargetPath = $trayExe
$shortcut.WorkingDirectory = $InstallDir
$shortcut.Description = "Pair this PC, view agent status and cancel pending power actions"
$shortcut.Save()

Write-Host "Restricting %ProgramData%\PcWakeAgent to SYSTEM and Administrators"
$dataDir = Join-Path $env:ProgramData "PcWakeAgent"
New-Item -ItemType Directory -Path $dataDir -Force | Out-Null
& icacls.exe $dataDir /inheritance:r /grant:r "*S-1-5-18:(OI)(CI)F" "*S-1-5-32-544:(OI)(CI)F" | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw "icacls failed to restrict $dataDir."
}

Write-Host "Registering the Windows service"
Invoke-Sc @(
    "create", $serviceName,
    "binPath=", $serviceBinPath,
    "start=", "auto",
    "DisplayName=", "PC Wake Controller Agent",
    "obj=", "LocalSystem"
)
Invoke-Sc @("description", $serviceName, "Authenticated HTTPS control and LAN discovery for PC Wake Controller.")
Invoke-Sc @("failure", $serviceName, "reset=", "86400", "actions=", "restart/60000/restart/60000/none/0")

if (-not $SkipFirewall) {
    Write-Host "Configuring Windows Firewall (Private and Domain profiles only)"
    $httpsRule = "PC Wake Controller Agent HTTPS"
    $discoveryRule = "PC Wake Controller Agent Discovery"
    & netsh.exe advfirewall firewall delete rule name="$httpsRule" | Out-Null
    & netsh.exe advfirewall firewall delete rule name="$discoveryRule" | Out-Null
    & netsh.exe advfirewall firewall add rule name="$httpsRule" dir=in action=allow protocol=TCP localport=47101 profile=private,domain | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to add the TCP 47101 firewall rule."
    }
    & netsh.exe advfirewall firewall add rule name="$discoveryRule" dir=in action=allow protocol=UDP localport=47100 profile=private,domain | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to add the UDP 47100 firewall rule."
    }
}

Write-Host "Starting the agent service"
Invoke-Sc @("start", $serviceName)
Start-Sleep -Seconds 2
$service = Get-Service -Name $serviceName
if ($service.Status -ne "Running") {
    Write-Warning "Service state is $($service.Status). Inspect %ProgramData%\PcWakeAgent\logs and run '$serviceExe --console' elevated for diagnostics."
} else {
    Write-Host ""
    Write-Host "PC Wake Controller Agent installed." -ForegroundColor Green
    Write-Host "Service:  $serviceName (LocalSystem, automatic)"
    Write-Host "HTTPS:    https://<host>:47101"
    Write-Host "Discover: UDP 47100"
    Write-Host "Tray:     $trayExe"
    Write-Host "Data:     $dataDir"
}
