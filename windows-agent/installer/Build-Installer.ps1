<#
.SYNOPSIS
    Publishes the Windows agent and tray, then builds the WiX MSI.

.DESCRIPTION
    Must be run from an elevated shell only if the dotnet tool or NuGet cache
    needs to be restored into a protected location. This script does NOT install
    the MSI or register a service; it only publishes and builds artifacts.
#>
[CmdletBinding()]
param(
    [string]$Configuration = "Release",
    [string]$Version = "1.0.0",
    [string]$Runtime = "win-x64",
    [switch]$SkipPublish
)

$ErrorActionPreference = "Stop"
$installerDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$agentRoot = Split-Path -Parent $installerDir
$repoRoot = Split-Path -Parent $agentRoot

$workspaceDotnet = Join-Path $repoRoot ".tools\dotnet\dotnet.exe"
$dotnet = if (Test-Path $workspaceDotnet) {
    $workspaceDotnet
} else {
    (Get-Command dotnet -ErrorAction Stop).Source
}

$publishRoot = Join-Path $agentRoot "artifacts\publish"
$servicePublish = Join-Path $publishRoot "service"
$trayPublish = Join-Path $publishRoot "tray"
$msiDir = Join-Path $agentRoot "artifacts\msi"
$wixDir = Join-Path $repoRoot ".tools\wix"
$wix = Join-Path $wixDir "wix.exe"
$wixVersion = "5.0.2"

function Invoke-Checked {
    param([string]$FilePath, [string[]]$Arguments)
    & $FilePath @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Command failed with exit code ${LASTEXITCODE}: $FilePath $($Arguments -join ' ')"
    }
}

$serviceProject = Join-Path $agentRoot "src\PcWake.Agent.Service\PcWake.Agent.Service.csproj"
$trayProject = Join-Path $agentRoot "src\PcWake.Agent.Tray\PcWake.Agent.Tray.csproj"

if (-not $SkipPublish) {
    foreach ($dir in @($servicePublish, $trayPublish)) {
        if (Test-Path $dir) {
            Remove-Item -LiteralPath $dir -Recurse -Force
        }
    }

    Invoke-Checked $dotnet @(
        "publish", $serviceProject,
        "-c", $Configuration,
        "-r", $Runtime,
        "--self-contained", "true",
        "-p:PublishSingleFile=true",
        "-p:IncludeNativeLibrariesForSelfExtract=true",
        "-p:DebugType=None",
        "-p:DebugSymbols=false",
        "-p:Version=$Version",
        "-o", $servicePublish,
        "--nologo"
    )

    Invoke-Checked $dotnet @(
        "publish", $trayProject,
        "-c", $Configuration,
        "-r", $Runtime,
        "--self-contained", "true",
        "-p:PublishSingleFile=true",
        "-p:IncludeNativeLibrariesForSelfExtract=true",
        "-p:DebugType=None",
        "-p:DebugSymbols=false",
        "-p:Version=$Version",
        "-o", $trayPublish,
        "--nologo"
    )
}

$serviceExe = Join-Path $servicePublish "PcWake.Agent.Service.exe"
$trayExe = Join-Path $trayPublish "PcWake.Agent.Tray.exe"
if (-not (Test-Path $serviceExe)) {
    throw "Service publish output not found: $serviceExe. Run this script without -SkipPublish first."
}
if (-not (Test-Path $trayExe)) {
    throw "Tray publish output not found: $trayExe. Run this script without -SkipPublish first."
}

if (-not (Test-Path $wix)) {
    Write-Host "Installing WiX $wixVersion into $wixDir"
    Invoke-Checked $dotnet @("tool", "install", "wix", "--tool-path", $wixDir, "--version", $wixVersion)
}

if (-not (Test-Path $msiDir)) {
    New-Item -ItemType Directory -Path $msiDir -Force | Out-Null
}

Push-Location $installerDir
try {
    if (-not (Test-Path (Join-Path $installerDir ".wix\extensions\WixToolset.Firewall.wixext"))) {
        Write-Host "Restoring the WiX firewall extension into the local extension cache"
        Invoke-Checked $wix @("extension", "add", "WixToolset.Firewall.wixext/$wixVersion")
    }

    $msi = Join-Path $msiDir "PcWake.Agent.$Version.msi"
    $wixArgs = @(
        "build", (Join-Path $installerDir "Package.wxs"),
        "-arch", "x64",
        "-d", "Version=$Version",
        "-d", "ServicePublishDir=$servicePublish",
        "-d", "TrayPublishDir=$trayPublish",
        "-ext", "WixToolset.Firewall.wixext",
        "-o", $msi
    )
    Invoke-Checked $wix $wixArgs

    Write-Host ""
    Write-Host "MSI built: $msi" -ForegroundColor Green
    Get-Item $msi | Select-Object FullName, Length, LastWriteTime | Format-List
}
finally {
    Pop-Location
}
