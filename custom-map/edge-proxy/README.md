# 4Riders PMTiles CORS proxy

GitHub Release assets support HTTP byte ranges, but their final download host does not expose the CORS headers required by browser-based PMTiles readers. This Cloudflare Worker forwards the browser's byte-range request and adds the CORS response headers without downloading the 112 MB archive into the app.

## Deploy

1. Sign in to Cloudflare and open **Workers & Pages**.
2. Create a Worker named `4riders-pmtiles-proxy`.
3. Upload or paste the contents of `worker.js` from this folder, then deploy.
   - Alternatively, from this folder run `npx wrangler deploy` after authenticating Wrangler with your Cloudflare account.
4. Copy the deployed Worker URL. It will look like `https://4riders-pmtiles-proxy.<your-subdomain>.workers.dev`.
5. In the Render service `4riders-custom-map`, set this build-time environment variable:

```text
VITE_PMTILES_URL=https://4riders-pmtiles-proxy.<your-subdomain>.workers.dev/klang-valley-basemap.pmtiles
```

6. Save the environment variable and let Render rebuild the static site.

## Verify byte-range and CORS

Replace the URL below with your deployed Worker URL:

```bash
curl -i -H 'Origin: https://fourriders-custom-map.onrender.com' \
  -H 'Range: bytes=0-15' \
  'https://4riders-pmtiles-proxy.<your-subdomain>.workers.dev/klang-valley-basemap.pmtiles'
```

Expected response: HTTP `206 Partial Content`, a 16-byte body, `Content-Range`, `Access-Control-Allow-Origin: *`, and `Access-Control-Expose-Headers` including `Content-Range`.

The Worker streams only the requested byte range. It does not store or proxy the entire archive as one response. The source archive remains the public GitHub Release asset.

## Notes

- The Worker accepts only GET, HEAD, and OPTIONS.
- Only the fixed 4Riders PMTiles asset is proxied; arbitrary URLs cannot be supplied.
- The proxy is public and read-only. Do not add secrets or private data to it.
