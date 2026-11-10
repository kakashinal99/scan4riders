const UPSTREAM =
  "https://github.com/kakashinal99/scan4riders/releases/download/klang-valley-basemap-latest/klang-valley-basemap.pmtiles";

const ALLOW_HEADERS =
  "Range, If-Range, If-None-Match, If-Modified-Since";

const EXPOSE_HEADERS =
  "Accept-Ranges, Content-Range, Content-Length, ETag, Last-Modified";

function corsHeaders() {
  return {
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Methods": "GET, HEAD, OPTIONS",
    "Access-Control-Allow-Headers": ALLOW_HEADERS,
    "Access-Control-Expose-Headers": EXPOSE_HEADERS,
    "Access-Control-Max-Age": "86400",
  };
}

export default {
  async fetch(request) {
    const url = new URL(request.url);

    if (url.pathname !== "/klang-valley-basemap.pmtiles") {
      return new Response("Not found", {
        status: 404,
        headers: corsHeaders(),
      });
    }

    if (request.method === "OPTIONS") {
      return new Response(null, {
        status: 204,
        headers: corsHeaders(),
      });
    }

    if (request.method !== "GET" && request.method !== "HEAD") {
      return new Response("Method not allowed", {
        status: 405,
        headers: { ...corsHeaders(), Allow: "GET, HEAD, OPTIONS" },
      });
    }

    const upstreamHeaders = new Headers();
    for (const name of [
      "Range",
      "If-Range",
      "If-None-Match",
      "If-Modified-Since",
    ]) {
      const value = request.headers.get(name);
      if (value) upstreamHeaders.set(name, value);
    }

    try {
      const upstream = await fetch(UPSTREAM, {
        method: request.method,
        headers: upstreamHeaders,
        redirect: "follow",
      });

      const headers = new Headers(corsHeaders());
      for (const name of [
        "Accept-Ranges",
        "Content-Range",
        "Content-Length",
        "Content-Type",
        "ETag",
        "Last-Modified",
        "Cache-Control",
      ]) {
        const value = upstream.headers.get(name);
        if (value) headers.set(name, value);
      }

      // Preserve the upstream 206 response and partial body. PMTiles relies
      // on byte ranges; returning a full 200 response breaks archive reads.
      return new Response(request.method === "HEAD" ? null : upstream.body, {
        status: upstream.status,
        statusText: upstream.statusText,
        headers,
      });
    } catch (error) {
      return new Response(
        error instanceof Error ? error.message : "Upstream map archive unavailable",
        {
          status: 502,
          headers: {
            ...corsHeaders(),
            "Content-Type": "text/plain; charset=utf-8",
            "Cache-Control": "no-store",
          },
        },
      );
    }
  },
};
