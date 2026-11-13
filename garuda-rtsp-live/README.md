# Garuda Stream

Native Kotlin / Compose Android app for **RTSP camera → transmitter-hosted LAN RTSP** streaming, with optional remote RTMP/RTSP publishing.
Application ID: `com.garuda.rtsplive`. Separate from `../garuda-live-stream`.
Current app version: **1.0.28** (version code 29). Branded as Garuda Stream with the Garuda logo. Includes an RTSP server on the transmitter so viewers do not depend on a receiver laptop.

## APKs and platform

- Installable debug APK: `release/Garuda-Stream-1.0.28-UniRC7Pro-debug.apk` (ARM64 debug build; Android 7.1 or later).
- Unsigned release APK: generated as `app/build/outputs/apk/release/app-release-unsigned.apk`.
- Minimum Android 7.1, API 25. Compile/target Android 16, API 36.
- CPU ABIs: **arm64-v8a and x86_64**. No 32-bit ARM build in this dependency.
- Java 17, Gradle 8.14, AGP 8.13.1, Kotlin/Compose compiler 2.2.20.
- Media3 1.8.0; Compose BOM 2025.09.01; DataStore 1.1.7.
- FFmpeg: **`dev.ffmpegkit-maintained:ffmpeg-kit-https:8.1.9`** from Maven Central, HTTPS/LGPL variant. No original retired `com.arthenica:ffmpeg-kit` artifact is used. The Java package remains `com.arthenica.ffmpegkit` for API compatibility.

