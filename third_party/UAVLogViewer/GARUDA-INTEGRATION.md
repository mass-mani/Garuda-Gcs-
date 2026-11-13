# Garuda Aerospace UAV Log Viewer

Vendored from https://github.com/ArduPilot/UAVLogViewer at
01f9d510b7df31687819a6c87415161041d73ae6.
JsDataflashParser revision: d8967f6e4b4415492ea7d9b1adb1a6dc026f2b75.

Upstream GPL-3.0 license and author attribution are retained. The desktop integration
adds Garuda branding, a matching color theme, and a debug bundling configuration.
Upstream Google Analytics tags are removed from this locally hosted edition.
Logs are parsed locally. Map imagery, terrain, and optional online tools require
internet access. The local HTTP service serves assets and terrain only; it does
not implement the upstream cloud-upload service.

The standalone Dataflash worker listener is removed from the imported parser:
the host parser.worker.js dispatches all formats. The upstream listener otherwise
parsed MAVLink files as Dataflash and dereferenced an unset parser on loadType.
