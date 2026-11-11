import http from "node:http";

const PORT = Number(process.env.PORT || 10000);
const UPSTREAM = "https://github.com/kakashinal99/scan4riders/releases/download/klang-valley-basemap-latest/klang-valley-basemap.pmtiles";

const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, HEAD, OPTIONS",
  "Access-Control-Allow-Headers": "Range, If-Range, If-None-Match, If-Modified-Since",
  "Access-Control-Expose-Headers": "Accept-Ranges, Content-Range, Content-Length, ETag, Last-Modified",
  "Access-Control-Max-Age": "86400"
};

async function readJsonBody(req, maxBytes = 16384) {
  const chunks = [];
  let size = 0;
  for await (const chunk of req) {
    size += chunk.length;
    if (size > maxBytes) throw new Error("Request body too large");
    chunks.push(chunk);
  }
  return JSON.parse(Buffer.concat(chunks).toString("utf8") || "{}");
}

function sendJson(res, status, body, extraHeaders = {}) {
  res.writeHead(status, { ...cors, "Content-Type": "application/json", ...extraHeaders });
  res.end(JSON.stringify(body));
}

async function verifySupabaseUser(accessToken) {
  const supabaseUrl = process.env.SUPABASE_URL;
  const publishableKey = process.env.SUPABASE_PUBLISHABLE_KEY;
  if (!supabaseUrl || !publishableKey) throw new Error("Supabase auth proxy is not configured");
  const response = await fetch(supabaseUrl + "/auth/v1/user", {
    headers: { apikey: publishableKey, Authorization: "Bearer " + accessToken },
    signal: AbortSignal.timeout(10000)
  });
  if (!response.ok) return null;
  const user = await response.json();
  return user && typeof user.id === "string" ? user : null;
}

