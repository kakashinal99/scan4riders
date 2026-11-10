# 4Riders Custom Map — foundation

Map-first pilot for Batu Tiga, Shah Alam.

## Stack
- React + TypeScript + Vite
- MapLibre GL JS
- First-party MapLibre style authored in `src/mapStyle.ts`
- Klang Valley vector data served from the project's versioned PMTiles archive through the project's own range proxy
- OpenStreetMap-derived geometry and attributes; OpenStreetMap attribution remains visible
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
- Pan, zoom, recenter, continuous foreground GPS follow mode
- Live blue GPS position marker with an accuracy halo; a directional arrow appears when the device reports a valid GPS heading
- Motorcycle route preview, active GPS follow and off-route route recalculation (beta)
- Installable PWA shell with app icon and limited app-shell/static-asset caching
- Place search with Photon
- Uniform muted-purple 2D building footprints from the vector building layer
- Building-name labels rendered from vector-tile building attributes (no separate live Overpass request)
- Responsive collapsed/expanded bottom information panel
- No synthetic hotspots or invented delivery-zone polygons

## Data and attribution
The map style is authored by 4Riders and geographic vector data is read from the project's Klang Valley PMTiles archive. The PMTiles archive is delivered by the 4Riders range proxy from the project's GitHub Release. Map glyphs currently use the OpenMapTiles font endpoint; Photon is used for place search; Valhalla public routing is used for motorcycle route estimates. Keep OpenStreetMap attribution visible and review provider usage limits/terms before production launch. The PWA cache does not yet make the PMTiles archive available offline.

## Next implementation stages
1. Validate building layer IDs, named-building coverage and footprint geometry across Batu Tiga at street zoom.
2. Validate motorcycle routing against local road restrictions, one-way roads and real test rides.
3. Add spoken, maneuver-by-maneuver guidance and arrival handling.
4. Add true offline regional tile downloads and cache management (the current cache is app-shell only).
5. Add surveyed operational boundaries and verified rider hotspots from privacy-safe operational data.
6. Package and test the Android app with native foreground/background location permissions.
