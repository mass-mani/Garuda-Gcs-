# Four-drone router setup and validation

## Network profile verified on 2026-10-08

The laptop Wi-Fi interface is `192.168.0.105/24` on SSID `GAPL`; the four supplied drone addresses are reachable on that subnet. The current Garuda GCS process has established RTSP-over-TCP sessions to all four video endpoints:

| Vehicle | Video URL | RTSP media | MAVLink system ID seen on TCP 5760 |
|---|---|---|---:|
| Drone 1 | `rtsp://192.168.0.101:8555/live/drone101` | H.265 | 5 |
| Drone 2 | `rtsp://192.168.0.104:8555/live/drone104` | H.265 | 2 |
| Drone 3 | `rtsp://192.168.0.108:8555/live/drone108` | H.265 | 3 |
| Drone 4 | `rtsp://192.168.0.109:8555/live/drone109` | H.265 | 4 |

The third address is `.108`. Do not configure `.107` from the earlier typo.

## Garuda GCS configuration

In the Control Room, assign one RTSP source URL to each of four tiles. Use **TCP** transport for these Wi-Fi feeds and begin with the new **250 ms** RTSP jitter-buffer setting. Increase latency if a stream still stutters; lowering the client buffer does not reduce the transmitter's encoding bitrate.

For telemetry, the four devices accepted TCP on port `5760`, and passive reads produced MAVLink 2 heartbeat frames with the distinct IDs shown above. If you use TCP, create four separately named TCP links, one per drone IP, all with remote port `5760`. If each transmitter is already sending MAVLink UDP to the laptop, use one UDP listener on local port `14550` for all four vehicles; each vehicle must retain a unique nonzero MAVLink system ID. The currently running GCS process has a UDP listener on `14550` and does not show separate TCP telemetry sessions.

Never set the telemetry destination to RTSP port `8555`. Video and MAVLink use separate services. QGC's active-vehicle selector keeps ordinary commands directed to the selected vehicle; selecting a vehicle does not make duplicate system IDs safe.

## Observed test limits

A simultaneous 8.5-second RTSP/TCP sample delivered all four H.265 streams at roughly 24–29 frame-marker events per second, with about 1.76 Mbit/s total received RTP payload. A passive 2.6-second MAVLink/TCP sample found three autopilot heartbeats from each distinct ID. These tests send no vehicle-control commands.

The running GCS snapshot showed four established RTSP/TCP connections, a UDP listener at `14550`, about 0.97 GiB working set, 1.74 GiB private memory, and 2.4% CPU across 16 logical processors over a six-second sample. The Wi-Fi link reported 100% signal and a 300 Mbit/s PHY rate, but it was using 2.4 GHz channel 3. The interface received about 3.0 Mbit/s in that sample; this does not indicate router capacity or packet loss. The short samples do not establish long-run decoder stability, packet loss, or video appearance.

For a larger fleet, put the laptop and transmitters on the same private subnet, avoid client isolation, use distinct MAVLink IDs, and prefer a clear 5 GHz channel when supported. The current channel 3 can overlap nearby 2.4 GHz networks; channel occupancy was not measured here.
