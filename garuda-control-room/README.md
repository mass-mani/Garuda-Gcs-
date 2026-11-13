# Garuda Live Viewer

Garuda Live Viewer is a native Windows WPF application for monitoring multiple independent camera feeds. It supports 1–4 rows and columns (up to 16 inputs), RTSP over TCP or UDP, HTTPS HLS playback, direct HTTP video streams supported by VLC, provider-hosted HTTPS player pages opened in the default browser, saved camera names and credentials, per-input connection tests, automatic reconnects, one-click reconnect all, optional audio per feed, an always-on-top window option, and full screen playback.

## Requirements

- Windows 10/11, x64
- Visual Studio 2022 or later with the .NET desktop development workload, or .NET 8 SDK
- Network access to each camera's RTSP address (the application itself stores configuration offline)

## Build from source

Open `Garuda.ControlRoom.sln` in Visual Studio and build `Release | Any CPU`, or run:

```powershell
dotnet restore .\Garuda.ControlRoom.sln
dotnet build .\Garuda.ControlRoom.sln -c Release
dotnet run --project .\tests\Garuda.ControlRoom.SmokeTests.csproj -c Release
```

The solution includes a small dependency-free smoke-test executable for layout sizing, configuration copying, log redaction, and DPAPI-encrypted settings storage.

## Publish a Windows release

From this directory, the release script builds the app, runs the offline smoke checks, publishes the self-contained x64 folder, generates VLC's plugin cache if needed, checks the VLC runtime, and creates a ZIP archive:

```powershell
.\tools\Publish-Windows.ps1
```

The portable archive is `release\Garuda-Live-Viewer-win-x64.zip`. For a manual publish without the tests or archive:

```powershell
dotnet publish .\src\Garuda.ControlRoom\Garuda.ControlRoom.csproj -c Release -r win-x64 --self-contained true -o .\release\win-x64
```

Run `release\win-x64\Garuda.LiveViewer.exe`. Keep the complete published folder together: VLC's native runtime and plugins are shipped alongside the app. The .NET runtime is included, so a separate .NET installation is not required on the target PC. To make a portable archive, zip the **contents** of `release\win-x64` together.

The publish script also starts the VLC runtime once and fails if that check does not complete within 90 seconds. Generating the plugin cache can take about 1–2 minutes on a clean output folder; the cache is shipped with the release so normal startup is faster.

To probe a reachable RTSP stream using the same LibVLC test path as the app's **Test Stream** button, run:

```powershell
dotnet run --project .\tests\Garuda.ControlRoom.SmokeTests.csproj -c Release -- --rtsp rtsp://192.168.137.1:8555/test/source1
```

This exits successfully only after VLC receives and decodes video frames. It does not modify saved app settings.

## First run

Select **Settings**, choose rows and columns, set a name and RTSP URL for each camera, choose TCP (recommended for most routed/Wi-Fi networks) or UDP, and select **Test URL** to confirm video arrives. If credentials are embedded in a URL, they are moved into the separate credential fields when that URL field loses focus or is tested. Passwords are hidden until **Show** is selected. Audio stays muted by default; enable it for the feed whose sound you want. **Keep on top** keeps the monitor above other windows. Use **Apply** to update the live screen, **Save** to apply and store the configuration, and **Cancel** to restore the settings that were present when the window opened. Select **Reconnect all** in the header to restart every configured feed without changing its settings.

The app accepts viewer endpoints such as `rtsp://192.168.137.17:8555/live/drone` or `https://cdn.example/live/drone/index.m3u8`. Select **Hosted player page (open in browser)** for an HTTPS provider embed page; its tile launches the page in the default browser. Use a direct HLS URL to play video inside the grid. RTMP publish URLs are rejected as viewer URLs. On the main view, double-click an RTSP/HLS tile or select its expand button; press **Esc** to leave full screen. Full-screen changes reuse the active LibVLC sessions. A failed input retries without stopping other streams.

## Worldwide cloud viewing

The Android transmitter must publish outbound RTMP/RTMPS to a real cloud provider using the ingest URL and stream key from the account. The Windows viewer then opens the provider's direct HLS URL or launches the HTTPS embed player in the default browser. An RTMP publish address is not a playback address. Direct HLS may require a specific plan and provider provisioning; the embed page opens externally. See [WORLDWIDE-STREAMING.md](../garuda-rtsp-live/WORLDWIDE-STREAMING.md) for transmitter setup and test status.

## Local files and privacy

- Settings are stored under `%LOCALAPPDATA%\Garuda Control Room\settings.dat`, protected with Windows DPAPI for the current Windows user.
- Logs are written under `%LOCALAPPDATA%\Garuda Control Room\Logs`. Stream credentials are not included in log messages; the logger also redacts RTSP URL user information and common password parameters.
- Settings saved with DPAPI are tied to the Windows user profile on that PC. Export/import between accounts is not implemented.

## Notes

- Actual stream reachability depends on the camera URL, credentials, router/firewall rules, provider account, stream-key permissions, and supported codec/transport. The viewer uses LibVLC/VLC for H.264 and H.265/HEVC decoding when supported by the VLC build and hardware.
- Several high-resolution streams can use substantial CPU, GPU, memory, and network bandwidth. Start with fewer feeds and lower camera bitrates on modest PCs.
- VLC and LibVLCSharp are third-party LGPL-licensed components. Their native libraries remain separate files in the release folder. The release contains the LGPL-2.1 license text and upstream notices; retain them when redistributing. Review codec/patent requirements for your distribution region.

## Project layout

- `src/Garuda.ControlRoom/Models` — local app and camera settings
- `src/Garuda.ControlRoom/Services` — protected configuration, redacted log, VLC playback and tests
- `src/Garuda.ControlRoom/Views` — main monitor, video tile, and settings editor
- `tests` — offline smoke checks
- `release/win-x64` — self-contained published application
