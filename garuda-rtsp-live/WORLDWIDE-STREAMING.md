# Garuda Stream → Garuda Live Viewer

## Network path

```text
Drone camera RTSP
  → Garuda Stream on the UniRC 7 Pro
  → outbound RTMP/RTMPS over transmitter 4G
  → streaming provider ingest and playback service
  → provider HTTPS HLS URL or hosted player
  → Garuda Live Viewer on a Windows PC anywhere with internet
```

The PC does not relay video. The transmitter starts an outbound publish connection, so a public SIM address or inbound port forwarding is not needed. Each transmitter must use its own provider stream and key. The GCS can continue using its separate telemetry connection.

## Configure the transmitter

1. In Garuda Stream, enter the camera's RTSP URL and use **TEST RTSP** to confirm video first.
2. Choose **REMOTE SERVER**, then **LIVEPUSH / CUSTOM**. Copy the RTMP or RTMPS ingest server URL and stream key from your Livepush stream dashboard. Enter both values as Livepush gives them; this app intentionally does not contain a shared key or fabricated Livepush endpoint.
3. Optionally paste the provider's **HTTPS embed player URL** or the provisioned **direct HLS `.m3u8` URL** in **Optional viewer / embed URL**. It is saved with the encrypted transmitter configuration and is separate from the publish key.
4. Keep the camera output at H.264 for efficient RTMP packet copy when available. If the camera only provides H.265, use the app's explicitly confirmed hardware transcode mode and verify it on this transmitter.
5. Press **START STREAM** while the app is visible. The foreground service keeps the publish session active when Garuda Stream is minimized. Check its status for outbound video bytes and reconnection state.
6. On the PC, configure Garuda Live Viewer with the same provider playback URL:
   - For a direct `.m3u8` URL, leave **Hosted player page (open in browser)** off.
   - For a provider embed/player webpage, turn **Hosted player page (open in browser)** on. It opens in the Windows default browser.
   - Never enter the RTMP publish URL or stream key into the viewer.

The viewer configuration is stored locally and protected with Windows DPAPI. A multi-cell layout can show independent streams; give every transmitter a distinct provider stream and viewer URL.

## Livepush link availability

Livepush provides an embed player URL for its stream types. Its help center says direct HLS links are plan dependent and provisioned by Livepush; they are not a URL that this app can derive from the RTMP ingest address. If your account does not provide direct HLS, use its embed player link in the Windows app's **Hosted player page (open in browser)** mode. See [Livepush embed player instructions](https://docs.livepush.io/en/articles/9435751-accessing-embed-player-url-and-embed-iframe-code-for-website-streaming) and [direct HLS availability](https://docs.livepush.io/en/articles/9989918-how-to-access-your-direct-hls-m3u8-urls).

The app does not call the Livepush API and does not infer that publishing succeeded from a URL alone. A publish is only confirmed when Garuda Stream reports outgoing video progress; the provider dashboard/player is still needed to verify that the provider accepted it and viewers can play it.

## Current hardware test status

The UniRC 7 Pro is detected as Android 13 / arm64 and Garuda Stream runs on it. Current ADB diagnostics show mobile data enabled in settings but the modem out of service, no active default network, and no IPv4 route. Therefore a camera-to-cloud-to-PC test cannot currently be completed. No real Livepush credentials have been supplied in this workspace, so no provider publish or remote playback is claimed as passed. Restore an active SIM data connection, configure the account-provided ingest details, and then verify the actual camera stream and PC playback.

Cloud video has delivery latency and is for monitoring. Do not use it for direct flight control or obstacle avoidance.
