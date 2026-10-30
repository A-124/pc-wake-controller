# Builds and tests every component. Run from an elevated-neutral shell.
$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
$gradle = if (Test-Path "$root\android-app\gradlew.bat") { "$root\android-app\gradlew.bat" } else { "gradle" }
$dotnet = if (Test-Path "$root\.tools\dotnet\dotnet.exe") { "$root\.tools\dotnet\dotnet.exe" } else { "dotnet" }

Write-Host "== Protocol cross-check ==" -ForegroundColor Cyan
node "$root\scripts\check-protocol.mjs"
if ($LASTEXITCODE -ne 0) { throw "Protocol vectors mismatched" }

Write-Host "== Android unit tests ==" -ForegroundColor Cyan
Push-Location "$root\android-app"
& $gradle :app:testDebugUnitTest --no-daemon --console=plain
if ($LASTEXITCODE -ne 0) { Pop-Location; throw "Android tests failed" }
Write-Host "== Android debug APK ==" -ForegroundColor Cyan
& $gradle :app:assembleDebug --no-daemon --console=plain
if ($LASTEXITCODE -ne 0) { Pop-Location; throw "Android build failed" }
Pop-Location

Write-Host "== Windows agent ==" -ForegroundColor Cyan
Push-Location "$root\windows-agent"
$sln = Get-ChildItem -Filter *.sln | Select-Object -First 1
& $dotnet test $sln.FullName -c Release
if ($LASTEXITCODE -ne 0) { Pop-Location; throw "Agent tests failed" }
Pop-Location

Write-Host "== Wake Bridge tests ==" -ForegroundColor Cyan
Push-Location "$root\wake-bridge"
if (-not (Test-Path node_modules) -and (Test-Path package-lock.json)) { npm ci }
npm test
if ($LASTEXITCODE -ne 0) { Pop-Location; throw "Bridge tests failed" }
Pop-Location

Write-Host "All builds and tests completed." -ForegroundColor Green
Write-Host "APK: $root\android-app\app\build\outputs\apk\debug\app-debug.apk"
