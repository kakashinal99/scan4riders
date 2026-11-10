const RELEASE_BASE = "https://github.com/kakashinal99/scan4riders/releases/download/klang-valley-basemap-latest/";
const ALLOWED_FILES = new Set(["klang-valley-basemap.pmtiles", "manifest.json"]);

function corsHeaders() {
  return {
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Methods": "GET, HEAD, OPTIONS",
    "Access-Control-Allow-Headers": "Range, If-Range, Origin, Accept",
    "Access-Control-Expose-Headers": "Accept-Ranges, Content-Length, Content-Range, ETag, Last-Modified",
    "Vary": "Origin"
  };
}

export default {
  async fetch(request) {
    const url = new URL(request.url);
    const filename = url.pathname.replace(/^\\/+/, "");

    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: corsHeaders() });
    }

    if (request.method !== "GET" && request.method !== "HEAD") {
      return new Response("Method not allowed", {
        status: 405,
        headers: { ...corsHeaders(), "Allow": "GET, HEAD, OPTIONS" }
      });
    }

    if (!ALLOWED_FILES.has(filename) || url.search) {
      return new Response("Not found", {
        status: 404,
        headers: corsHeaders()
      });
    }

    const upstreamHeaders = new Headers();
    const range = request.headers.get("Range");
    const ifRange = request.headers.get("If-Range");
    const accept = request.headers.get("Accept");
    if (range) upstreamHeaders.set("Range", range);
    if (ifRange) upstreamHeaders.set("If-Range", ifRange);
    if (accept) upstreamHeaders.set("Accept", accept);

    try {
      // Fixed upstream host and allow-listed filenames: this is not an open proxy.
      // Fetch streams the 112 MB archive; it does not buffer the whole file in memory.
      const upstream = await fetch(new URL(filename, RELEASE_BASE), {
        method: request.method,
        headers: upstreamHeaders,
        redirect: "follow"
      });

      const headers = new Headers(upstream.headers);
      for (const [key, value] of Object.entries(corsHeaders())) headers.set(key, value);
      headers.set("Cache-Control", "public, max-age=300");
      headers.set("X-Content-Type-Options", "nosniff");

      return new Response(request.method === "HEAD" ? null : upstream.body, {
        status: upstream.status,
        statusText: upstream.statusText,
        headers
      });
    } catch {
      return new Response("Upstream map archive unavailable", {
        status: 502,
        headers: { ...corsHeaders(), "Cache-Control": "no-store" }
      });
    }
  }
};
