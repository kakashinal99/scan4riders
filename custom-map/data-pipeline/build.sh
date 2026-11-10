#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK_DIR="${WORK_DIR:-"$ROOT/work"}"
OUTPUT_DIR="${OUTPUT_DIR:-"$ROOT/output"}"
PBF_URL="https://download.geofabrik.de/asia/malaysia-singapore-brunei-latest.osm.pbf"
PBF_NAME="malaysia-singapore-brunei-latest.osm.pbf"
WEST="101.20"
SOUTH="2.76"
EAST="102.00"
NORTH="3.62"
# Planetiler currently supports generated tiles through z16. The map renderer
# can overzoom these vector tiles for closer street-level viewing.
MAX_ZOOM="${MAX_ZOOM:-16}"

command -v docker >/dev/null || { echo "Docker is required." >&2; exit 1; }
command -v osmium >/dev/null || { echo "Osmium Tool is required (https://osmcode.org/osmium-tool/)." >&2; exit 1; }
command -v curl >/dev/null || { echo "curl is required." >&2; exit 1; }
command -v md5sum >/dev/null || { echo "md5sum is required." >&2; exit 1; }

mkdir -p "$WORK_DIR" "$OUTPUT_DIR"
cd "$WORK_DIR"

echo "==> Downloading latest Geofabrik regional OSM snapshot"
curl -fL --retry 3 --retry-delay 3 "$PBF_URL" -o "$PBF_NAME"
curl -fL --retry 3 --retry-delay 3 "$PBF_URL.md5" -o "$PBF_NAME.md5"
md5sum --check "$PBF_NAME.md5"

echo "==> Extracting Klang Valley coverage envelope"
osmium extract \
  --bbox="$WEST,$SOUTH,$EAST,$NORTH" \
  --strategy=complete_ways \
  --overwrite \
  "$PBF_NAME" \
  --output=klang-valley.osm.pbf

test -s klang-valley.osm.pbf

echo "==> Building standalone vector basemap with Planetiler (max zoom $MAX_ZOOM)"
docker run --rm \
  -e JAVA_TOOL_OPTIONS="-Xmx4g" \
  -v "$WORK_DIR:/data" \
  ghcr.io/onthegomap/planetiler:latest \
  --osm-path=/data/klang-valley.osm.pbf \
  --output=/data/klang-valley-basemap.pmtiles \
  --maxzoom="$MAX_ZOOM" \
  --download=full \
  --force

test -s klang-valley-basemap.pmtiles

echo "==> Verifying PMTiles archive"
docker run --rm \
  -v "$WORK_DIR:/data" \
  protomaps/go-pmtiles:latest \
  verify /data/klang-valley-basemap.pmtiles

docker run --rm \
  -v "$WORK_DIR:/data" \
  protomaps/go-pmtiles:latest \
  show /data/klang-valley-basemap.pmtiles --metadata

cp klang-valley-basemap.pmtiles "$OUTPUT_DIR/klang-valley-basemap.pmtiles"

cat > "$OUTPUT_DIR/manifest.json" <<EOF
{
  "product": "4Riders Custom Map",
  "dataset": "Klang Valley standalone basemap",
  "source": "OpenStreetMap contributors via Geofabrik",
  "source_url": "$PBF_URL",
  "built_at_utc": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "coverage_type": "bounding-box envelope; not an administrative boundary",
  "bounds_wgs84": {
    "west": $WEST,
    "south": $SOUTH,
    "east": $EAST,
    "north": $NORTH
  },
  "max_zoom": $MAX_ZOOM,
  "format": "PMTiles",
  "tile_schema": "OpenMapTiles",
  "attribution": "© OpenStreetMap contributors",
  "license": "ODbL-1.0"
}
EOF

echo "==> Build complete"
ls -lh "$OUTPUT_DIR/klang-valley-basemap.pmtiles" "$OUTPUT_DIR/manifest.json"
