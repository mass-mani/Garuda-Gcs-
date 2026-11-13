# Validation record

## GAPL ZT30 to laptop VLC — verified 2026-10-07

- Laptop on GAPL: `192.168.0.105/24`. ZT30 transmitter on GAPL: `192.168.0.104/24`; its camera interface is `192.168.144.11/24` and the ZT30 feed is `rtsp://192.168.144.25:8554/video1`.
- The camera feed recovered and passed the app test as HEVC 1920×1080. Garuda RTSP Live published it to MediaMTX at `rtsp://192.168.0.105:8555/live/zt30`.
- VLC on the laptop decoded and visibly displayed the ZT30 image using RTSP/UDP. The prior server configuration allowed only RTSP/TCP, while default VLC negotiated UDP; enabling both `[udp, tcp]` fixed viewer playback without changing the app publisher.
- The app remained LIVE for nearly five minutes with RTSP connected and sending. MediaMTX confirmed the H265 publisher at `live/zt30` and VLC reading the same path.
- Use `rtsp://192.168.0.105:8555/live/zt30` in VLC on other GAPL laptops while MediaMTX and the transmitter remain running. Other-client playback was not separately tested; the router must allow client-to-client traffic. This local stream does not require internet.

## GAPL router setup — 2026-10-07

- Laptop Wi-Fi (`GAPL`): `192.168.0.105/24`. Connected ZT30 transmitter Wi-Fi: `192.168.0.104/24`. They can ping each other; TCP connections from the transmitter to the laptop's RTSP `8555` and RTMP `1935` ports succeeded.
- MediaMTX 1.21.1 is listening on `0.0.0.0:8555` and `0.0.0.0:1935`. Windows Firewall allows both TCP ports from the local subnet. The laptop viewer address for this transmitter is `rtsp://192.168.0.105:8555/live/zt30`.
- Installed Garuda RTSP Live 1.0.21 (version code 22). Configured RTSP publish server `rtsp://192.168.0.105:8555/live/` and unique path/key `zt30`.
- The ZT30 input test did **not** pass on this transmitter: `rtsp://192.168.144.25:8554/video1` timed out. Its camera interface is `192.168.144.11/24`; a scan found no RTSP listener on ports 554 or 8554 in that camera subnet. MediaMTX did not receive a publisher, so VLC/other laptops will not have video until the transmitter can reach the camera feed.
- The laptop and transmitter LAN path is verified. To finish end-to-end testing, connect/enable the ZT30 video link and enter its working RTSP input address in the app. Then keep MediaMTX running and open `rtsp://192.168.0.105:8555/live/zt30` in VLC on any GAPL client laptop, assuming router client isolation is disabled. Internet is not required for this private-LAN path.

## Shared Wi-Fi C12 relay route fix — 2026-10-07

- Laptop and transmitter were confirmed on the same `FRIDAY` Wi-Fi: laptop `192.168.137.67/24`, transmitter `wlan0` `192.168.137.204/24`. The transmitter's separate camera interface is `ar_net0` at `192.168.144.11/24`; the kernel route for the C12 `192.168.144.108` correctly selects `ar_net0`, while the laptop route selects `wlan0`.
- Laptop MediaMTX 1.21.1 listened on `0.0.0.0:1935` (RTMP) and `0.0.0.0:8555` (RTSP/TCP). Its debug log verified a TCP connection from the transmitter to port 1935. This checks LAN reachability, not a valid RTMP publish.
- Garuda Live Stream 1.0.6 successfully previewed and tested the C12 visible feed as HEVC 1280×720, but no RTMP publisher appeared. Inspection found that FFmpeg received the camera's direct URL even though the preview used the interface-bound `RoutedGateway`. The relay could therefore fail on transmitters with a vendor-managed camera interface.
- Version 1.0.7 now routes FFmpeg's RTSP input through that same bridge. The RTMP output remains the laptop address; use a unique output such as `rtmp://192.168.137.67:1935/live/drone` and view `rtsp://192.168.137.67:8555/live/drone` from VLC. Because this C12 profile reports HEVC, select the explicitly confirmed hardware TRANSCODE mode unless the camera can be set to H.264.
- Unit tests and APK assembly passed after the routing change. Physical publish and VLC playback are recorded below after the new APK is installed and run; the TCP reachability check alone is not evidence of a working video stream.

