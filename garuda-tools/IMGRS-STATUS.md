# Indian Grid / IMGRS status

Garuda GCS includes an **inspection-only** Indian Grid converter in the existing Edit Position dialog. It supports full easting/northing for zones 0, IA, IB, IIA, IIB, IIIA, IIIB, IVA and IVB, with automatic zone suggestion only in limited interior areas. An explicit zone is required for inverse conversion. The conversion runs offline and never changes the underlying mission coordinate.

## Definition and limits

The implementation uses the approximate projection and datum parameters published at <https://deeppradhan.heliohost.org/gis/indian-grid/> (Indian Grid approximation v1). It treats the published latitude of origin, scale factor and false coordinates as Lambert Conformal Conic 1SP-equivalent parameters. The published Everest 1956 ellipsoid and three geocentric shifts (+295, +736, +257 m from Indian datum to WGS84) are used. These are **not authoritative Survey of India parameters**. The reference itself says exact zone parameters are unavailable and published parameters may cause errors. Its general description says two standard parallels while its table gives a latitude and scale factor, so the projection convention also needs independent validation. The area of use for zone IB is unknown. Height conversion is not provided.

The UI deliberately does not allow Indian Grid results to move a mission item, upload a mission, or command a vehicle. Grid letters are not parsed or emitted: the reference explains that letter pairs repeat far apart and cannot uniquely identify a position without more context. Full zone + full easting + full northing is required.

## Validation still required before operational use

1. Obtain authoritative zone definitions, datum transformation and area polygons, including zone IB.
2. Compare independent surveyed ground control points in every zone with the converter; record horizontal errors and an acceptable threshold.
3. Establish the primary/secondary grid-letter origin and disambiguation rules from an authoritative source.
4. Only then enable mission waypoint entry and geometrically transformed map overlays. Validate in simulation before use with an aircraft.

Display conversion is available through the position editor used by Fly View, map items and mission editing. The converter is not a substitute for WGS84 coordinates for navigation.

## Build and numerical check (9 October 2026)

The Windows RelWithDebInfo build, including Qt MOC and the Edit Position QML cache, completed. The staged portable executable passed `--simple-boot-test`. For WGS84 28.6139 N, 77.2090 E with manually selected zone IIA, the actual C++ converter returned E 3,057,029.21026268 m and N 1,207,612.99488739 m. Independent PROJ 9 / pyproj using the same published approximate definition returned E 3,057,029.2102626767 m and N 1,207,612.994887402 m. The C++ inverse returned 28.613899999886 N, 77.209000003347 E. This demonstrates arithmetic agreement for one point, **not** datum or survey accuracy. Other zones and authoritative control points remain unverified.
