# PMTiles browser delivery

The GitHub Release asset supports HTTP byte ranges, but its final Azure Blob response does not return the CORS headers MapLibre/PMTiles needs. Do not point `VITE_PMTILES_URL` directly at the GitHub Release URL.

## Cloudflare Worker proxy

This folder contains a fixed-origin streaming proxy for the public Klang Valley archive. It:
- accepts only GET, HEAD and OPTIONS;
- serves only the PMTiles archive and manifest (not arbitrary URLs);
- forwards HTTP Range and If-Range headers unchanged;
- streams the upstream response, preserving 206 / 416 status and Content-Range;
- adds browser CORS headers and exposes Content-Range to JavaScript.

### Deploy

1. In Cloudflare, open **Workers & Pages** and create a Worker named `4riders-map-tiles`.
2. Paste the contents of `worker.js` into the Worker editor and deploy.
3. Test the Worker URL with a byte-range request:
   `curl -i -H "Origin: https://fourriders-custom-map.onrender.com" -H "Range: bytes=0-15" https://YOUR-WORKER.workers.dev/klang-valley-basemap.pmtiles`
4. Confirm HTTP 206, a 16-byte response, `Access-Control-Allow-Origin: *`, and `Access-Control-Expose-Headers` containing `Content-Range`.
5. Set the Render static-site environment variable `VITE_PMTILES_URL` to:
   `https://YOUR-WORKER.workers.dev/klang-valley-basemap.pmtiles`
6. Redeploy the Custom Map static site and verify tiles in the browser network panel.

The worker uses a fixed GitHub Release origin and is not an open proxy. It streams the 112 MB archive instead of loading it into memory. The Worker is a delivery bridge; it does not make the map offline. Offline use still requires downloading/caching the archive and packaging routing/building data separately.

## Operational note

The PMTiles archive is public OpenStreetMap-derived data under ODbL. Keep attribution visible in the app. For sustained traffic, move the archive to Cloudflare R2 with a bucket CORS policy; that avoids routing every tile-range request through a Worker.
