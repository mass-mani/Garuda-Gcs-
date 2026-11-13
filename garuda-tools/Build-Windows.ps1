param(
    [ValidateSet('Debug', 'RelWithDebInfo', 'Release')]
    [string]$Configuration = 'RelWithDebInfo',
    [int]$Parallel = 2,
    [string]$ReleaseLabel = 'v5.0.8-garuda.2',
    [switch]$EnableTests,
    [switch]$SkipViewerBuild,
    [switch]$Package
)
$ErrorActionPreference = 'Stop'
if ($EnableTests -and $Configuration -ne 'Debug') { throw 'QGC tests require -Configuration Debug.' }
$Source = Split-Path $PSScriptRoot -Parent
$Workspace = Split-Path $Source -Parent
$ToolRoot = Join-Path $Workspace '.build-tools'
$Qt = Join-Path $ToolRoot 'Qt/6.8.3/msvc2022_64'
$GStreamer = Join-Path $ToolRoot 'gstreamer/1.0/msvc_x86_64'
if (!(Test-Path (Join-Path $GStreamer 'bin/pkg-config.exe'))) { throw 'GStreamer SDK missing. Run garuda-tools/Setup-WindowsGStreamer.ps1 first.' }
$Build = Join-Path $Workspace "windows-$Configuration"
$VsWhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio/Installer/vswhere.exe'
if (!(Test-Path $VsWhere)) { throw 'Install Visual Studio 2022 Build Tools with the Desktop development with C++ workload.' }
$VsPath = & $VsWhere -latest -products '*' -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
if (!$VsPath) { throw 'MSVC x64 build tools are missing.' }
$env:Path = "$(Split-Path $VsWhere -Parent);$env:Path"
Import-Module (Join-Path $VsPath 'Common7/Tools/Microsoft.VisualStudio.DevShell.dll')
Enter-VsDevShell -VsInstallPath $VsPath -SkipAutomaticLocation -DevCmdArguments '-arch=x64 -host_arch=x64'
$env:Path = "$ToolRoot/python-env/Scripts;$Qt/bin;$env:Path"
if (!$SkipViewerBuild) {
    & (Join-Path $PSScriptRoot 'Build-UAVLogViewer.ps1')
    if ($LASTEXITCODE) { throw 'UAV Log Viewer asset build failed.' }
}
$ConfigureArgs = @('-S', $Source, '-B', $Build, '-G', 'Ninja',
    "-DCMAKE_BUILD_TYPE=$Configuration", '-DQGC_STABLE_BUILD=ON',
    "-DGARUDA_RELEASE_VERSION=$ReleaseLabel",
    '-DQGC_ORG_DOMAIN=garudaaerospace.com',
    '-DQGC_APP_COPYRIGHT=Copyright (c) 2025 Garuda Aerospace. All rights reserved.',
    "-DQGC_BUILD_TESTING=$(if ($EnableTests) { 'ON' } else { 'OFF' })", '-DQGC_ENABLE_GST_VIDEOSTREAMING=ON',
    '-DQGC_ENABLE_QT_VIDEOSTREAMING=OFF', '-DQGC_ENABLE_UVC=ON',
    "-DGStreamer_ROOT_DIR=$GStreamer")
if ($Package) {
    $ConfigureArgs += '-DGARUDA_ENABLE_DEPLOYMENT=ON'
} else {
    $ConfigureArgs += '-DGARUDA_ENABLE_DEPLOYMENT=OFF'
}
$Cache = Join-Path $Workspace 'android-short/cpm_modules'
if (Test-Path $Cache) { $ConfigureArgs += "-DQGC_CPM_SOURCE_CACHE=$Cache" }
& "$Qt/bin/qt-cmake.bat" @ConfigureArgs
if ($LASTEXITCODE) { throw 'Windows configuration failed.' }
cmake --build $Build --parallel $Parallel
if ($LASTEXITCODE) { throw 'Windows compilation failed.' }
if ($Package) {
    cmake --install $Build --config $Configuration
    if ($LASTEXITCODE) { throw 'Windows deployment failed.' }
    $Installer = Join-Path $Build 'GarudaGCS-installer.exe'
    if (!(Test-Path $Installer)) { throw 'Windows installer was not produced. Check that NSIS is installed.' }
    $Artifacts = Join-Path $Source 'artifacts'
    New-Item -ItemType Directory -Force $Artifacts | Out-Null
    Copy-Item -LiteralPath $Installer -Destination (Join-Path $Artifacts "Garuda-GCS-Windows-x64-$Configuration-Setup.exe")
}
Write-Host "Windows build ready in $Build"
