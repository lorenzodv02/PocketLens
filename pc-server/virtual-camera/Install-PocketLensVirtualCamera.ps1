#Requires -RunAsAdministrator

$ErrorActionPreference = "Stop"

if ([Environment]::Is64BitOperatingSystem -eq $false) {
    throw "PocketLens Virtual Camera requires 64-bit Windows."
}
if ([Environment]::OSVersion.Version.Build -lt 22000) {
    throw "PocketLens Virtual Camera requires Windows 11."
}

$sourceDll = Join-Path $PSScriptRoot "PocketLensVirtualCameraSource.dll"
$sourceExe = Join-Path $PSScriptRoot "PocketLensVirtualCamera.exe"
foreach ($sourceFile in @($sourceDll, $sourceExe)) {
    if (-not (Test-Path -LiteralPath $sourceFile -PathType Leaf)) {
        throw "Required virtual camera file was not found: $sourceFile"
    }
}

$dllHash = (Get-FileHash -LiteralPath $sourceDll -Algorithm SHA256).Hash.Substring(0, 12)
$installDirectory = Join-Path $env:ProgramFiles "PocketLens\VirtualCamera-$dllHash"
New-Item -ItemType Directory -Path $installDirectory -Force | Out-Null
$installedDll = Join-Path $installDirectory "PocketLensVirtualCameraSource.dll"
$installedExe = Join-Path $installDirectory "PocketLensVirtualCamera.exe"
Copy-Item -LiteralPath $sourceDll -Destination $installedDll -Force
Copy-Item -LiteralPath $sourceExe -Destination $installedExe -Force

$regsvr32 = Join-Path $env:WINDIR "System32\regsvr32.exe"
$registration = Start-Process -FilePath $regsvr32 -ArgumentList @("/s", "`"$installedDll`"") -Wait -PassThru
if ($registration.ExitCode -ne 0) {
    throw "Registering the PocketLens camera source failed with exit code $($registration.ExitCode)."
}

Write-Host "PocketLens Camera is installed and registered."
Write-Host "Open PocketLens PC and start the virtual camera host."
