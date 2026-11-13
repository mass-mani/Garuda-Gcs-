# Garuda GCS

Garuda GCS is a Garuda Aerospace customized ground control station based on [QGroundControl](https://github.com/mavlink/qgroundcontrol). It is intended for MAVLink vehicles using ArduPilot or PX4.

## Features

- Android ARM64 build configured for the Skydroid G12 class of Android transmitter.
- Servo 7–14 high/low actions in the flight screen, sent through MAVLink `MAV_CMD_DO_SET_SERVO`.
- RTSP video settings and Qt Multimedia video output on the Android build.
- Garuda flight panels, vehicle tools, and optional log viewer integrations in the source tree.

The GCS is a ground station interface. Vehicle firmware, servo output functions, and failsafe behavior still need to be configured for the specific aircraft. Bench-test changes before flight; this project has not been flight-tested.

## Build Android

See [GARUDA-README.md](GARUDA-README.md) for the Windows prerequisites and complete setup. In brief, install Git, Python 3.12, and JDK 17, then run PowerShell from the repository root:

```powershell
.\garuda-tools\Setup-Android.ps1 -ToolRoot C:\GarudaGCS-Tools
```

For a later build after setup:

```powershell
$env:Path = 'C:\GarudaGCS-Tools\python-env\Scripts;' + $env:Path
.\garuda-tools\Build-Android.ps1 `
  -QtRoot C:\GarudaGCS-Tools\Qt `
  -AndroidSdk C:\GarudaGCS-Tools\android-sdk
```

The script writes an internal-test APK to `artifacts\Garuda-GCS-Android-arm64-TEST.apk`. It creates or reuses a local test-signing key under the ignored build directory. That test key is not a production signing identity.

## Build Windows

Windows build automation and prerequisites are documented in [GARUDA-README.md](GARUDA-README.md) and [garuda-tools/WINDOWS-DEBUG.md](garuda-tools/WINDOWS-DEBUG.md). The Windows package script requires Qt, MSVC Build Tools, and GStreamer.

## GitHub Actions

The manual workflow at `.github/workflows/garuda-build.yml` builds an Android ARM64 internal-test APK. Open **Actions → Garuda GCS Android first → Run workflow** after pushing the source and initializing its Git submodules. The optional Windows job can be selected in the workflow inputs.

## Repository contents

- `src/`, `android/`, and `resources/`: GCS application source and assets.
- `garuda-tools/`: local build, packaging, and setup scripts.
- `garuda-control-room/`, `garuda-live-stream/`, `garuda-rtsp-live/`: related Garuda tools and their source projects.
- `third_party/UAVLogViewer/`: UAV log viewer integration source.

Build directories, generated APKs, local credentials, and packaged release binaries are excluded by `.gitignore`. Run `git submodule update --init --recursive` after cloning so the ArduPilot parameter repository is available.

## License and attribution

This project includes and modifies QGroundControl. Keep the upstream license, attribution, and third-party notices when redistributing it. Review `LICENSE-GPL`, `LICENSE-APACHE`, and the notices in each bundled component before publishing or distributing binaries. Upstream project: [mavlink/qgroundcontrol](https://github.com/mavlink/qgroundcontrol).
