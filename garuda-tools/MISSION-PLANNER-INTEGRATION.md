# Mission Planner reference and Garuda flight panels

Upstream: https://github.com/ArduPilot/MissionPlanner.git
Reference checkout: `../MissionPlanner-reference`
Revision: `ae8c2909c1b5195c9665cd1b851584f03123a0c3`
The checkout preserves upstream COPYING.txt (GPL v3) and authorship.
Submodules are not initialized; this checkout is for source reference, not a built Mission Planner distribution.

Reviewed upstream controls:
- `ExtLibs/Controls/HUD.cs` (HUD by Michael Oborne, 2011): attitude horizon, pitch/bank scale, heading, speed/altitude and status layout.
- `GCSViews/FlightData.Designer.cs`: Quick telemetry grid and action tab layout.

Garuda uses native Qt/QML implementations, not an embedded Windows Forms process.
No Mission Planner C# code or binaries are linked into Garuda. The HUD and
information panel use Garuda's active Vehicle facts and existing guided action
controller. No telemetry is fabricated when disconnected. Display values use
raw SI facts (metres, metres/second, degrees).

Right side panels:
1. HUD: attitude, heading, groundspeed, relative altitude, airspeed, climb,
   battery, GPS, flight mode and arming state. Supports a separate HUD window.
2. Information & Actions: Quick, Actions, Messages, Status. Commands retain
   Garuda's capability checks and confirmation flow; flight-mode changes also
   require confirmation for the selected vehicle.
3. IMGRS Conversion: reserved placeholder; no conversion implemented yet.

Panel expansion states persist in the app profile. The original flight widgets
and camera controls remain available. Mission Planner's other tabs (servo/relay,
scripting, transponder, etc.) are not part of this initial integration.

Actions now include the four selectors (action, waypoint, flight mode, mount),
Auto, Loiter, RTL, joystick settings, arming, restart/resume mission, raw sensor
inspection, log messages, clear track, abort landing, speed, altitude and loiter
radius. The action selector includes Mission Planner's service commands.
Availability depends on vehicle state and firmware support; commands require
confirmation and the selected vehicle must remain connected. Raw Sensor View
opens Garuda's MAVLink Inspector. Resume uses Garuda's existing mission flow.
Set Home Alt toggles the HUD between relative and AMSL altitude; it does not
change the vehicle home position. Information & Actions also supports pop-out.
Real-aircraft command execution has not been validated as part of UI debugging.
