# Garuda operational multi-vehicle upgrade

## Phase 1 — repository inspection, 2026-10-08

Status: architecture inspection recorded; Windows baseline build passed. Phase 1 implementation plan is delivered. Automated runtime regression coverage remains unverified because this build disables QGC tests. No swarm flight controls are implemented by this document. Subsequent phases must pass their own build and validation gates before being marked complete.

Baseline: `git describe` reports `v5.0.8-garuda.1`. This is the local repository tag, not an independent upstream release verification. Windows uses Qt 6.8.3 MSVC x64, C++20, CMake/Ninja, QML, and GStreamer. The existing working tree contains extensive Garuda modifications; preserve them. No AGENTS.md was found in the searched workspace sources.

## Existing capabilities and extension points

| Area | Actual source | Reuse and gaps |
| --- | --- | --- |
| Navigation | src/UI/MainWindow.qml | Existing Fly/Plan and tool drawer navigation; add Swarm, Control Room, Simulation, Vehicles using existing QML modules. |
| Vehicle identity and selection | src/Vehicle/MultiVehicleManager.{h,cc} | Already owns vehicles, activeVehicle and selectedVehicles. Heartbeat discovery accepts autopilot component 1 and looks up by System ID. Duplicate IDs cannot simply be treated as separate vehicles. |
| Links | src/Comms/LinkManager.h; SerialLink, UDPLink, TCPLink; src/Vehicle/VehicleLinkManager.h | Reuse connection editing, saved configurations, simultaneous links, primary-link selection and loss detection. Disconnecting a shared link affects all vehicles on that link; expose that explicitly. |
| MAVLink | src/Comms/MAVLinkProtocol.{h,cc} | Existing parser/channel handling and MAVLink 1/2 negotiation. Do not add another parser or transport stack. |
| Telemetry | src/Vehicle/Vehicle.h and vehicle FactGroups | Each Vehicle owns its state. UI adapters must retain explicit vehicle references, never copy all feeds through activeVehicle. Represent missing/stale facts as unavailable. |
| Commands | Vehicle::sendMavCommandWithHandler, mavCommandResult, guidedMode methods; firmware plugins | Existing per-vehicle ACK handlers and firmware-specific guided actions. A received ACK is distinct from observed completion of an operation. Mode changes may need observed-state confirmation. |
| Map | src/FlightDisplay/FlyViewMap.qml | Already renders all vehicles and per-vehicle PlanMapItems. Trajectory currently follows activeVehicle. Add independent optional trails, selection styling and target/status annotations. |
| Missions | src/MissionManager/PlanMasterController, MissionManager, PlanManager | Existing per-vehicle managers, asynchronous transfers, progress and static vehicle binding. Copy mission items for each destination; do not retarget an active transfer by changing activeVehicle. |
| Video | src/VideoManager/VideoManager and AdditionalVideoSource | Existing independent additional receivers, lifecycle and settings; only sources 2 and 3 are currently instantiated. Generalize a bounded pool for a 16-panel wall. |
| Existing Control Room | garuda-control-room | Separate WPF/LibVLC application, not a Qt page. Reuse its workflows; integrate video with QGC's GStreamer receivers rather than embedding the Windows UI. |
| Simulation | src/Comms/MockLink | MockLink is for tests and is not ArduPilot/PX4 SITL. No process-launching SITL manager was found in the inspected Comms/Vehicle/tool sources. |
| Logging | MAVLinkProtocol::_logData; Vehicle MAVLinkLogManager | Existing raw telemetry and flight-log facilities do not establish independent per-vehicle operational event logs. Add identity-keyed event streams and rotation. |
| Garuda customization | GarudaActionsController, GarudaFlightActions, GarudaRightPanels, AdditionalVideoPane, UAVLogViewerPage | Preserve existing actions, branding, telemetry panels, extra video, log viewer and packaging. |
| Tests | test/Vehicle, test/MissionManager, test/Comms | Existing command ACK, initial connection, link, mission and plan tests. Current Windows cache has QGC_BUILD_TESTING=OFF; a normal release build does not run them. |

## Architecture decisions

Use MultiVehicleManager and Vehicle as the single owners of MAVLink vehicle state. Add small Garuda adapters only for capabilities absent from QGC: persistent aliases/groups, explicit selection snapshots, command batches and safety evaluations, mission batches, video associations, simulator process ownership and operational event logging. Keep interfaces separated from QML rendering.

