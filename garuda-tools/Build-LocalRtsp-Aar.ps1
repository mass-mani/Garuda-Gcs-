$ErrorActionPreference = 'Stop'

$workspace = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$appRoot = Join-Path $workspace 'Garuda-GCS\garuda-rtsp-live'
$moduleRoot = Join-Path $appRoot 'app\src\main\go\localrtsp'
$toolRoot = Join-Path $workspace '.build-tools'
$sdk = Join-Path $toolRoot 'android-sdk'
$ndk = Join-Path $sdk 'ndk\28.2.13676358'
$goRoot = Join-Path $toolRoot 'go1.27.1\go'
$go = Join-Path $goRoot 'bin\go.exe'
$goBin = Join-Path $toolRoot 'gomobile-bin'
$goPath = Join-Path $toolRoot 'gomobile-gopath'
$gomobileVersion = 'v0.0.0-20260908204917-8b95e45f8d3e'
$output = Join-Path $appRoot 'app\libs\localrtsp.aar'

if (!(Test-Path $go)) {
    $installedGo = Get-Command go.exe -ErrorAction SilentlyContinue
    if (!$installedGo) { throw 'Go 1.27.1 is required to rebuild the embedded RTSP AAR.' }
    $go = $installedGo.Source
    $goRoot = Split-Path (Split-Path $go -Parent) -Parent
}
if (!(Test-Path $sdk)) { throw "Android SDK not found: $sdk" }
if (!(Test-Path $ndk)) { throw "Android NDK 28.2.13676358 not found: $ndk" }

$env:GOPATH = $goPath
$env:GOBIN = $goBin
$env:ANDROID_HOME = $sdk
$env:ANDROID_NDK_HOME = $ndk
$env:Path = (Join-Path $goRoot 'bin') + ';' + $goBin + ';' + $env:Path
New-Item -ItemType Directory -Force -Path $goBin,(Split-Path $output) | Out-Null

$gomobile = Join-Path $goBin 'gomobile.exe'
if (!(Test-Path $gomobile)) {
    & $go install "golang.org/x/mobile/cmd/gomobile@$gomobileVersion"
    if ($LASTEXITCODE -ne 0) { throw 'Could not install gomobile.' }
}

Push-Location $moduleRoot
try {
    & $gomobile init
    if ($LASTEXITCODE -ne 0) { throw 'gomobile init failed.' }
    & $gomobile bind -target 'android/arm64,android/amd64' -androidapi 25 `
        -javapkg com.garuda.rtsplive -o $output .
    if ($LASTEXITCODE -ne 0) { throw 'Could not build the embedded RTSP AAR.' }
} finally {
    Pop-Location
}

Get-Item $output | Select-Object FullName,Length,LastWriteTime
