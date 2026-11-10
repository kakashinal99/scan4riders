# Klang Valley map data pipeline

This pipeline builds a **standalone, versioned Klang Valley basemap** for the 4Riders Custom Map app. It does not integrate the map into the 4Riders rider app.

## Data flow

1. Download the latest Malaysia–Singapore–Brunei OpenStreetMap PBF snapshot from Geofabrik.
2. Extract the Klang Valley coverage window from that regional PBF with Osmium.
3. Build a MapLibre-compatible vector-tile archive with Planetiler's OpenMapTiles profile.
4. Verify the PMTiles archive and write a provenance manifest.
5. Publish the archive as a GitHub Actions artifact. A later hosting step can copy the same immutable archive to object storage for online range requests and app-managed offline downloads.

The basemap contains OSM-mapped roads, building footprints, place names, POIs, land use, water, transit and other OpenMapTiles layers. Actual coverage depends on what contributors have mapped in OpenStreetMap.

## Coverage window

The initial extraction uses the envelope already used by the standalone map app:

- West: 101.20
- South: 2.76
- East: 102.00
- North: 3.62

This is a **coverage envelope**, not a claim that it is an authoritative administrative boundary for Greater Kuala Lumpur. It intentionally includes an edge buffer around the Klang Valley. Replace it with a reviewed multi-polygon boundary before treating the edge as a hard product boundary.

## Run locally (Linux/macOS)

Requirements: Docker, Osmium Tool, curl, md5sum, and at least 8 GB RAM plus 10 GB free disk space.

```bash
cd custom-map/data-pipeline
bash build.sh
```

Output:
- `output/klang-valley-basemap.pmtiles`
- `output/manifest.json`

The workflow is also manually runnable and scheduled monthly. It stores generated archives as workflow artifacts rather than committing large binary map files into Git.

## Online + offline delivery

- **Online:** host the immutable PMTiles file on an HTTP range-request-capable object store/CDN with CORS enabled, then point MapLibre's PMTiles protocol at that URL.
- **Offline:** the mobile app downloads the same archive for the selected region and stores it locally. The app must manage download progress, storage limits, version checks and atomic replacement of old archives.
- Keep the archive versioned by source snapshot date; do not overwrite a file while clients may still be downloading it.

The current workflow produces and verifies the archive. Public object storage and app-side offline download management are separate deployment tasks and are not claimed as completed here.

## Data quality and licensing

OpenStreetMap data is community-mapped and may be incomplete or stale. Lane counts, one-way tags, access restrictions, speed limits, building names and turn restrictions are only available where mapped. Missing tags must remain unknown; never infer them from road appearance.

Display visible attribution: **© OpenStreetMap contributors** and link to https://www.openstreetmap.org/copyright. OpenStreetMap data is available under the Open Database License (ODbL); review attribution and share-alike obligations for the final distribution model.

Sources:
- Geofabrik regional extract: https://download.geofabrik.de/asia/malaysia-singapore-brunei.html
- Planetiler: https://github.com/onthegomap/planetiler
- PMTiles: https://docs.protomaps.com/pmtiles/