## Physical C12 relay attempt — 2026-10-07

- Installed version 1.0.7 and confirmed its saved output resolves to the laptop's current `192.168.137.67` address. The camera RTSP check succeeded again as HEVC 1280×720, and Advanced TRANSCODE was selected.
- MediaMTX saw occasional RTMP connections. It accepted one short publisher at `live/drone` with one H.264 track, but the publisher ended after about one second; subsequent attempts timed out before publishing. The app's status briefly reported LIVE, then reconnected. Opening `rtsp://192.168.137.67:8555/live/drone` in VLC returned 404 because no publisher remained online.
- Version 1.0.10 reached the camera but failed before publishing: Diagnostics reported `Unrecognized option 'drop_changed'`. Version 1.0.11 fixed a race in the native encoder availability check; its `-reinit_filter:v 0` attempt still stopped when C12 frame properties changed. Version 1.0.12's legacy decoder flag was also rejected by FFmpeg 8.1. Version 1.0.13 switched to FFmpeg 7.1.6, but Android 13's linker refused GNU RELRO setup for an FFmpeg library. Version 1.0.14 packages native libraries extracted rather than loaded from inside the APK; retest before marking the C12 path working.
- The stream uses only the local Wi-Fi/router: transmitter `192.168.137.204`, receiver `192.168.137.67`, RTMP `1935`, RTSP `8555`. No internet access is required for these local addresses. Internet is only needed for a remote RTMP provider.

## Direct C12 HEVC to LAN RTSP — 2026-10-07

- The transmitter's `wlan0` is `192.168.137.191/24`; the camera remains on its separate `ar_net0` interface. The laptop Wi-Fi address `192.168.137.67` did not route from the transmitter (`No route to host` to TCP 8555), while the laptop's Wi-Fi Direct/hotspot interface `192.168.137.1` replied to the transmitter. The preset now targets that reachable interface.
- Versions 1.0.15 and 1.0.16 passed the C12 camera test as HEVC 1280×720; 1.0.16 reached MediaMTX at `.1`, which logged an online H265 publisher and RTP packets. The app then closed the session after 20 seconds because its LIVE watchdog required frame/time counters missing from this RTSP copy stream.
- Version 1.0.17 treats growing RTSP output bytes as stream progress and retains frame/time checks for RTMP. It still observed FFmpeg closing the publisher within about 2–9 seconds, so the RTSP source/read path needs further diagnosis.
- Version 1.0.18 Diagnostics showed FFmpeg counters `size=-1, time=2613 ms, frames=60`, followed by a clean exit. This proves H.265 frames reached the relay, while byte-size-based progress was wrong for the RTSP muxer.
- Version 1.0.19 uses advancing frame/time counters for RTSP LIVE status and marks unavailable network byte-rate as `—`. It logged a camera-media gateway `IllegalArgumentException` after frames reached the publisher.
- Version 1.0.20 validates interleaved RTP headers and reports a sanitized parser reason if the camera-side proxy closes. Retest this build for a sustained publisher and VLC playback.

## C12 to laptop VLC — verified with 1.0.20

- Installed version 1.0.20 (Android 13, arm64) over USB; the encrypted camera profile and stream key were retained.
- On this Windows hotspot link, the transmitter is `192.168.137.191`; laptop interface `192.168.137.1` is reachable, while the laptop's other Wi-Fi address `192.168.137.67` is not reachable from the transmitter. The app's LAN RTSP preset now uses `.1`. On another router, use the laptop address reachable from the transmitter and viewers.
- The C12 published HEVC 1280×720 over RTSP/TCP to MediaMTX port 8555. MediaMTX reported the publisher online and VLC reading the same path over TCP as one H265 track.
- The transmitter stayed **LIVE** for over five minutes with zero retries and zero errors while VLC remained open for that RTSP URL. MediaMTX retained both publisher and VLC reader TCP sessions. Diagnostics showed advancing frame/time counters; RTSP's byte-rate field is unavailable and displays `—`.
- This is a local-IP stream and does not use a hosted signaling service or remote RTMP provider. Internet remained available during this validation; a WAN-disconnected run was not separately performed.