Identity must distinguish System ID, autopilot Component ID, link provenance and session lifetime. Multiple links can legitimately belong to the same aircraft; a second link is not proof of an ID collision. Use available autopilot UID and peer provenance to detect contradictions. UDP peer provenance must be inspected through reception before promising detection on a shared socket. Require unique System IDs on a shared MAVLink network; block ambiguous command targets rather than silently merging conflicting aircraft or broadcasting. A simulator label requires provenance from an owned simulator endpoint, not just a guessed System ID or firmware name.

Group command preparation snapshots exact live vehicle handles and displays names, IDs, real/simulation labels and prerequisite failures. Confirmation executes that snapshot only after rechecking health and identity. Selection changes cannot change recipients after confirmation. No MAVLink broadcast destination. ACK tracking uses vehicle, component and command; serialize potentially conflicting command IDs on each vehicle. Report accepted, rejected, in progress, timeout, disconnected and completed separately. Never automatically replay a flight command after reconnection.

Safety evaluations are command-specific: GPS/home rules suitable for takeoff or RTL must not prevent an emergency land request without an explicit reason. Do not force-arm or force-disarm. Unknown/stale safety data is visible and blocks actions requiring it. Respect firmware/type capabilities, ongoing missions/transfers and operator cancellation. Mixed real/simulation groups require clearly identified recipients; simulation stop terminates only owned processes.

SITL uses real ArduPilot/PX4 executables through asynchronous QProcess adapters (WSL on Windows where required), unique IDs and reserved endpoints. Store process ownership, bounded output, exit state and cleanup. PX4 support remains conditional on an available working runtime. Install/runtime discovery and platform command lines must be verified against official project documentation during implementation.

Video uses a bounded receiver pool with per-stream reconnect backoff, explicit disconnect and teardown, persisted names/URLs/vehicle associations and optional overlays. Verify backend audio support before exposing mute. A missing aircraft never redirects an overlay to the currently active aircraft. Fullscreen reuses a receiver. Video failure cannot block MAVLink processing.

## Phases and completion gates

1. Inspection and this plan: build the existing Windows application, record the result and available test coverage. Do not infer flight readiness from compilation.
2. Vehicle/connection management: aliases and link actions backed by existing managers; implement identity ambiguity handling first. Gate: two unique vehicles, shared/mixed links and duplicate-ID rejection tests.
3. Selector and telemetry: bind panels to activeVehicle and dashboard rows to their own Vehicle; all requested telemetry fields need availability/staleness handling. Gate: switching never cross-writes state.
4. Map: preserve existing all-vehicle markers/missions; add independent trails, selection and status. Gate: updates, loss and recovery for multiple positions.
5. Swarm dashboard/groups: persisted named groups, explicit enable/disable, bounded rows and health indicators. Gate: disabled mode preserves existing operation, stale selections cannot target replacement vehicles.
6. Commands/safety: preparation, confirmation, exact recipients, firmware support checks and independent results. Gate: ACK denial, timeout, in-progress, reordered replies and disconnection; test on simulation only.
7. Missions: cloned per-vehicle payloads, bounded group uploads and independent progress/cancellation. Gate: different missions, shared mission and partial failure remain isolated.
8. Integrated Control Room: 1–4 rows/columns, independent RTSP configurations and receiver lifecycle. Gate: 1x1, 2x2, 3x3 and 4x4 lifecycle/resource checks.
9. Video associations/overlays: persistent vehicle identity and independent facts. Gate: selection changes and disconnects never substitute another vehicle's telemetry.
10. SITL: real ArduPilot launcher, vehicle/frame selection, position/spacing, ports, IDs and lifecycle; PX4 adapter where runtime permits. Gate: 5 and 10 real SITL instances through the same Vehicle backend, process exit/stop and port conflicts.
11. Event logs/recovery: independent connection, command/ACK, mode, arm, GPS/EKF, battery/failsafe and mission events; bounded storage. Gate: isolation, redaction and application restart behavior.
12. Integration: execute the matrix below, record build identifiers, environment and evidence. Mark unavailable hardware scenarios unverified, never passed.

After each phase: compile, fix errors, run relevant QGC/new tests, check existing single-vehicle behavior, document files changed and evidence before continuing. Keep changes documented rather than committing the user's unrelated dirty working tree.

## Acceptance matrix

All scenarios below are pending unless a dated validation entry explicitly records otherwise.

