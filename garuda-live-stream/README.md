# Garuda Live Stream

Garuda Live Stream is a standalone Kotlin Android app for taking an RTSP camera feed received by a G12 transmitter and sharing a temporary browser viewing link. Open this directory (`Garuda-GCS/garuda-live-stream`) in Android Studio.

## Architecture

```text
Drone camera → G12 receiver → local RTSP
                               │
                               ▼
                    Garuda Live Stream app
                    Media3 / Android decoder
                    GPU texture → WebRTC encoder
                               │
                 SDP + ICE only over WSS
                               │
              ┌────────────────┴────────────────┐
              ▼                                 ▼
       browser viewer  ◀──── DTLS-SRTP ────▶ G12
       HTTPS page          direct P2P media
```

The viewer link contains a cryptographically random session ID, never the RTSP URL, RTSP credentials, or G12 address. A small Internet reachable server is necessary to publish that link, authenticate the app, check an optional PIN, and exchange WebRTC offer/answer and ICE messages. It does not receive the video. WebRTC tries STUN-assisted direct connectivity first. If the two networks cannot form a direct connection, the optional coturn service relays encrypted WebRTC packets; TURN does not decrypt DTLS-SRTP media.

An address such as `192.168.144.25` is only routable on a local network. A PC on another ISP cannot open that address through the Internet, and copying it into a public URL does not make it public. The app therefore contacts the public signaling service to introduce the G12 publisher and the browser; the peers then try to connect directly using ICE.

Each viewer has a separate peer connection to the G12. This avoids a video relay by default and works best for a small audience. The signaling server defaults to three simultaneous viewers; change `MAX_VIEWERS` in its environment if the transmitter upload and encoder can handle more. A TURN relay uses server bandwidth while that fallback is active.

### RTSP and video processing

The app accepts RTSP URLs and separate optional credentials. It saves the last successful URL in app preferences and encrypts RTSP credentials and the publisher token using an Android Keystore AES-GCM key. It uses Media3 RTSP playback, TCP-interleaved RTP, and a Wi-Fi/Ethernet-bound socket factory for local RTSP. That keeps the RTSP connection on the local receiver network while WebRTC uses Android's Internet route. It retries RTSP after stream errors and route changes.

The current Android bridge renders through Android's hardware decoder into a GPU texture, then WebRTC uses the device's hardware H.264 encoder when available. It does not convert frames through CPU RGB memory. Media3's RTSP playback API used here does not expose the source's encoded RTP/NAL units to the WebRTC sender, so this build does **not** provide H.264 packet forwarding/remuxing. An encoded packet path would require a native GStreamer `webrtcbin` or equivalent JNI pipeline and device validation. Hardware codec availability and power draw vary by G12 firmware; verify heat, battery use, output resolution, and latency on the transmitter before operational use. The app sends video only; audio is disabled.

## No video storage

The app does not record or write stream media to disk. Media3 and WebRTC use temporary in-memory buffers. The signaling process keeps session IDs, PIN hashes, active peer metadata, and sockets in memory only. It has no media ingest endpoint, media file, database, or recording path. `STOP LIVE` closes peer connections and deletes the session so its old link cannot establish another connection. A server restart also ends all active sessions. Android app backup is disabled.

## Signaling server deployment

Use a small Linux host with a public IPv4 address and a DNS name. No streaming subscription is required. Hosting, domain registration, bandwidth, and TURN relay traffic may still cost money. Free hosting/STUN/TURN tiers can change limits and cannot be guaranteed free for life.

1. Point a DNS A record (and optional AAAA record) for your chosen host name to the server. Allow inbound TCP 80 and 443 for Caddy and outbound Internet access.
2. Install Docker Engine and the Compose plugin.
3. In this `signaling` directory, copy `.env.example` to `.env`. Set `PUBLIC_DOMAIN` and `PUBLIC_BASE_URL` to the same public host, for example `live.example.com` and `https://live.example.com`.
4. Generate a publisher secret with `openssl rand -hex 32`; place it in `PUBLISHER_TOKEN`. Keep `.env` private. Do not commit or bake this token into the APK. Enter it in the app, where it is encrypted locally.
5. Run `docker compose up -d --build`. Caddy obtains and renews the HTTPS certificate. The same HTTPS origin carries the viewer page and WSS signaling.
6. Confirm `https://live.example.com/healthz` returns `{"ok":true}`. Open `https://live.example.com/` to check that the page is served.

Session IDs expire after six hours by default (`SESSION_TTL_HOURS`, maximum twelve hours) and session state is memory-only. `MAX_VIEWERS` defaults to three, maximum sixteen. The API has per-IP session creation throttling; PIN attempts are limited to eight per viewer IP and session per ten minutes.

### Optional TURN fallback

Try the default STUN-only setup first. If WebRTC fails on restrictive/symmetric NATs, set `TURN_SECRET` to a separate long random value, `TURN_REALM` to the TURN hostname, and `TURN_URLS` to the publicly reachable coturn address, for example `turn:turn.example.com:3478?transport=udp,turn:turn.example.com:3478?transport=tcp`. The same `TURN_SECRET` is used by the app's signaling server and the coturn container. Open UDP/TCP 3478 and UDP 49160–49200 on the host/firewall, then run:

```sh
docker compose --profile turn up -d --build
```

