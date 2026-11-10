# 4Riders Custom Map — foundation

Map-first pilot for Batu Tiga, Shah Alam.

## Stack
- React + TypeScript + Vite
- MapLibre GL JS
- OpenFreeMap vector styles (OpenStreetMap-derived data)
- Photon geocoder for place search
- Supabase/PostGIS is planned for owned rider, hotspot and operational-boundary data; not connected in this foundation.

## Run locally
```bash
cd custom-map
npm install
npm run dev
```

## Current scope
- Interactive vector map centred on Batu Tiga KTM station area
- Pan, zoom, recenter, device GPS
- Place search with Photon
- Uniform muted-purple building footprint styling when the provider style exposes building fill layers
- Responsive collapsed/expanded bottom information panel
- No synthetic hotspots or invented delivery-zone polygons

## Data and attribution
Map tiles/styles are provided by OpenFreeMap; underlying map data is from OpenStreetMap. Keep attribution visible in the application. Photon is used for search. Review each provider's current usage and attribution terms before production launch.

## Next implementation stages
1. Verify building layer IDs and geometry coverage across Batu Tiga at street zoom.
2. Establish a versioned local PostGIS dataset for the pilot area.
3. Add surveyed/verified operational boundaries.
4. Add hotspots from actual order and rider activity, with privacy-safe aggregation.
5. Add vector tile hosting/cache and mobile performance tests.
