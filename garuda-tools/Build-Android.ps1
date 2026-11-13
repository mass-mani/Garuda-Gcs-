param(
    [Parameter(Mandatory=$true)][string]$QtRoot,
    [Parameter(Mandatory=$true)][string]$AndroidSdk,
    [string]$JavaHome = $env:JAVA_HOME
)
$ErrorActionPreference = 'Stop'
$Source = Split-Path $PSScriptRoot -Parent
$Build = Join-Path $Source 'build-android'
$Qt = Join-Path $QtRoot '6.8.3/android_arm64_v8a/bin/qt-cmake.bat'
$QtHost = Join-Path $QtRoot '6.8.3/msvc2022_64'
$Ndk = Join-Path $AndroidSdk 'ndk/26.1.10909125'
foreach ($path in @($Qt, $QtHost, $Ndk, $JavaHome)) {
    if (!$path -or !(Test-Path $path)) { throw "Missing prerequisite: $path. See GARUDA-README.md." }
}
$env:JAVA_HOME = $JavaHome
$env:ANDROID_HOME = $AndroidSdk
$env:ANDROID_SDK_ROOT = $AndroidSdk
$env:ANDROID_NDK_ROOT = $Ndk
$env:Path = "$JavaHome/bin;$env:Path"
& $Qt -S $Source -B $Build -G Ninja '-DCMAKE_BUILD_TYPE=Release' '-DQGC_STABLE_BUILD=ON' '-DQGC_ORG_DOMAIN=garudaaerospace.com' '-DQGC_APP_COPYRIGHT=Copyright (c) 2025 Garuda Aerospace. All rights reserved.' '-DQT_ANDROID_ABIS=arm64-v8a' '-DQT_ANDROID_BUILD_ALL_ABIS=OFF' '-DQGC_ENABLE_QT_VIDEOSTREAMING=ON' "-DQT_HOST_PATH=$QtHost" "-DANDROID_SDK_ROOT=$AndroidSdk" "-DANDROID_NDK_ROOT=$Ndk" '-DQT_ANDROID_SIGN_APK=OFF'
if ($LASTEXITCODE) { throw 'Android configuration failed' }
cmake --build $Build --parallel 2
if ($LASTEXITCODE) { throw 'Android compilation failed' }
$Apk = Get-ChildItem "$Build/android-build" -Recurse -Filter '*.apk' | Select-Object -First 1
if (!$Apk) { throw 'Build did not produce an APK' }
$Out = Join-Path $Source 'artifacts'
New-Item -ItemType Directory -Force $Out | Out-Null
$Key = Join-Path $Build 'garuda-test.keystore'
if (!(Test-Path $Key)) {
    & "$JavaHome/bin/keytool.exe" -genkeypair -keystore $Key -storepass android -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 365 -dname 'CN=Garuda GCS Internal Test,O=Garuda Aerospace,C=IN'
    if ($LASTEXITCODE) { throw 'Test key creation failed' }
}
& "$AndroidSdk/build-tools/35.0.0/zipalign.exe" -f -p 4 $Apk.FullName "$Build/aligned.apk"
if ($LASTEXITCODE) { throw 'APK alignment failed' }
& "$AndroidSdk/build-tools/35.0.0/apksigner.bat" sign --ks $Key --ks-pass pass:android --key-pass pass:android --ks-key-alias androiddebugkey --out "$Out/Garuda-GCS-Android-arm64-TEST.apk" "$Build/aligned.apk"
if ($LASTEXITCODE) { throw 'APK signing failed' }
& "$AndroidSdk/build-tools/35.0.0/apksigner.bat" verify --verbose "$Out/Garuda-GCS-Android-arm64-TEST.apk"
if ($LASTEXITCODE) { throw 'APK verification failed' }
Write-Host "APK ready: $Out/Garuda-GCS-Android-arm64-TEST.apk"
