<#
.SYNOPSIS
    Fallback uninstaller for a script-installed PC Wake Controller Windows agent.

.DESCRIPTION
    Stops/deletes the PcWakeAgent service, removes the firewall rules created by
    the script fallback (and the equivalent MSI rule names), and removes the
    program files. Credentials, certificate, configuration and audit logs under
    %ProgramData%\PcWakeAgent are retained unless -RemoveData is specified.

    If the agent was installed with the MSI, use "Apps & features" or
    msiexec /x on the MSI instead; this script is the non-MSI fallback.
#>
[CmdletBinding()]
param(
    [string]$InstallDir = (Join-Path $env:ProgramFiles "PcWake Agent"),
    [switch]$RemoveData
)

$ErrorActionPreference = "Stop"

function Assert-Administrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = [Security.Principal.WindowsPrincipal]::new($identity)
    if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw "Run PowerShell as Administrator (UAC is never bypassed)."
    }
}

Assert-Administrator

$serviceName = "PcWakeAgent"
$existing = Get-Service -Name $serviceName -ErrorAction SilentlyContinue
if ($null -ne $existing) {
    Write-Host "Stopping the agent service"
    if ($existing.Status -ne "Stopped") {
        Stop-Service -Name $serviceName -Force
        try {
            (Get-Service -Name $serviceName).WaitForStatus("Stopped", [TimeSpan]::FromSeconds(30))
        } catch {
            Write-Warning "Service did not reach the Stopped state."
        }
    }

    Write-Host "Deleting the service registration"
    & sc.exe delete $serviceName | Out-Null
    Start-Sleep -Seconds 2
}

$ruleNames = @(
    "PC Wake Controller Agent HTTPS",
    "PC Wake Controller Agent Discovery",
    "PC Wake Controller Agent HTTPS (Domain)",
    "PC Wake Controller Agent HTTPS (Private)",
    "PC Wake Controller Agent Discovery (Domain)",
    "PC Wake Controller Agent Discovery (Private)"
)
foreach ($rule in $ruleNames) {
    & netsh.exe advfirewall firewall delete rule name="$rule" | Out-Null
}

if (Test-Path $InstallDir) {
    Write-Host "Removing $InstallDir"
    Remove-Item -LiteralPath $InstallDir -Recurse -Force
}

$dataDir = Join-Path $env:ProgramData "PcWakeAgent"
if ($RemoveData) {
    if (Test-Path $dataDir) {
        Write-Host "Removing agent data, certificate and pairing state: $dataDir"
        Remove-Item -LiteralPath $dataDir -Recurse -Force
    }
} else {
    Write-Host "Retained agent data at $dataDir (use -RemoveData to delete it)."
    Write-Host "The certificate, device tokens and revocations remain valid if the agent is reinstalled."
}

Write-Host "Uninstall complete." -ForegroundColor Green