## Skydroid C12 and receiver-laptop diagnosis — 2026-10-07

- Installed Garuda RTSP Live 1.0.6 (version code 7) on the USB-connected Bengal transmitter (Android 13, arm64). The app's RTSP test completed successfully through Media3 and reported the C12 visible feed as HEVC 1280×720. The app preview had already reached Media3's ready state on this camera.
- The C12 camera responds on its camera link at `rtsp://192.168.144.108:554/stream=1`; the thermal endpoint is `rtsp://192.168.144.108:555/stream=2`. See the [Skydroid C12 instructions](https://file.skydroid.xin/shuomingshu/C12-Instructions.pdf).
- The transmitter's Wi-Fi is `GAPL`, `192.168.0.102/24`, gateway `192.168.0.1`. The receiver laptop is on `Garuda`, `192.168.1.142/24`. A transmitter ping to `192.168.1.142` returned `Destination Net Unreachable`; Windows also could not see `GAPL` in its current Wi-Fi scan. This is not an end-to-end RTMP test because the two current Wi-Fi links are on different, unreachable subnets.
- MediaMTX is running on the laptop and listening on `0.0.0.0:1935` (RTMP) and `0.0.0.0:8555` (RTSP/TCP). Once the transmitter joins the laptop's reachable LAN, publish each device to the laptop's current IP using a unique path such as `/live/drone` and view `rtsp://<laptop-ip>:8555/live/drone` from each viewer laptop.
- The C12 feed is HEVC. The app's AUTO/COPY relay path intentionally requires H.264 for RTMP. Set the camera to H.264 if available, or select explicit hardware TRANSCODE in Advanced and verify the device supports it before publishing.

Run on 2026-10-06 with the Android 11 x86_64 `GarudaLiveApi30` emulator, a local H.264 RTSP fixture, and MediaMTX 1.21.1 on the host. The fixture was 640×360 at 15 fps.

`./gradlew :app:testDebugUnitTest :app:connectedDebugAndroidTest :app:lintDebug :app:assembleDebug :app:assembleRelease` completed successfully.

The eight instrumented tests verified:

- Media3 RTSP preview rendered its first decoded frame.
- Compose layouts rendered in portrait and landscape.
- Foreground streaming continued after Home and stopped from the notification action.
- Digest-authenticated RTSP succeeded without exposing the password.
- Invalid RTSP produced a useful failure.
- The bundled native build was LGPL/no GPL or nonfree flags and reported an H.264 MediaCodec encoder.
- RTSP H.264 packets were copied through RTMP and recovered after a forced destination disconnect.

The emulator tests are local integration tests. They do not validate physical hardware MediaCodec behavior or OEM battery restrictions.

## Physical G20 / SIYI ZT30 check

Also run on 2026-10-06 with the connected Bengal G20 (Android 13, arm64) and ZT30 camera:

- The production FFprobe path read the physical `video1` camera feed as H.264, 1920×1080 at 30 fps, with 152 packets in the sample. The camera route was `192.168.144.0/24` on `ar_net0`; the G20 retained internet on `wlan0`.
- The production foreground service sent the camera feed to Twitch’s current Mumbai ingest over RTMP. It reached **LIVE** and reported 1,330,802 outgoing bytes before the smoke test stopped it. It required startup retries; this was a short confirmation, not a long-duration or independent-viewer test.
- Installing version 1.0.1 migrated the saved legacy Twitch preset to the regional endpoint while retaining the encrypted saved key.
- Twitch ingest TCP/RTMPS reachability was also checked from the G20. RTMPS did not reach the app’s LIVE state in the smoke test, so the verified preset remains the official regional RTMP endpoint.

The previous failure was in the relay command: it passed `-localaddr` to FFmpeg, which reported `Option localaddr not found` and exited before opening the destination. FFprobe accepts that option, so the probe retains it; the relay now leaves interface binding unset and uses Android’s per-destination routes. The corrected relay was then verified on the physical camera and Twitch path.

The original “No video packets received” status also had a separate probe-output issue: repeated camera timing warnings preceded FFprobe’s JSON in a first-bytes-only buffer. The probe now suppresses those warnings and retains the end of the output, so it can parse the packet summary.

This validates a brief G20/ZT30 → Twitch publish only. One-hour stability, independent viewer playback, flight conditions, network loss/restoration, thermal behavior, and device battery restrictions still need field checks.

## Shared Wi-Fi hotspot to laptop VLC check

Run on 2026-10-06 with the G20 on the same Wi-Fi as the Windows receiver laptop:

- Laptop Wi-Fi address: `192.168.137.17/24`; G20 `wlan0`: `192.168.137.9/24`. The G20 camera link remained separate on `ar_net0` (`192.168.144.11/24`).
- A Wi-Fi Direct virtual adapter on the laptop also had an overlapping `192.168.137.0/24` route. The physical Wi-Fi interface metric was lowered to 5 so replies use the shared Wi-Fi. Windows Firewall permits TCP 1935 and 8555 from `LocalSubnet`.
- MediaMTX 1.21.1 listened on all laptop interfaces at RTMP port 1935 and RTSP/TCP port 8555. The clients used the laptop Wi-Fi address `192.168.137.17`.
- The installed Garuda Live Stream 1.0.4 app published from the G20; MediaMTX reported the stream online with an H.264 track. VLC opened the RTSP reader session over TCP and received the same H.264 stream.

For reuse on another Wi-Fi, use the laptop's current Wi-Fi IPv4 address in the app's editable RTMP URL and VLC's RTSP URL. The receiver listener and `LocalSubnet` firewall rules are reusable across DHCP networks. No APK rebuild is needed when only the receiver IP changes. The receiver host/interface and DHCP address can change between networks.

## Two-transmitter VLC check

On 2026-10-06, both Bengal G20 transmitters were connected to the Karthi hotspot and the Windows laptop:

- Original transmitter `192.168.137.9`: Garuda Live Stream 1.0.4 published the physical camera to `live/drone`.
- Second transmitter `192.168.137.55` (Android 13): Garuda Live Stream 1.0.4 was installed over USB debugging. As this transmitter had no camera attached, its RTSP input was set to `rtsp://192.168.137.17:8555/test/source1`. MediaMTX proxied the laptop's local H.264 test fixture (`127.0.0.1:18554/source1`) on demand. The app test read 76 H.264 packets at 640×360 / 15 fps.
- The second app's RTMP URL was `rtmp://192.168.137.17:1935/live/camera2`. Its UI reached **LIVE**, and MediaMTX reported an H.264 publisher at `live/camera2` from `.55` while the original transmitter remained LIVE at `live/drone` from `.9`.
- VLC opened both output paths in separate windows: `rtsp://192.168.137.17:8555/live/drone` and `rtsp://192.168.137.17:8555/live/camera2`. MediaMTX logged separate H.264 RTSP reader sessions for both paths.

This confirms two independent RTMP paths and simultaneous VLC playback. The second feed is generated test video, not a second camera feed. When a camera is attached to transmitter 2, change its **Camera URL** back to that camera's RTSP address; keep `/live/camera2` as the distinct output path. Both devices and VLC need to stay on the same LAN, and the laptop receiver must be running.

## Third transmitter with a separate test URL

Later on 2026-10-06, a third Bengal G20 joined Karthi at `192.168.137.77`. Garuda Live Stream 1.0.4 was installed over USB debugging. The app used RTSP input `rtsp://192.168.137.17:8555/test/source2` and RTMP output `rtmp://192.168.137.17:1935/live/camera3`. MediaMTX reported `test/source2` online with H.264 and the third transmitter publishing `live/camera3` with H.264. The distinct `test/source2` path is an alias to the laptop's same generated test fixture used by `test/source1`, so it is a separate link with the same generated picture, not another camera.

All three transmitters then published simultaneously: `.9` to `live/drone`, `.55` to `live/camera2`, and `.77` to `live/camera3`. Three separate VLC windows opened `rtsp://192.168.137.17:8555/live/drone`, `/live/camera2`, and `/live/camera3`; MediaMTX logged three distinct H.264 RTSP reader sessions.

## GAPL / ZT30 reconnect investigation — 2026-10-07

- The user clarified that the current issue is Garuda RTSP Live. No Garuda Control Room configuration or source was changed.
- Previous transmitter diagnostics showed FFmpeg input timeouts while reading the camera RTSP stream and repeated relay restarts. The camera endpoint was still reachable, but the ZT30 was reporting HEVC 1920×1080 at 0 fps during some probes.
- Built and installed Garuda RTSP Live 1.0.22 (version code 23) on the Android 13 transmitter. The change holds a Wi-Fi performance lock during relay, extends RTSP input/output read timeouts to 15 seconds, and resets reported LIVE time/retry backoff on each relay attempt.
- Current shared Wi-Fi: transmitter `192.168.0.104`, laptop/MediaMTX `192.168.0.105`. MediaMTX accepted an RTSP/TCP ANNOUNCE from the transmitter at `live/drone0`, reported its H265 stream online, and retained the publisher TCP connection during the observation. App diagnostics advanced from `time=5899 ms, frames=119` to `time=56666 ms, frames=1642`; the live status then remained `RTSP streaming active`, `RTSP: sending`, `Reconnects: 0`, `Errors: 0` at 4:14 elapsed. This is a short stability check and does not establish long-duration stability.
- Correct viewer URL for this transmitter is `rtsp://192.168.0.105:8555/live/drone0`. The local laptop's Garuda Control Room log also repeatedly requested `live/drone1` and `live/drone3`, which MediaMTX correctly returned as unavailable paths; those are separate paths and do not identify a failure in the `drone0` publisher. A second physical laptop was not available for this check, so its Garuda Control Room playback remains unverified.
- The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. Reuse on another router requires updating the transmitter's receiver IP to the laptop address reachable on that LAN and using the same URL/path in Garuda Control Room. Keep each transmitter on a distinct stream key/path.

## Transmitter-hosted RTSP server — 2026-10-07

- Built version 1.0.23 (version code 24); `:app:testDebugUnitTest`, `:app:lintDebug`, and `:app:assembleDebug` succeeded. Installed the debug APK on the connected Android 13 / arm64 transmitter.
- With **TRANSMITTER RTSP** selected, the app displayed `rtsp://192.168.0.104:8555/live/drone0`. While streaming was active, the embedded server listened on TCP 8555 and UDP 8000/8001. An RTSP `OPTIONS` request returned `200 OK`; before a publisher was present, `DESCRIBE` returned `404 Not Found`, as expected.
- For a server and multi-reader check, a generated H.264 test pattern was published into the transmitter through an ADB USB port forward. Two RTSP/TCP reader pipelines connected at the same time and decoded the stream for five seconds; the transmitter showed one publisher and two reader connections. This verifies the embedded server's publisher and multi-reader path, not the physical camera or a Wi-Fi reader.
- The current physical camera did not publish. Its saved source is `rtsp://192.168.144.25:8554/video1`; the transmitter's `ar_net0` neighbor table reported `192.168.144.25 INCOMPLETE`, and the app returned to `RECONNECTING CAMERA` after a camera probe timeout. The server still opened independently of that camera-link failure.
- At this check, the transmitter Wi-Fi address was `192.168.0.104/24`, while this laptop was `192.168.1.142/24`. A direct laptop connection to `192.168.0.104:8555` timed out. Put viewer laptops on a mutually reachable Wi-Fi subnet and make sure the router permits client-to-client traffic before testing Garuda Control Room playback. Internet access is not required.
- Local mode is unauthenticated: any client on a reachable LAN that knows the URL can view the stream. No public internet access or port forwarding was tested or is required.