The maintained [release](https://github.com/ffmpegkit-maintained/ffmpeg/releases) and [Maven metadata](https://repo.maven.apache.org/maven2/dev/ffmpegkit-maintained/ffmpeg-kit-https/maven-metadata.xml) were checked on 2026-10-06. Runtime build configuration was checked for absence of `--enable-gpl` and `--enable-nonfree`.

## Build

Open this directory, not the Qt/GCS root, in Android Studio. Install Android SDK platform 36 and a current build-tools package. Set `JAVA_HOME` to JDK 17. Android Studio can create `local.properties`; on a separate machine its contents should resemble:

```properties
sdk.dir=C\:/Users/YOUR_NAME/AppData/Local/Android/Sdk
```

In PowerShell, from this directory:

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
.\gradlew.bat :app:assembleRelease
```

The first run downloads Gradle and dependencies. This workspace already has its SDK in `../../.build-tools/android-sdk`; its machine-specific `local.properties` is ignored by Git. The embedded server AAR is included at `app/libs/localrtsp.aar`, so normal APK builds do not need Go or the NDK. To regenerate that AAR, install Go 1.27.1 and Android NDK 28.2, then run `..\garuda-tools\Build-LocalRtsp-Aar.ps1` from this directory.

The installable debug APK is signed with the local Android debug key. The release APK is **unsigned**; use Android Studio **Build → Generate Signed App Bundle or APK** with your own private signing key before distributing it. No production signing key has been generated or embedded.

## Install on G20 / G12

Enable Developer options and USB debugging on the transmitter, connect USB, and accept its debugging prompt. Use the SDK's `platform-tools/adb`:

```powershell
adb devices
adb shell getprop ro.build.version.release
adb shell getprop ro.product.cpu.abilist
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n com.garuda.rtsplive/.MainActivity
```

In this workspace, replace `adb` with `..\..\.build-tools\android-sdk\platform-tools\adb.exe` if it is not on PATH. The device ABI list must include `arm64-v8a` (or `x86_64` for emulators). `INSTALL_FAILED_NO_MATCHING_ABIS` means a separate compatible native build is needed.

## First camera → transmitter RTSP stream

1. Power the camera and transmitter. Select its model in **Camera Model**; the RTSP URL stays editable.
2. Connect the transmitter to the Wi-Fi/router where viewer laptops will connect. Keep the camera's separate video-link network active. This local path does not need internet.
3. Open **Garuda Stream**. For Skydroid C12, **C12 VISIBLE** selects `rtsp://192.168.144.108:554/stream=1`; **C12 THERMAL** selects `rtsp://192.168.144.108:555/stream=2`. Other models require their RTSP URL from the camera documentation. Percent-encode special characters in camera credentials.
4. Press **TEST RTSP**. The app prepares the RTSP stream through the camera's routed network and reports its video codec and dimensions. Allow up to 18 seconds for a failed connection test.
5. **CONNECT CAMERA** runs that test and opens preview. SHOW/HIDE PREVIEW controls a separate camera connection. Hide preview to reduce decoding load and camera client usage.
6. Leave **TRANSMITTER RTSP** selected. Set **Stream name** (default `drone0`). The transmitter hosts `rtsp://<transmitter-wifi-ip>:8555/live/<name>`; no receiver laptop, MediaMTX, or internet service is involved.
7. Press **START STREAM**. The app displays the current viewer URL. Enter it in Garuda Control Room on each laptop. VLC can also be used for a direct protocol check. Multiple viewers can connect at once, and a viewer disconnect does not stop the transmitter's publisher.
8. Keep the viewer laptops on a network that can reach the transmitter. The router must allow Wi-Fi clients to communicate. If the transmitter is `192.168.0.x` and a laptop is `192.168.1.x`, they are on different subnets; connect both to the same SSID/subnet or configure routing between them.
9. Any client on the reachable local network that knows the URL can view this stream; local mode does not require viewer credentials. The stream is not exposed through the internet by this app.
10. Press **STOP STREAM** in the app or persistent notification to stop the local server and cancel retries.

For the Skydroid C12, the app can relay HEVC/H.265 unchanged through RTSP. The ZT30's RTSP URL remains editable. Preview is separate; hide it before starting if the camera supports only one RTSP client.

The RTMP server operator must provide the correct application path and stream key. For the supplied DronestreamX address, `drone` is the stream key; configure the server field with the application path provided by that server's operator. No stream key is included in source or APK; user settings are stored encrypted on the device. The G20/Twitch smoke test recorded in [VALIDATION.md](VALIDATION.md) does not verify another server's credentials.

## Optional: publish to a separate RTMP/RTSP server

Choose **REMOTE SERVER** when you intentionally want to publish to a separate RTMP/RTSP endpoint. That endpoint must already be running and reachable. For laptop-based LAN publishing, MediaMTX can listen on the laptop, and the transmitter's remote server field uses that laptop's reachable address. This is independent of the default transmitter-hosted RTSP mode.

For this optional remote-server mode, put the transmitter and server laptop on a mutually reachable network. Internet is not required for private addresses. MediaMTX can listen on all laptop interfaces while Windows Firewall limits incoming RTMP/RTSP to local clients:

```yaml
rtmpAddress: 0.0.0.0:1935
rtspAddress: 0.0.0.0:8555
rtspTransports: [tcp]
paths:
  test/source1:
    source: rtsp://127.0.0.1:18554/source1
    sourceOnDemand: yes
  test/source2:
    source: rtsp://127.0.0.1:18554/source1
    sourceOnDemand: yes
  all_others:
```

Run MediaMTX with that configuration, then allow the two TCP ports from the local subnet in an elevated PowerShell window:

```powershell
New-NetFirewallRule -DisplayName "Garuda LAN RTMP" -Direction Inbound -Action Allow -Protocol TCP -LocalPort 1935 -RemoteAddress LocalSubnet -Profile Any
New-NetFirewallRule -DisplayName "Garuda LAN RTSP" -Direction Inbound -Action Allow -Protocol TCP -LocalPort 8555 -RemoteAddress LocalSubnet -Profile Any
```

Find the receiver laptop interface reachable by the transmitter with `ipconfig`. In **REMOTE SERVER** mode, H.264 RTMP output can publish to `rtmp://<server-ip>:1935/live/<stream-key>`; VLC opens the RTSP path served by MediaMTX at `rtsp://<server-ip>:8555/live/<stream-key>`. For remote RTSP publishing, enter the RTSP server URL and stream name in the app. The IP is network-specific. Build the APK with `gradlew.bat :app:assembleDebug`; the installable debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

### Two transmitters on one receiver

Use the same laptop IP and ports, but give every transmitter a unique path. For example, configure transmitter 1 to publish to `rtmp://<laptop-ip>:1935/live/camera1` and transmitter 2 to `rtmp://<laptop-ip>:1935/live/camera2`. Open the matching RTSP URLs in separate VLC windows: `rtsp://<laptop-ip>:8555/live/camera1` and `rtsp://<laptop-ip>:8555/live/camera2`. If both apps publish to the same path, they compete for one stream and cannot be viewed independently.

For transmitters without cameras, add a distinct MediaMTX path for each RTSP test URL. The `test/source1` and `test/source2` examples above both proxy the local H.264 fixture at `127.0.0.1:18554/source1`, so they provide distinct paths but the same test picture. Set transmitter 2's **Camera URL** to `rtsp://<laptop-ip>:8555/test/source1` and output to `rtmp://<laptop-ip>:1935/live/camera2`; set transmitter 3's input to `rtsp://<laptop-ip>:8555/test/source2` and output to `rtmp://<laptop-ip>:1935/live/camera3`. VLC opens the corresponding `/live/camera2` and `/live/camera3` outputs. These inputs work only while a test RTSP source is running on the receiver laptop.

RTMP itself is cleartext. Use an `rtmps://` endpoint when your provider offers it; TLS certificates and hostnames are validated. The app does not record media. A destination may independently record or retain broadcasts according to its own settings.

## Streaming design

- Default LAN path: camera RTSP/TCP → FFmpeg packet copy → loopback RTSP publisher → embedded transmitter RTSP server on port 8555 → multiple LAN readers. H.264 and HEVC are passed unchanged. Optional remote RTMP uses RTSP/TCP → H.264 packet copy or explicit hardware transcode → FLV → RTMP.
- The embedded RTSP server uses gortsplib. It listens while the foreground stream is active even if no viewer is connected; its local publisher is restricted to loopback. LAN readers need no credentials, so anyone on a reachable network who knows the URL can watch.
- TEST RTSP uses Media3 and confirms a supported video track, codec and dimensions. The publisher separately requires outgoing video bytes before it reports LIVE.
- Native FFmpeg runs in-process through its Android library. No shell executable runs on the transmitter.
- A 20-second output-stall watchdog closes broken publishing sessions. RTSP packet-copy progress uses advancing frame/time counters; RTMP also requires increasing output bytes. RTSP output does not report a byte rate on this FFmpeg muxer, so the app labels it unavailable. Network failures retry at **1, 2, 5, then 10 seconds**, capped at 10 seconds, until STOP. Connection/probe time is additional.
- In local mode the RTSP listener stays available while the camera publisher reconnects, and viewers can reconnect to the same URL. Malformed settings and codec compatibility problems require operator correction; they do not trigger expensive automatic transcoding.
- AUTO first copies. If codec/mux compatibility prevents startup, the UI offers Advanced settings. TRANSCODE requires explicit confirmation. It checks for `h264_mediacodec`, selects an Android hardware H.264 encoder supporting the requested dimensions/FPS, and passes its exact name to FFmpeg. **No software encoder is silently selected.** On older APIs, recognized vendor encoders are used. Unrecognized or unsupported hardware is rejected with a useful message.
- Transcode settings: original/1080p/720p bounding size (aspect ratio retained, even dimensions), original/30/25 FPS, 2/3/4/6 Mbps, approximately two-second GOP. Transcode drops incoming frames whose properties change midstream so a camera parameter-set update does not restart the filter graph. Decode/scale work may still use CPU. Hardware behavior needs device testing.
- `+genpts` is opt-in, for timestamp issues only. The default does not apply wall-clock timestamps or alter valid source timestamps.
- Current bitrate is derived from output-byte deltas; duration is elapsed session time since first live output, including reconnect gaps. Packet/drop metrics not exposed reliably by this FFmpeg API are labelled unavailable.

## Networking

`Networks.kt` monitors Android networks. The Media3 preview, TEST RTSP probe, and native FFmpeg input carry RTSP/TCP through `RoutedGateway`, which opens the upstream camera socket on the matching camera interface (such as the transmitter's `ar_net0`). This matters on transmitters where Android's default network is Wi-Fi but the camera uses a separate, vendor-managed Ethernet interface. Local-mode FFmpeg publishes to `127.0.0.1:8555`; remote RTMP is sent to its configured host through Android routing. The relay leaves FFmpeg's unsupported `-localaddr` option unset.

`RoutedGateway` passes preview TCP media through bounded in-memory buffers on private loopback listeners. RTSP control addresses are translated to preserve the original camera endpoint. Basic and MD5/SHA-256 Digest authentication are supported (Digest `auth`, not `auth-int`). Interleaved RTP payloads are forwarded unchanged. The embedded server accepts one local publisher and fans its RTP packets out to multiple reader sessions.

The camera's numeric default address is preferred for reliable multi-interface route selection. Overlapping subnets, VPN-only connectivity, OEM network restrictions and simultaneous physical Ethernet/mobile routing require field validation. No attempt is made to change system network configuration.

## Security and diagnostics

- DataStore persists AES-GCM-encrypted configuration using a non-exportable Android Keystore key, including source credentials, destination and stream key. Backups/device transfers are excluded. Clearing app data or uninstalling deletes settings.
- Stream keys/full URLs are masked by default. Android secure-window protection prevents screenshots/recents captures of the UI. No crash-reporting SDK or analytics is installed.
- The transmitter-hosted RTSP path has no viewer authentication or encryption. It is reachable only where the Wi-Fi/router permits device-to-device traffic; do not port-forward port 8555 to the public internet. Use a trusted LAN and share the URL only with intended viewers.
- Diagnostics retain at most 200 messages in memory. Native errors include a short context string after removing configured camera/destination URLs and keys; RTSP/RTMP URLs and common credential parameters are redacted. Arbitrary exceptions and full native logs are never copied into UI logs. COPY LOG exports only this sanitized list. CLEAR LOG empties it.
- Native Logcat forwarding is disabled. `SecureSessionRunner` is a small **version-pinned integration adapter** because FFmpegKit's default Java exception path prints complete command arguments. It invokes the same JNI bridge while replacing failure details with a generic error. Keep minification off or preserve this bridge if enabling R8. Re-run instrumented tests before updating FFmpegKit.
- `BoundedSession` clears native Java log/statistics history after callbacks so a long stream does not accumulate raw logs or an unbounded statistics list. These two adapters depend on the pinned FFmpegKit implementation.
- Notifications contain status only. Use Diagnostics when requesting support; do not share stream keys.

The foreground service uses Android's [connected-device type](https://developer.android.com/develop/background-work/services/fgs/service-types), since it maintains network communication with an external IP camera. It is started from the visible app and holds a partial wake lock during streaming. No boot auto-start is implemented. Force-stop, OS process termination, device shutdown and restrictive OEM battery policies can end a broadcast; open the app and press START again after those events.

## Tests and evidence

See [VALIDATION.md](VALIDATION.md) for actual results and limits. Unit tests check command construction, URL validation, credential-safe diagnostics and explicit transcoding. Instrumented tests exercise the real FFmpeg/Media3 libraries, actual video packets, Digest authentication, foreground operation, notification STOP, and destination-loss recovery.

### Reproduce emulator tests

Use an Android x86_64 emulator. Tests expect an H.264 RTSP source at host `127.0.0.1:18554/source1` and MediaMTX at RTMP port 19350 / RTSP port 18555. The emulator reaches the host as `10.0.2.2`.

Install [MediaMTX](https://github.com/bluenviron/mediamtx/releases) on the development PC and, **for test fixtures only**, a host FFmpeg build with libx264. In three terminals, from this directory:

```powershell
mediamtx .\test-fixtures\source.yml
```

```powershell
ffmpeg -re -f lavfi -i "testsrc2=size=640x360:rate=15" -c:v libx264 -pix_fmt yuv420p -tune zerolatency -g 15 -an -f rtsp -rtsp_transport tcp rtsp://127.0.0.1:18554/source1
```

```powershell
mediamtx .\test-fixtures\destination.yml
```

Then:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

The fixture password in the destination configuration is public test data and only its local listener is exposed. Production settings are not used. The development run used an equivalent GStreamer `videotestsrc` H.264 source at 640×360/15fps and MediaMTX 1.21.1. Tests do not send video to Twitch.

Reports: `app/build/reports/tests/testDebugUnitTest/`, `app/build/reports/androidTests/connected/debug/`, and `app/build/reports/lint-results-debug.html`.

## Field checklist / troubleshooting

- **Unreachable camera / no packets:** confirm UniGCS video, camera power, cable/link, camera IP/subnet and stream path. Some cameras limit concurrent clients; close UniGCS preview temporarily or hide this app's preview to diagnose.
- **Authentication rejected:** verify camera username/password or destination stream key. Correct and press START again. URL credentials must be percent-encoded.
- **Unsupported codec:** select H.264 in the camera. COPY does not convert H.265 to H.264. Try confirmed hardware TRANSCODE only if needed.
- **RTMP refused/DNS failure:** verify server, app path, port, internet connection and account status. A captive portal may require login first.
- **LIVE but no Twitch picture:** LIVE confirms outgoing bytes, not provider playback acceptance. Check Twitch dashboard/Inspector and camera encoding/GOP/bitrate requirements. This version is video-only; compatibility with a provider's current video-only ingest policy must be checked.
- **Timestamp errors:** stop, enable Generate timestamps, and retry. Do not enable it merely to lower latency.
- **Background stops on the transmitter:** inspect its battery restrictions for this app and ensure its internet connection stays active with UniGCS foregrounded. OS force-stop cannot be overridden.
- **Heat / lag:** hide preview, select the camera's lower-bitrate substream, keep COPY, and measure the transmitter's temperature and battery impact.

Before flight use, test on the ground: main/substream, correct/incorrect credentials, preview show/hide, portrait/landscape, one-hour streaming, UniGCS foreground, camera reboot, internet outage/restoration, RTMP disconnect, STOP during reconnection, and restart. Confirm independent viewer playback. Measure added latency and thermal behavior on the actual G20. A short G20/ZT30-to-Twitch RTMP publish was verified; it does not replace those longer field checks. See [VALIDATION.md](VALIDATION.md).

## Source map

- `MainActivity.kt`: Compose screens, ViewModel, independent lifecycle-managed Media3 preview.
- `Config.kt`: validated URLs, immutable configuration, FFmpeg argument arrays and safe error mapping.
- `AppState.kt`: application state, encrypted DataStore, bounded diagnostics.
- `Engine.kt`: packet probe, native relay, statistics and stall cancellation.
- `HardwareEncoder.kt`: hardware-only encoder/capability selection.
- `Networks.kt`: route selection and in-memory TCP gateways.
- `StreamService.kt`: foreground notification, wake lock, retry lifecycle and STOP.
- `com/arthenica/ffmpegkit/`: pinned privacy/memory integration adapters.

Third-party notices and license texts are in `THIRD_PARTY_NOTICES.md` and `app/src/main/assets/licenses/`.