| Scenario | Required evidence |
| --- | --- |
| One physical drone | Discovery and read-only telemetry on authorized hardware; flight commands require an appropriate safe setup. |
| Two simultaneous drones | Different IDs, independent state and exact command destinations. |
| Five / ten SITL drones | Actual simulator processes, distinct IDs/positions, independent telemetry and bounded resource usage. |
| Serial + UDP / TCP + UDP | Concurrent links, shared-link disconnect warning and unaffected surviving vehicles. |
| Vehicle switching / map updates | Active panel changes, per-vehicle rows/markers remain independent. |
| Drone loss/reconnection | Only affected aircraft marked lost; identity revalidation; no automatic flight-command replay. |
| Individual/group commands and ACKs | Exact confirmed recipients, per-recipient ACK/state outcomes, partial failures and late replies. |
| Individual/group mission upload | Independent mission contents, transfer status, progress and failed recipients. |
| Low battery / GPS failure / EKF warning | Fault injection in tests/SITL, correct affected row and command-specific rejection. |
| RTSP 1x1 / 2x2 / 3x3 / 4x4 | Decoded moving video, no false LIVE state, measured CPU/memory and stable layout switching. |
| Stream loss/reconnection | Independent retry, other videos and telemetry responsive. |
| SITL start/stop | Owned processes only, ports released, failures surfaced and no orphan processes. |
| Restart/recovery | Settings/groups restored, missing peers shown clearly and no stale commands replayed. |

## Baseline evidence

Build command: `garuda-tools/Build-Windows.ps1 -Parallel 2`.
Build log: `../swarm-phase1-build.log`.
Build outcome: succeeded, including UAV Log Viewer assets, CMake configuration and linking `windows-RelWithDebInfo/RelWithDebInfo/GarudaGCS.exe`.

CTest discovery using the workspace CTest executable returned `Total Tests: 0`, consistent with `QGC_BUILD_TESTING=OFF`. No runtime, hardware, flight-command or SITL validation is claimed. `git diff --check` found no whitespace errors in tracked changes (line-ending warnings only). This phase adds documentation only; the build compiles the pre-existing working tree, including existing GPS edits. Next: establish a separate test-enabled build and implement Phase 2 identity/connection handling before adding swarm commands.

## Phase 2 progress — 2026-10-08

Status: partial; not flight-ready and not accepted as complete. Added a `GarudaVehicleDirectory` QML adapter and Vehicles page. It reads the live vehicle collection owned by MultiVehicleManager, lets the operator select an active vehicle, shows SYSID/component/type/mode/link/connection/armed state, and persists a preferred label under hardware UID when available (otherwise under SYSID). Duplicate nonzero UIDs across distinct connected SYSIDs are now shown as an identity conflict and block active-vehicle selection in this page. The page links to existing Comm Links settings and warns that shared-link disconnects affect all vehicles. Files: `src/Vehicle/GarudaVehicleDirectory.{h,cc}`, `src/Vehicle/CMakeLists.txt`, `src/QmlControls/QGroundControlQmlGlobal.cc`, `src/UI/GarudaVehicles.qml`, `src/UI/CMakeLists.txt`, `src/UI/MainWindow.qml`, `garuda-tools/Build-Windows.ps1`.

