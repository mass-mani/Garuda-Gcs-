param([string]$ToolRoot = 'C:\GarudaGCS-Tools')
$ErrorActionPreference = 'Stop'
# Install Git, Python 3.12 and JDK 17 first; see GARUDA-README.md.
foreach ($cmd in @('python','java','git')) {
    if (!(Get-Command $cmd -ErrorAction SilentlyContinue)) { throw "Missing $cmd. Install prerequisites listed in GARUDA-README.md, then reopen PowerShell." }
}
New-Item -ItemType Directory -Force $ToolRoot | Out-Null
$Venv = Join-Path $ToolRoot 'python-env'
if (!(Test-Path "$Venv/Scripts/python.exe")) {
    python -m venv $Venv
    if ($LASTEXITCODE) { throw 'Python environment creation failed' }
}
$Python = "$Venv/Scripts/python.exe"
& $Python -m pip install aqtinstall==3.3.0 'cmake>=3.25,<4' ninja
if ($LASTEXITCODE) { throw 'Build-tool installation failed' }
$Modules = @('qtcharts','qtlocation','qtpositioning','qtspeech','qt5compat','qtmultimedia','qtserialport','qtimageformats','qtshadertools','qtconnectivity','qtquick3d','qtsensors')
& $Python -m aqt install-qt windows desktop 6.8.3 win64_msvc2022_64 -O "$ToolRoot/Qt" -m $Modules
if ($LASTEXITCODE) { throw 'Qt host installation failed' }
& $Python -m aqt install-qt windows android 6.8.3 android_arm64_v8a -O "$ToolRoot/Qt" -m $Modules
if ($LASTEXITCODE) { throw 'Qt Android installation failed' }
$Sdk = Join-Path $ToolRoot 'android-sdk'
$Manager = "$Sdk/cmdline-tools/latest/bin/sdkmanager.bat"
if (!(Test-Path $Manager)) {
    Invoke-WebRequest 'https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip' -OutFile "$ToolRoot/android-tools.zip"
    New-Item -ItemType Directory -Force "$Sdk/cmdline-tools" | Out-Null
    Expand-Archive "$ToolRoot/android-tools.zip" "$Sdk/cmdline-tools" -Force
    Move-Item "$Sdk/cmdline-tools/cmdline-tools" "$Sdk/cmdline-tools/latest"
}
& $Manager "--sdk_root=$Sdk" --licenses
if ($LASTEXITCODE) { throw 'Android SDK license acceptance is required' }
& $Manager "--sdk_root=$Sdk" 'platform-tools' 'platforms;android-35' 'build-tools;35.0.0' 'ndk;26.1.10909125'
if ($LASTEXITCODE) { throw 'Android SDK installation failed' }
$env:Path = "$Venv/Scripts;$env:Path"
& "$PSScriptRoot/Build-Android.ps1" -QtRoot "$ToolRoot/Qt" -AndroidSdk $Sdk
