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

const server = http.createServer(async (req, res) => {
  const pathname = new URL(req.url || "/", "http://localhost").pathname;

  if (pathname === "/health") {
    res.writeHead(200, { ...cors, "Content-Type": "application/json" });
    res.end(JSON.stringify({ ok: true, service: "4riders-pmtiles-proxy" }));
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
      redirect: "follow"
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

    res.writeHead(upstream.status, responseHeaders);
    if (req.method === "HEAD" || !upstream.body) {
      res.end();
      return;
    }

    const reader = upstream.body.getReader();
    req.on("close", () => reader.cancel().catch(() => {}));
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      if (!res.write(Buffer.from(value))) {
        await new Promise(resolve => res.once("drain", resolve));
      }
    }
    res.end();
  } catch (error) {
    if (!res.headersSent) {
      res.writeHead(502, { ...cors, "Content-Type": "text/plain; charset=utf-8" });
    }
    res.end(error instanceof Error ? error.message : "Upstream archive unavailable");
  }
});

server.listen(PORT, "0.0.0.0", () => {
  console.log(`4Riders PMTiles proxy listening on ${PORT}`);
});