Validation: the test-enabled Debug executable linked successfully at `../windows-Debug/Debug/GarudaGCS.exe` (114,588,160 bytes at the initial link). QML type registration and `GarudaVehicles.qml` QML cache generation completed during successful incremental builds. `--simple-boot-test` exited 0. CTest definitions are under `windows-Debug/test`, and the full serial suite ran 42 tests: 38 passed, 4 failed. Failures reproduced on a serial rerun: `SigningTest`, `StructureScanComplexItemTest` (0xc0000409), `ComponentInformationCacheTest`, and `VehicleLinkManagerTest` (access violation in MockLink's MAVLink parameter response path). A new `GarudaVehicleDirectoryTest` now covers default/stored naming, invalid and trimmed aliases, connection/component display, and selection; it passed on the test-enabled build. Existing CTest parallel execution is unsafe with QGC's single-instance guard; use `-j 1`. Some GPS test build entries are not registered in `UnitTestList.cc`, so this suite's counts are not full source coverage. The four pre-existing suite failures are not attributed to the new adapter without further isolation.

Phase 2 remains open: the UID conflict check has not yet received dedicated automated coverage. Same-SYSID peer collisions are not detected here because MultiVehicleManager already maps vehicles by SYSID. There is no new unit coverage for two vehicles, mixed/shared links, link loss, or reconnect. No swarm commands, identity-sensitive group targeting, SITL, or Control Room integration are implemented by this phase. Continue with robust peer-provenance ambiguity handling and regression coverage before marking this phase complete.

## Phase 3 progress — 2026-10-08

Added per-vehicle telemetry to the Vehicles page for relative altitude, ground speed, heading, GPS fix/count, and coordinate. Every field binds through the card's own `Vehicle` object; connection loss is shown on that same card. The telemetry page QML cache compiled; the directory unit test passed again and `--simple-boot-test` exited 0. Multi-vehicle switching and stale-field behavior on physical aircraft remain unverified. Phase 3 remains open until a two-vehicle test demonstrates no cross-binding and missing/stale values render safely.

## Phase 4 progress — 2026-10-08

The flight map now tracks each connected vehicle's trajectory independently instead of using only the active vehicle's path. Vehicle labels use the saved Garuda alias on multi-vehicle maps, and trails use deterministic per-vehicle colors with a stronger active-vehicle style. The changed QML modules compiled in the Debug build. A multi-vehicle map update/loss/recovery run is still needed; Phase 4 remains partial.

## Phase 5 progress — 2026-10-08

Added persistent named groups and explicit activate/disable behavior. Activation resolves each saved identity to exactly one currently connected vehicle, rejects duplicate hardware-UID conflicts and stale session-only members, then sets QGC's exact selected-vehicle collection. It does not send MAVLink commands. A connection add/remove or active-group membership edit clears selection and disables the group. Group names are unique without regard to case. The Swarm page now has per-vehicle cards for connection, SYSID, mode, arm state, GPS availability, relative altitude, ground speed and battery availability, plus group editing.

Validation: test-enabled Windows Debug build passed; `GarudaVehicleDirectoryTest` passed with MockLink, covering saved aliases, selection, group creation, case-insensitive duplicate rejection, exact selection, and invalidating selection after active-membership edit. `--simple-boot-test` exited 0. Phase 5 is partial: there is no multi-vehicle/reconnect/restart test yet, no command panel or command-safety backend, and physical vehicles are not tested. Do not use group selection as flight-command readiness.

## Current build evidence — 2026-10-08

The latest test-enabled Debug build at `../windows-Debug/Debug/GarudaGCS.exe` includes the Phase 5 dashboard and selection-safety updates. Build command: `garuda-tools/Build-Windows.ps1 -Configuration Debug -EnableTests -SkipViewerBuild -Parallel 3`. Targeted CTest and simple boot both pass. The earlier full serial CTest run had 38/42 pass and four reproduced failures documented under Phase 2; they remain unresolved and are not claimed as passing.

## Phase 8 progress — 2026-10-08

Added an in-app Control Room route and a bounded pool of 16 independent QGC `AdditionalVideoSource` receivers (camera indices 1–16, internally VideoSettings groups 2–17). Layout choices are 1×1, 1×2, 2×2, 2×3, 3×3 and 4×4. Each visible cell has Setup controls for source, RTSP URL and stream enable; each receiver continues to use the existing GStreamer worker and status/retry lifecycle. The two regular Fly-view extra panes remain available outside Control Room. QML cache generation and the test-enabled Debug build succeeded; the Garuda focused test and simple boot passed after integration.

Phase 8 remains partial: no real RTSP input was available for decode/loss/reconnect testing, camera names and mute are not implemented, and CPU/memory scaling has not been measured. The grid is a presentation pool, not proof that 16 streams can be decoded smoothly on a target laptop.

## Optimized Windows artifact — 2026-10-08

Built `../windows-RelWithDebInfo/RelWithDebInfo/GarudaGCS.exe` with Qt 6.8.3/MSVC x64 and GStreamer enabled. `windeployqt` was used to assemble a portable runtime folder at `artifacts/Garuda-GCS-Windows-x64-Portable`; it contains Qt, QML, GStreamer runtime/plugin DLLs, a launcher and GStreamer license files. Launch with `Run-GarudaGCS.cmd`. The portable executable passed `--simple-boot-test` with its bundled Qt/GStreamer paths. There is no installer yet: the CMake deployment/package configuration hit a prolonged Qt `qmlimportscanner` run and failed to produce its import manifest; the normal RelWithDebInfo build succeeded after disabling CMake deployment and the runtime folder was deployed separately.

Full serial CTest after the Garuda feature changes: 40/43 passed. Reproduced failures: `SigningTest`, `StructureScanComplexItemTest` (exit `0xc0000409`), and `ComponentInformationCacheTest`; `VehicleLinkManagerTest` passed in this run. The targeted Garuda test passed. No physical vehicle, multi-vehicle, SITL or RTSP decode test is claimed.

## Vehicle capacity and operator labels — 2026-10-08

Raised the MAVLink link-channel pool to 32 channels total (channel 0 reserved), allowing up to 31 independently allocated active links. The application does not impose a three-vehicle cap: multiple aircraft can share one UDP listening link when each transmits a distinct MAVLink system ID. Per-channel allocation is a separate-link ceiling, not a fleet ceiling; MAVLink's nonzero system IDs are the addressing constraint for unique vehicles.

Vehicle aliases are editable from both the Vehicles page and the active-vehicle toolbar selector. The home/Fly toolbar now has a Control Room button for direct navigation.

Validation: optimized RelWithDebInfo Windows build completed, the portable bundle executable was updated, and portable `--simple-boot-test` exited 0. Debug `GarudaVehicleDirectoryTest` passed. More than ten live aircraft were not available for a physical test, so real radio/router throughput and unique SYSID configuration remain to be verified with the fleet.

## Four-drone connectivity audit and stabilization — 2026-10-08

### Reproducible baseline on the connected router

The laptop is `192.168.0.105/24`. All supplied devices answered on `192.168.0.101`, `.104`, `.108`, and `.109`. Each had TCP `8555` (RTSP) and `5760` open. RTSP DESCRIBE returned H.265 SDP from all four endpoints. Passive reads on port `5760` yielded MAVLink 2 heartbeat frames with distinct autopilot system IDs: Drone 1=5, Drone 2=2, Drone 3=3, Drone 4=4. No command packets were sent during this check.

All four RTSP feeds were played at once over interleaved RTSP/TCP for an 8.5-second sample. Each carried RTP packets and roughly 24–29 frame-marker events/s; measured aggregate payload throughput was about 1.76 Mbit/s. This short network-path check did not reproduce the reported freeze and does not measure GCS decoder/rendering load, sustained Wi-Fi loss, or 60-minute stability.

### Source findings and likely failure contributors

`GstVideoReceiver::_makeSource` forces RTSP jitter latency to 25 ms for every camera and does not expose RTSP lower transport. That is a fragile buffer for four Wi-Fi H.265 streams when packet arrival jitter exceeds 25 ms. `AdditionalVideoSource` owns independent receivers and worker threads, but every source retries on the same fixed 1.5-second timer; a shared outage can synchronize four reconnect cycles. These are concrete resilience weaknesses. The router/network and duplicate system IDs were not the cause in this sample: all four feeds transferred simultaneously and the observed system IDs were distinct.

QGC already supports independent TCP, UDP and serial link configurations; the four tested telemetry endpoints are TCP `5760`. Adding four separately named TCP links preserves per-vehicle link ownership and QGC command routing. There is no evidence here to justify a new socket stack or changing aircraft parameters.

### Fixes in progress

Add per-source RTSP transport and jitter-latency settings (TCP and a bounded 250 ms default for the four tested Wi-Fi cameras), enable drop-on-latency to keep buffers bounded, and replace fixed reconnect timing with bounded exponential backoff plus jitter. Keep the standard primary-camera path configurable through the same settings. Validate the Windows build and targeted tests, then repeat simultaneous RTSP/MAVLink checks. Hardware decode utilization, CPU/GPU/RAM and extended stability remain unmeasured until the GCS renderer is exercised with the actual devices.

### Live four-drone router evidence — 2026-10-08

The currently running portable GCS process (PID 13912) owns established RTSP/TCP sessions to `.101:8555`, `.104:8555`, `.108:8555`, and `.109:8555`; it also owns a UDP listener on `14550`. A six-second Windows process sample measured 2.4% CPU across 16 logical processors, 0.97 GiB working set, and 1.74 GiB private bytes. System RAM was 15.6 GiB total and 4.9 GiB free. The laptop is on GAPL 2.4 GHz channel 3, 300 Mbit/s reported link rate, 100% signal. Interface receive rate was approximately 3.0 Mbit/s for that sample. No evidence of Wi-Fi bandwidth saturation was measured.

A passive sample from the four separate TCP `5760` endpoints parsed MAVLink 2 heartbeat frames from distinct system IDs 5, 2, 3, and 4. A simultaneous 8.5-second RTSP/TCP client sample received all four H.265 streams at approximately 24–29 marker events per second and an aggregate ~1.76 Mbit/s RTP payload. The GCS process itself had the four RTSP sessions open during the observation. This confirms network-level multi-stream connectivity, not decoded picture quality or 60-minute stability.

Implemented per-source RTSP transport and 25–2000 ms latency settings; default TCP/250 ms; GStreamer drops frames beyond the configured latency cap. Additional video sources now reconnect with 1–30 second exponential backoff plus per-source jitter, resetting after successful decoding. Settings are visible in each source's Control Room setup and the Video settings page.

The four supplied TCP telemetry endpoints are active on port `5760`, but the running GCS process currently shows UDP `14550` rather than TCP `5760` telemetry connections. QGC's existing link manager can keep four independently named TCP links, or one UDP `14550` listener can carry all four unique system IDs. Current network traffic and stream tests do not demonstrate the reported instability, and no flight-control commands were sent.