const server = http.createServer(async (req, res) => {
  const pathname = new URL(req.url || "/", "http://localhost").pathname;

  if (pathname === "/health") {
    res.writeHead(200, { ...cors, "Content-Type": "application/json" });
    res.end(JSON.stringify({ ok: true, service: "4riders-pmtiles-proxy" }));
    return;
  }

  if (pathname === "/route/traffic") {
    if (req.method === "OPTIONS") {
      res.writeHead(204, cors);
      res.end();
      return;
    }
    if (req.method !== "POST") {
      sendJson(res, 405, { error: "Method not allowed" }, { Allow: "POST, OPTIONS" });
      return;
    }
    const authorization = req.headers.authorization || "";
    const accessToken = authorization.startsWith("Bearer ") ? authorization.slice(7) : "";
    if (!accessToken) {
      sendJson(res, 401, { error: "Authentication required" });
      return;
    }
    if (!process.env.GOOGLE_MAPS_API_KEY) {
      sendJson(res, 503, { error: "Live traffic provider is not configured" });
      return;
    }
    try {
      const user = await verifySupabaseUser(accessToken);
      if (!user) {
        sendJson(res, 401, { error: "Invalid or expired session" });
        return;
      }
      const body = await readJsonBody(req);
      const validPoint = (p) => p && Number.isFinite(p.latitude) && Number.isFinite(p.longitude)
        && Math.abs(p.latitude) <= 90 && Math.abs(p.longitude) <= 180;
      if (!validPoint(body.origin) || !validPoint(body.pickup) || !validPoint(body.dropoff)) {
        sendJson(res, 400, { error: "Valid origin, pickup and dropoff coordinates are required" });
        return;
      }
      const requestBody = {
        origin: { location: { latLng: { latitude: body.origin.latitude, longitude: body.origin.longitude } } },
        intermediates: [{ location: { latLng: { latitude: body.pickup.latitude, longitude: body.pickup.longitude } } }],
        destination: { location: { latLng: { latitude: body.dropoff.latitude, longitude: body.dropoff.longitude } } },
        travelMode: "TWO_WHEELER",
        routingPreference: "TRAFFIC_AWARE",
        extraComputations: ["TRAFFIC_ON_POLYLINE"],
        polylineQuality: "HIGH_QUALITY",
        polylineEncoding: "ENCODED_POLYLINE",
        languageCode: "en-MY",
        units: "METRIC"
      };
      const upstream = await fetch("https://routes.googleapis.com/directions/v2:computeRoutes", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "X-Goog-Api-Key": process.env.GOOGLE_MAPS_API_KEY,
          "X-Goog-FieldMask": "routes.distanceMeters,routes.duration,routes.staticDuration,routes.polyline.encodedPolyline,routes.travelAdvisory.speedReadingIntervals"
        },
        body: JSON.stringify(requestBody),
        signal: AbortSignal.timeout(20000)
      });
      const payload = await upstream.json().catch(() => ({}));
      if (!upstream.ok) {
        console.error("[Google Routes]", upstream.status, payload.error?.status || "provider_error");
        sendJson(res, upstream.status >= 500 ? 502 : upstream.status, {
          error: "Traffic-aware motorcycle route unavailable",
          providerStatus: payload.error?.status || null
        });
        return;
      }
      const route = payload.routes?.[0];
      if (!route?.polyline?.encodedPolyline) {
        sendJson(res, 502, { error: "Traffic provider returned no route" });
        return;
      }
      sendJson(res, 200, {
        distanceMeters: route.distanceMeters,
        duration: route.duration,
        staticDuration: route.staticDuration,
        encodedPolyline: route.polyline.encodedPolyline,
        traffic: route.travelAdvisory?.speedReadingIntervals || [],
        provider: "google-routes",
        travelMode: "TWO_WHEELER",
        trafficAware: true,
        userId: user.id
      });
    } catch (error) {
      console.error("[Traffic route proxy]", error);
      sendJson(res, 502, { error: error instanceof Error ? error.message : "Traffic route proxy failed" });
    }
    return;
  }

  if (pathname !== "/klang-valley-basemap.pmtiles") {
    res.writeHead(404, cors);
    res.end("Not found");
    return;
  }

  if (req.method === "OPTIONS") {
    res.writeHead(204, cors);
    res.end();
    return;
  }

  if (req.method !== "GET" && req.method !== "HEAD") {
    res.writeHead(405, { ...cors, Allow: "GET, HEAD, OPTIONS" });
    res.end("Method not allowed");
    return;
  }

  try {
    const headers = {};
    for (const name of ["range", "if-range", "if-none-match", "if-modified-since"]) {
      if (req.headers[name]) headers[name] = req.headers[name];
    }

    const upstream = await fetch(UPSTREAM, {
      method: req.method,
      headers,
      redirect: "follow",
      signal: AbortSignal.timeout(90000)
    });

    const responseHeaders = { ...cors };
    for (const name of [
      "accept-ranges",
      "content-range",
      "content-length",
      "content-type",
      "etag",
      "last-modified",
      "cache-control"
    ]) {
      const value = upstream.headers.get(name);
      if (value) responseHeaders[name] = value;
    }

    console.log("[PMTiles upstream]", req.method, req.headers.range || "no-range", upstream.status, upstream.headers.get("content-range") || "");

    res.writeHead(upstream.status, responseHeaders);
    if (req.method === "HEAD" || !upstream.body) {
      res.end();
      return;
    }

    const reader = upstream.body.getReader();
    // The incoming request's close event can fire as soon as its request body
    // is consumed. Watch the outgoing response instead so byte-range streams
    // are not cancelled before the PMTiles bytes reach the browser.
    res.on("close", () => {
      if (!res.writableEnded) reader.cancel().catch(() => {});
    });

    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      if (!res.write(Buffer.from(value))) {
        await new Promise(resolve => res.once("drain", resolve));
      }
    }
    if (!res.writableEnded) res.end();
  } catch (error) {
    console.error("[PMTiles proxy error]", error);
    if (!res.headersSent) {
      res.writeHead(502, { ...cors, "Content-Type": "text/plain; charset=utf-8" });
    }
    if (!res.writableEnded) res.end(error instanceof Error ? error.message : "Upstream archive unavailable");
  }
});

server.listen(PORT, "0.0.0.0", () => {
  console.log(`4Riders PMTiles proxy listening on ${PORT}`);
});
