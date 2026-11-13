# Garuda GCS — Android first

Status: the Android ARM64 app has been built, installed on a Skydroid G12 transmitter, and launch-checked after the Android Qt Multimedia RTSP sink fix. This is a startup check only; the app has not been flight-tested. A Windows installer has not been verified in this session. Local Qt/Android toolchains and generated APKs are excluded from the source repository.

## What changed

- Display name: **Garuda GCS**. Internal build target / Windows executable: `GarudaGCS` / `GarudaGCS.exe` (no spaces for build-tool compatibility).
- Android installation ID: `com.garudaaerospace.gcs`. This is a proposed company namespace, not a verified Play Store registration. Confirm company ownership before public release.
- Company setting: Garuda Aerospace. QGroundControl's project attribution, license files and upstream links are retained.
- Supplied Garuda artwork used for Android launcher/splash icons, Windows app/installer icons, installer banner, toolbar, settings navigation and company wordmark.
- Java/JNI namespaces and QML imports retain their upstream names because renaming these would break linkage. Firmware flight-control logic has not been edited.
- Based on QGroundControl tag `v5.0.8`, commit `e0816c957602789200ae5ba0af45217f0f2f1db4`.
- ArduPilot parameter submodule contents are included at `a458e8e86a8ffa3b7f52f4601adcdaaff0db5f42`.

## Fastest build route: GitHub Actions

1. Create a private GitHub repository for Garuda GCS.
2. Extract this ZIP. From its `Garuda-GCS` folder, run:

   ```powershell
   git init
   git add .
   git commit -m "Prepare Garuda GCS branding from QGroundControl v5.0.8"
   git tag v5.0.8-garuda.1
   git branch -M main
   git remote add origin https://github.com/YOUR-ACCOUNT/YOUR-REPOSITORY.git
   git push -u origin main --tags
   ```

3. In the repository, open **Actions → Garuda GCS Android first → Run workflow**.
4. Leave **build_windows** unchecked for Android only. Check it to build Windows after Android succeeds.
5. On successful completion, download **Garuda-GCS-Android-arm64-TEST** from the workflow artifacts and extract its APK. The optional Windows artifact is **Garuda-GCS-Windows-installer**.

The workflow installs the SDKs on GitHub's runner. It has been syntax-checked but has not been executed. The original upstream workflows remain in this source; use the specifically named Garuda workflow, which does not publish to upstream servers or app stores.

## Build Android on Windows

Use a Windows 10/11 x64 development computer with an unrestricted internet connection. Allow at least 30 GB free space; 16 GB RAM is recommended for the build.

Install the prerequisites from PowerShell:

```powershell
winget install --id Git.Git --exact
winget install --id Python.Python.3.12 --exact
winget install --id EclipseAdoptium.Temurin.17.JDK --exact
winget install --id Microsoft.VCRedist.2015+.x64 --exact
```

Close and reopen PowerShell after installation. Set `JAVA_HOME` to the actual installed JDK 17 directory if the installer did not set it. Extract this project to a short path such as `C:\Garuda-GCS`. From that folder run:

```powershell
.\garuda-tools\Setup-Android.ps1 -ToolRoot C:\GarudaGCS-Tools
```

If Windows blocks a downloaded script, inspect its contents and use `Unblock-File` on the two scripts; do not weaken machine-wide execution policy. An organization-managed execution-policy restriction needs your IT administrator.

The setup script downloads Qt 6.8.3 (Windows host plus Android ARM64), Android SDK 35, build tools 35.0.0, and NDK 26.1.10909125, then compiles and signs an internal-test APK. It prompts for Android SDK licenses. Download/setup scripts have not been executed on Windows in this session.

For subsequent builds without reinstalling, ensure the installed CMake/Ninja virtual environment is on PATH:

```powershell
$env:Path = "C:\GarudaGCS-Tools\python-env\Scripts;$env:Path"
.\garuda-tools\Build-Android.ps1 -QtRoot C:\GarudaGCS-Tools\Qt -AndroidSdk C:\GarudaGCS-Tools\android-sdk
```

Expected successful output: `artifacts\Garuda-GCS-Android-arm64-TEST.apk`.

Initial target: **ARM64 Android 9 or later**. Older Android controllers (such as Android 7 Herelink devices) require a separate Qt 6.6.3 / Herelink build; do not use this configuration for them.

## Windows build

The optional GitHub Actions job uses Windows Server 2022, MSVC 2022, Qt 6.8.3, GStreamer 1.22.12 and NSIS. It produces `GarudaGCS-installer.exe`. The Windows installer is not code-signed. It has not been built here.

## Signing and validation

- The supplied Android automation creates an **internal-test** signing key. Do not distribute that as the production signing identity. GitHub generates a new key each run, so successive test builds may require uninstalling the previous test app, which deletes that app's local settings/data. The local Windows script reuses its generated key while its build folder is preserved.
- For company releases, supply a company-owned Android keystore through your private build secrets and keep it backed up. No production keystore was created or uploaded.
- Before operational use, verify installation, displayed name/icons, map loading, mission planning, UDP/USB telemetry, video, settings persistence and PX4/ArduPilot behavior in simulation and on a bench. This build has not been flight-tested.
- Keep upstream license and attribution files with your distribution and source.

## Verification performed in this session

`git diff --check` passed. The Android manifest parsed with the expected application name and package identity. Resource references in all three top-level QRC files resolve. The new workflow parsed as YAML. Existing Java/JNI class paths and Android provider isolation were checked. These checks do not establish compilation or runtime correctness.

Official source: https://github.com/mavlink/qgroundcontrol/tree/v5.0.8
Official developer guide: https://dev.qgroundcontrol.com/
