# Local Windows debugging

Open the Garuda-GCS folder in VS Code. Select **Garuda GCS: local Windows debugger**
in Run and Debug, then press F5. The task builds the viewer assets and the Windows
application with native debug symbols. It does not generate an installer.

The default configuration is **RelWithDebInfo**: an optimized application with
symbols for Visual Studio's native debugger. Both F5 and direct Windows builds
refresh the UAV Log Viewer assets before compiling the application.

Requirements on this machine: Visual Studio 2022 C++ Build Tools, Microsoft C/C++
VS Code extension, Qt 6.8.3 MSVC including WebEngine/WebChannel/WebSockets, and Node.js.
The scripts use the workspace's .build-tools Qt installation.
Local builds set `GARUDA_ENABLE_DEPLOYMENT=OFF` and load Qt from that installation.
This skips the deployment scan and installer setup during debugging. Only
`Build-Windows.ps1 -Package` enables deployment and creates an installer.

Open Analyze Tools > UAV Log Viewer. Use Open Sample, or select a local .bin/.tlog
file. The viewer keeps its loaded log when switching between Analyze Tools tabs.
Exiting Analyze Tools closes the viewer. Existing vehicle tools remain available.

The viewer source is vendored in third_party/UAVLogViewer with upstream licenses
and revisions documented in GARUDA-INTEGRATION.md. User-facing branding uses
Garuda Aerospace; compatibility identifiers, protocol names, and copyright credits
are preserved. Internet access is needed for external imagery and terrain.

For a first-time web dependency install:
  powershell -ExecutionPolicy Bypass -File garuda-tools/Build-UAVLogViewer.ps1 -InstallDependencies

For compilation only:
  powershell -ExecutionPolicy Bypass -File garuda-tools/Build-Windows.ps1

To open the existing build in the full Visual Studio debugger:
  powershell -ExecutionPolicy Bypass -File garuda-tools/Start-WindowsDebug.ps1

Press F5 in Visual Studio to start the application. Add `-Build` to compile first;
the launcher uses RelWithDebInfo by default and never packages an installer. It
sets the Qt runtime paths for the new IDE process without changing system settings.

Launch the new executable from `../windows-RelWithDebInfo/RelWithDebInfo/GarudaGCS.exe`
using the configured debugger so the Qt runtime paths are set. An older installed
copy does not show source changes until it is rebuilt and replaced.

Do not run the Package Windows installer task until review is complete.

## Three video sources

Run `garuda-tools/Setup-WindowsGStreamer.ps1` once to download and verify the
GStreamer 1.22.12 MSVC runtime and SDK. They are extracted into the workspace;
no system-wide installation is made. Windows builds enable GStreamer and USB
camera support. The Visual Studio launcher supplies the runtime paths.

Application Settings > Video contains Video Source 1, 2, and 3. Each has its own
source selector and connection URL. More options exposes aspect ratio, fit, grid, latency, timeout,
recording format, and enable switch. Select RTSP Video Stream and enter a URL
for each camera. Settings are saved independently in Video, Video2, and Video3.
For UDP sources, use distinct ports (defaults: 5600, 5601, and 5602).

Source 1 retains the existing map/video swap, camera controls and pop-out.
Sources 2 and 3 appear beside it in Fly View with Record/Stop, Snapshot, Retry,
and Expand/Restore controls. Reconnecting one additional source does not
restart the other sources. Additional USB sources use Qt's camera preview.
Decoder preference and recording storage limits are shared; restart after
changing decoder preference. Source 2/3 recordings and photos include their
source number in the filename.

For an isolated debug profile, pass `-SettingsDirectory <folder>` to the Visual
Studio launcher. Normal launches continue using the regular saved settings.

## Right-side flight panels

Fly View now includes independently retractable HUD, Information & Actions,
and IMGRS Conversion panels. Click a panel header to collapse or expand it.
HUD > Pop out opens the live instrument in a separate Garuda Aerospace window.
Information includes Quick, Actions, Messages and Status tabs. IMGRS is reserved
for a later update. Upstream reference details are in MISSION-PLANNER-INTEGRATION.md.
