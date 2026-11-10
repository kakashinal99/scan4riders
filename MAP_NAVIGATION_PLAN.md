# 4Riders Custom Map & Navigation

## Product scope
Build a first-party, rider-focused map for Klang Valley only. The visual direction follows the approved 4Riders reference: dark satellite-inspired canvas, neon road hierarchy, purple zone boundaries, hotspot glows, on-map place labels, and compact mobile controls.

## Existing app
The current Android app is native Kotlin. Keep the existing application and integrate the map as a native Android screen; do not migrate to MagicPath or replace the app stack.

## Initial architecture
- **Android map renderer:** MapLibre Native Android.
- **Base map data:** OpenStreetMap extract clipped to a defined Klang Valley coverage polygon.
- **Vector map packaging:** generate vector tiles and package regional/offline coverage as PMTiles or an equivalent tile archive.
- **Routing engine:** self-hosted Valhalla built from the same regional OSM extract.
- **Navigation UI:** MapLibre Navigation SDK / compatible turn-by-turn SDK, with voice guidance and rerouting.
- **Location:** Android fused/location services, subject to runtime permission and foreground-location requirements.
- **Search/geocoding:** separate place/address search service backed by regional OSM data.
- **Rider intelligence:** 4Riders-owned hotspot overlays, rider zones, user reports, and later traffic observations.

## Klang Valley coverage
Do not treat Klang Valley as a rectangular bounding box in the final dataset. Define and review a coverage polygon that includes the intended urban corridor (Klang, Shah Alam, Subang Jaya, Petaling Jaya, Kuala Lumpur, Ampang, Cheras, Puchong, Kajang, Putrajaya and Cyberjaya) and clips the source extract to that polygon. Confirm edge coverage before producing the production tiles.

## Routing behaviour
- Initial vehicle profile: motorcycle-oriented routing, with road access and turn restrictions respected.
- Provide route alternatives where available.
- Display distance, ETA, next manoeuvre, lane/turn guidance where source data supports it, and voice prompts.
- Reroute when the rider leaves the route.
- Do not claim live congestion or Waze-equivalent incident intelligence until a real-time traffic/incident data source and reporting workflow exist.
- Treat OSM lane counts, turn lanes, one-way tags, surface and access tags as source data; do not invent missing lane or traffic details.

## Data and licensing
OpenStreetMap data is available under ODbL. Preserve required attribution and assess ODbL obligations for the distributed database and derived database. Keep source attribution visible in the map UI and documentation. Satellite/aerial imagery must come from a separately licensed source; OSM data does not include satellite imagery.

## Delivery phases
1. **Data foundation:** define the Klang Valley polygon, obtain the Malaysia OSM PBF extract, clip it, inspect roads/building footprints/POIs, and produce a repeatable update pipeline.
2. **Map foundation:** integrate MapLibre into the Android app, apply the approved 4Riders visual style, and load regional vector tiles.
3. **Navigation foundation:** build Valhalla from the same clipped data; connect route search, route alternatives, turn-by-turn instructions, voice prompts and rerouting.
4. **Rider intelligence:** add hotspots, rider-defined zones, road/merchant context and user reports.
5. **Traffic layer:** only after a legitimate live data source and sufficient rider reports are available.

## Acceptance criteria for the first usable map
- Map is limited to the approved Klang Valley coverage.
- Roads and building footprints come from the regional source data, not hand-drawn approximations.
- Road direction/access restrictions are respected by routing when present in OSM.
- Map remains usable while GPS location updates.
- Navigation shows a real route and real manoeuvre instructions from the routing engine.
- Attribution and source licensing are visible.