The server issues one-hour coturn REST credentials to each client. TURN is not needed for networks where direct P2P succeeds. The configured TURN host must be reachable from both endpoints; an incorrect public IP/NAT mapping or blocked relay range will prevent fallback.

## Build and install

Requirements: Android Studio with Android SDK Platform 35, JDK 17, and an Internet connection for the first dependency download.

```powershell
cd Garuda-GCS\garuda-live-stream
$env:ANDROID_HOME = "C:\path\to\Android\Sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
.\gradlew.bat assembleDebug
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. Copy it to the G12, allow installation from the file source if Android asks, then install it. With USB debugging enabled, `adb install -r app/build/outputs/apk/debug/app-debug.apk` is also available.

### Signed release APK

Create and protect your own signing key; do not use a test key for a public release:

```powershell
keytool -genkeypair -v -keystore garuda-live-release.jks -alias garuda-live `
  -keyalg RSA -keysize 3072 -validity 10000
$env:ANDROID_HOME = "C:\path\to\Android\Sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
.\gradlew.bat assembleRelease `
  -PGARUDA_KEYSTORE="C:\secure\garuda-live-release.jks" `
  -PGARUDA_STORE_PASSWORD="<keystore-password>" `
  -PGARUDA_KEY_ALIAS="garuda-live" `
  -PGARUDA_KEY_PASSWORD="<key-password>"
```

Release output is `app/build/outputs/apk/release/app-release.apk`. Keep the keystore and passwords outside source control. The `-P` form can be replaced with protected Gradle user properties or CI secrets to avoid leaving secrets in shell history.

The bandwidth figures in the app are estimates from Android's per-app network byte counters, so they include small signaling/ICE overhead as well as stream traffic.

## Use the app

1. Connect the G12 to the receiver's local Wi-Fi/Ethernet and make sure mobile data or another Internet-capable route is also available. Android must keep both routes active for the local RTSP feed plus Internet uplink.
2. Enter the receiver's RTSP URL, such as `rtsp://192.168.144.25:8554/main.264`. Add username/password in their fields if the stream requires authentication.
3. Tap **TEST RTSP**. The app saves the clean RTSP URL after playback connects and shows a local preview. Credentials are kept separately in encrypted app storage.
4. Enter the HTTPS signaling origin and publisher token generated during server setup. Optionally enable PIN access and choose a 4–12 character PIN.
5. Tap **START INTERNET LIVE**. The app creates a temporary session, starts the foreground notification, and displays the viewer URL and count. Copy or share the link with viewers.
6. On a PC or phone using a different network, open the link in current Chrome, Edge, or a WebRTC capable Safari. Enter the PIN if prompted. Allow a few seconds for ICE connectivity and the first video frame.
7. Tap **STOP LIVE** when finished. The session is closed and the link is revoked.

The web page uses `RTCPeerConnection` and the browser's built-in WebRTC implementation. It does not require a client installation. iOS Safari support depends on its WebRTC and negotiated codec support; current Chrome/Edge desktop and Android Chrome are the primary targets.

## Test procedure

1. On the G12, test RTSP and confirm the status becomes **CONNECTED** with moving preview video.
2. Confirm **INTERNET CONNECTED**, configure the HTTPS server and token, then start live. Confirm **LIVE**, an HTTPS viewer link, elapsed time, viewer count, and changing statistics.
3. Connect a PC to a different Internet connection from the G12 (for example, G12 on 4G/5G and PC on another ISP's Wi-Fi). Open the link in Chrome or Edge and confirm video, resolution, WebRTC state, RTT, and packet loss appear.
4. Test the optional PIN in a private window with a wrong PIN and then the correct PIN. The wrong PIN should be rejected; the correct one should show video.
5. Press **STOP LIVE**. Verify the browser disconnects and a reload of the old link reports that the session ended.
6. With TURN disabled, test direct connectivity. If it fails only on a restrictive network, enable coturn and repeat to verify relay fallback.

## Troubleshooting

- **RTSP stays disconnected:** check that the transmitter can reach the receiver, the path/case is correct, authentication works, and the camera accepts TCP-interleaved RTP. The app keeps credentials out of Android logs.
- **RTSP preview works but Internet Live does not start:** confirm the server is reachable over HTTPS and the publisher token matches `.env`. Verify `healthz`, host DNS, and ports 80/443.
- **Viewer stays waiting:** confirm the app shows signaling connected/LIVE, then inspect browser WebRTC state and server WSS reverse-proxy configuration. A private RTSP address cannot be reached by an Internet viewer; it is only used by the G12 app.
- **ICE fails:** try another Internet network, allow WebRTC UDP, and configure coturn if necessary. Direct P2P is not possible on every carrier/NAT/firewall combination.
- **High transmitter load/temperature:** reduce RTSP source resolution/bitrate/frame rate, lower simultaneous viewers, and test on the actual G12 hardware. This implementation decodes and re-encodes via hardware codec paths; it does not remux encoded H.264 packets.
- **No active viewers:** the app continues publishing while live. Stop the session when finished; sessions also expire after the configured lifetime.

## Main project pieces

- `app/`: standalone Kotlin Android Studio application.
- `signaling/server.js`: in-memory authenticated HTTPS/WSS rendezvous server.
- `signaling/public/`: responsive no-install WebRTC viewer.
- `signaling/compose.yaml`, `Caddyfile`: self-hosted HTTPS and optional coturn deployment.
