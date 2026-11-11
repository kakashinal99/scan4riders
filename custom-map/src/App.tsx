import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react';
import * as maplibregl from 'maplibre-gl';
import { PMTiles, Protocol } from 'pmtiles';
import { buildMapStyle } from './mapStyle';
import RoutePlanner from './RoutePlanner';
import type { Map as MapLibreMap } from 'maplibre-gl';
import { Crosshair, Layers3, MapPin, Minus, Plus, Search, X, Route, LocateFixed, Home } from 'lucide-react';

const KLANG_VALLEY_CENTER: [number, number] = [101.55, 3.105];
const KLANG_VALLEY_BOUNDS: [[number, number], [number, number]] = [[101.20, 2.76], [102.00, 3.62]];
const PMTILES_URL = import.meta.env.VITE_PMTILES_URL?.trim() ?? '';
const pmtilesProtocol = new Protocol();
maplibregl.addProtocol('pmtiles', pmtilesProtocol.tile);
const PHOTON_API = 'https://photon.komoot.io/api/';
const VALHALLA_ISODISTANCE_API = 'https://valhalla1.openstreetmap.de/isochrone';

const COVERAGE_SETTINGS_KEY = '4riders.custom-map.coverage.v1';
type SavedCoverageSettings = { radius: number; center: [number, number]; label: string };
function readCoverageSettings(): SavedCoverageSettings {
  const fallback: SavedCoverageSettings = {
    radius: 30,
    center: KLANG_VALLEY_CENTER,
    label: 'Klang Valley default center'
  };
  try {
    const raw = window.localStorage.getItem(COVERAGE_SETTINGS_KEY);
    if (!raw) return fallback;
    const saved = JSON.parse(raw) as Partial<SavedCoverageSettings>;
    const radius = Number(saved.radius);
    const center = saved.center;
    if (!Number.isFinite(radius) || radius < 1 || radius > 100 ||
        !Array.isArray(center) || center.length !== 2 ||
        !Number.isFinite(center[0]) || !Number.isFinite(center[1]) ||
        typeof saved.label !== 'string') return fallback;
    return { radius, center: [center[0], center[1]], label: saved.label };
  } catch {
    return fallback;
  }
}


type SearchFeature = { geometry: { coordinates: [number, number] }; properties: { name?: string; street?: string; city?: string; state?: string; country?: string } };
type RoadSelection = { name: string; roadClass: string; direction: string; surface: string; access: string; structure: string; lanes: string; selectionKey: string };
type BuildingSelection = { name: string; address: string; addressLabel: string; category: string; block: string; reference: string; details: Array<[string, string]> };

export default function App() {
  const mapNode = useRef<HTMLDivElement>(null);
  const mapRef = useRef<MapLibreMap | null>(null);
  const [ready, setReady] = useState(false);
  const [expanded, setExpanded] = useState(false);
  const [sheetCollapsed, setSheetCollapsed] = useState(true);
  const [activePanel, setActivePanel] = useState<string | null>(null);
  const [gpsTracking, setGpsTracking] = useState(false);
  const [gpsAccuracy, setGpsAccuracy] = useState<number | null>(null);
  const gpsWatchId = useRef<number | null>(null);
  const gpsMarkerRef = useRef<maplibregl.Marker | null>(null);
  const gpsArrowRef = useRef<SVGElement | null>(null);
  const lastGpsFixAt = useRef<number>(0);
  const [buildingsOn, setBuildingsOn] = useState(true);
  const [buildingCount, setBuildingCount] = useState<number | null>(null);
  const [namedBuildingCount, setNamedBuildingCount] = useState<number | null>(null);
  const roadLookupAbort = useRef<AbortController | null>(null);
  const [selectedRoad, setSelectedRoad] = useState<RoadSelection | null>(null);
  const [selectedBuilding, setSelectedBuilding] = useState<BuildingSelection | null>(null);
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<SearchFeature[]>([]);
  const [searching, setSearching] = useState(false);
  const [message, setMessage] = useState('Loading Klang Valley map…');
  const [centerLabel, setCenterLabel] = useState('Klang Valley');
  const [reachStatus, setReachStatus] = useState<'idle' | 'loading' | 'ready' | 'error'>('idle');
  const [reachMessage, setReachMessage] = useState('');
  const [coverageSettingsOpen, setCoverageSettingsOpen] = useState(false);
  const [initialCoverageSettings] = useState(readCoverageSettings);
  const [coverageRadius, setCoverageRadius] = useState(initialCoverageSettings.radius);
  const [coverageCenter, setCoverageCenter] = useState<[number, number]>(initialCoverageSettings.center);
  const [coverageCenterLabel, setCoverageCenterLabel] = useState(initialCoverageSettings.label);
  const [pickingCoverageCenter, setPickingCoverageCenter] = useState(false);
  const pickingCoverageCenterRef = useRef(false);
  const coverageCenterRef = useRef<[number, number]>(KLANG_VALLEY_CENTER);
  useEffect(() => { pickingCoverageCenterRef.current = pickingCoverageCenter; }, [pickingCoverageCenter]);
  useEffect(() => { coverageCenterRef.current = coverageCenter; }, [coverageCenter]);
  useEffect(() => {
    try {
      window.localStorage.setItem(COVERAGE_SETTINGS_KEY, JSON.stringify({
        radius: coverageRadius,
        center: coverageCenter,
        label: coverageCenterLabel
      }));
    } catch (error) {
      console.warn('[4Riders coverage settings] Could not persist settings', error);
    }
  }, [coverageRadius, coverageCenter, coverageCenterLabel]);

  const lookupRoadTags = async (lng: number, lat: number, roadName: string, selectionKey: string) => {
    roadLookupAbort.current?.abort();
    const controller = new AbortController();
    roadLookupAbort.current = controller;
    const queryText = `[out:json][timeout:15];way(around:25,${lat.toFixed(6)},${lng.toFixed(6)})["highway"];out tags geom;`;
    try {
      const response = await fetch('https://overpass-api.de/api/interpreter', {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' },
        body: `data=${encodeURIComponent(queryText)}`,
        signal: controller.signal
      });
      if (!response.ok) throw new Error('Road tag query failed');
      const payload = await response.json() as {
        elements?: Array<{ tags?: Record<string, string>; geometry?: Array<{ lon: number; lat: number }> }>;
      };
      const candidates = (payload.elements ?? []).filter((item) => item.geometry && item.geometry.length > 1);
      const namedCandidates = candidates.filter((item) => roadName !== 'Jalan tanpa nama' && item.tags?.name === roadName);
      const pool = namedCandidates.length ? namedCandidates : candidates;
      const distanceToRoad = (geometry: Array<{ lon: number; lat: number }>) => {
        const cosLat = Math.cos(lat * Math.PI / 180);
        let minimum = Number.POSITIVE_INFINITY;
        for (let i = 1; i < geometry.length; i++) {
          const ax = (geometry[i - 1].lon - lng) * 111320 * cosLat;
          const ay = (geometry[i - 1].lat - lat) * 110540;
          const bx = (geometry[i].lon - lng) * 111320 * cosLat;
          const by = (geometry[i].lat - lat) * 110540;
          const dx = bx - ax, dy = by - ay;
          const lengthSquared = dx * dx + dy * dy;
          const t = lengthSquared === 0 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / lengthSquared));
          minimum = Math.min(minimum, Math.hypot(ax + t * dx, ay + t * dy));
        }
        return minimum;
      };
      const nearest = pool
        .map((item) => ({ item, distance: distanceToRoad(item.geometry ?? []) }))
        .sort((a, b) => a.distance - b.distance)[0]?.item;
      if (controller.signal.aborted) return;
      const tags = nearest?.tags ?? {};
      const laneTag = tags.lanes;
      const laneText = laneTag ? `${laneTag} lane${laneTag === '1' ? '' : 's'}` : 'Not tagged in OpenStreetMap';
      const oneWay = tags.oneway;
      const direction = oneWay === 'yes' || oneWay === '1' || oneWay === 'true'
        ? 'Sehala · ikut arah OSM'
        : oneWay === '-1'
          ? 'Sehala · arah songsang OSM'
          : oneWay === 'no' || oneWay === '0'
            ? 'Dua hala · OSM'
            : null;
      setSelectedRoad((current) => current?.selectionKey === selectionKey
        ? { ...current, lanes: laneText, direction: direction ?? current.direction }
        : current);
    } catch (error) {
      if (!controller.signal.aborted) {
        console.warn('[4Riders road tags]', error);
        setSelectedRoad((current) => current?.selectionKey === selectionKey
          ? { ...current, lanes: 'OSM lookup unavailable' }
          : current);
      }
    }
  };

  useEffect(() => {
    const container = mapNode.current;
    if (!container || mapRef.current) return;

    let disposed = false;
    let map: MapLibreMap | null = null;
    let resizeObserver: ResizeObserver | null = null;
    let laneRefreshTimer: number | null = null;
    let laneLookupController: AbortController | null = null;

    const startMap = async () => {
      if (!PMTILES_URL) {
        setMessage('Map archive is not configured · set VITE_PMTILES_URL to the hosted Klang Valley PMTiles file');
        return;
      }
      try {
        // Check the actual archive before telling the UI that map data is ready.
        // A MapLibre "load" event only means the style loaded; it does not prove
        // that the PMTiles archive contains usable vector tiles.
        const archive = new PMTiles(PMTILES_URL);
        const [header, rawMetadata] = await Promise.all([archive.getHeader(), archive.getMetadata()]);
        if (disposed) return;
        if (header.tileType !== 1) throw new Error('Archive is not an MVT vector-tile PMTiles file');
        const metadata = rawMetadata as { vector_layers?: Array<{ id?: string }> };
        const layerIds = new Set((metadata.vector_layers ?? []).map((layer) => layer.id).filter(Boolean));
        const requiredLayers = ['transportation', 'building'];
        const missingLayers = requiredLayers.filter((id) => !layerIds.has(id));
        if (missingLayers.length) {
          throw new Error(`Archive is missing required vector layers: ${missingLayers.join(', ')}`);
        }

        map = new maplibregl.Map({
          container,
          style: buildMapStyle(PMTILES_URL),
          center: initialCoverageSettings.center,
          zoom: 10.6,
          minZoom: 9.4,
          maxBounds: KLANG_VALLEY_BOUNDS,
          maxZoom: 20,
          attributionControl: false,
          pitchWithRotate: false,
          dragRotate: false,
          touchPitch: false,
          trackResize: true
        });
        mapRef.current = map;
      } catch (error) {
        if (!disposed) {
          const detail = error instanceof Error ? error.message : 'Unknown archive error';
          console.error('[4Riders PMTiles preflight]', error);
          setMessage(`PMTiles archive check failed · ${detail}`);
        }
        return;
      }
      if (disposed || !map) return;
      const activeMap = map;
    activeMap.addControl(new maplibregl.AttributionControl({ compact: true }), 'bottom-right');

    // Mobile browsers can report an incomplete container size on first paint.
    // Observe the actual map element and explicitly resize after layout settles.
    resizeObserver = new ResizeObserver(() => map?.resize());
    resizeObserver.observe(container);
    requestAnimationFrame(() => map?.resize());

    activeMap.on('load', () => {
      requestAnimationFrame(() => map?.resize());
      const layers = activeMap.getStyle().layers ?? [];
      let count = 0;
      // Keep the road hierarchy legible and visually distinct at every useful zoom.
      layers.forEach((layer) => {
        const id = layer.id.toLowerCase();
        if (layer.type !== 'line' || !(id.includes('transportation') || id.includes('road'))) return;
        try {
          activeMap.setPaintProperty(layer.id, 'line-color', [
            'match', ['get', 'class'],
            'motorway', '#d8a4c8', 'trunk', '#c59acb',
            'primary', '#e0bd91', 'secondary', '#d2c49c',
            'tertiary', '#b8b0c5', 'minor', '#a49caf',
            'residential', '#8f899d', 'service', '#777181',
            '#9690a2'
          ]);
        } catch { /* Some style layers use a different schema. */ }
      });
      layers.forEach((layer) => {
        if (!layer.id.toLowerCase().includes('building')) return;
        count++;
        if (layer.type === 'fill') {
          activeMap.setPaintProperty(layer.id, 'fill-color', '#76658f');
          activeMap.setPaintProperty(layer.id, 'fill-opacity', 0.82);
          activeMap.setPaintProperty(layer.id, 'fill-outline-color', '#b9a5cd');
        }
        if (layer.type === 'line') {
          activeMap.setPaintProperty(layer.id, 'line-color', '#b4a2c8');
          activeMap.setPaintProperty(layer.id, 'line-opacity', 0.8);
        }
      });
      // OpenMapTiles transportation schema exposes oneway=1/-1. Draw arrows only
      // where direction is explicitly present; never infer direction from road class.
      if (activeMap.getSource('openmaptiles') && !activeMap.getLayer('4r-road-direction-arrows')) {
        activeMap.addLayer({
          id: '4r-road-direction-arrows',
          type: 'symbol',
          source: 'openmaptiles',
          'source-layer': 'transportation',
          minzoom: 15,
          filter: ['all', ['has', 'oneway'], ['!=', ['get', 'oneway'], 0]],
          layout: {
            'symbol-placement': 'line',
            'symbol-spacing': 150,
            'text-field': '➤',
            'text-font': ['Noto Sans Regular'],
            'text-size': 12,
            'text-keep-upright': false,
            'text-rotation-alignment': 'map',
            'text-rotate': ['case', ['==', ['get', 'oneway'], -1], 180, 0]
          },
          paint: { 'text-color': '#f2d8ff', 'text-halo-color': '#30263b', 'text-halo-width': 1.4 }
        });
      }
      if (activeMap.getSource('openmaptiles') && !activeMap.getLayer('4r-building-outlines')) {
        activeMap.addLayer({
          id: '4r-building-outlines',
          type: 'line',
          source: 'openmaptiles',
          'source-layer': 'building',
          minzoom: 15,
          paint: {
            'line-color': '#b9a5cd',
            'line-width': ['interpolate', ['linear'], ['zoom'], 15, 0.35, 18, 1.1],
            'line-opacity': 0.9
          }
        });
        count++;
      }
      // Building names are rendered directly from the vector-tile building
      // layer. When PMTiles is enabled, geometry and names are served from our
      // Klang Valley archive rather than live Overpass queries.
      if (activeMap.getSource('openmaptiles') && !activeMap.getLayer('4r-building-name-labels')) {
        activeMap.addLayer({
          id: '4r-building-name-labels',
          type: 'symbol',
          source: 'openmaptiles',
          'source-layer': 'building',
          minzoom: 15,
          filter: ['all', ['has', 'name'], ['!=', ['get', 'name'], '']],
          layout: {
            'symbol-placement': 'point',
            'text-field': ['get', 'name'],
            'text-font': ['Noto Sans Regular'],
            'text-size': ['interpolate', ['linear'], ['zoom'], 15, 9, 18, 12],
            'text-max-width': 10,
            'text-padding': 2,
            'text-allow-overlap': false,
            'text-ignore-placement': false
          },
          paint: {
            'text-color': '#f5eafc',
            'text-halo-color': '#211a2a',
            'text-halo-width': 1.5
          }
        });
      }
      // Road-distance coverage overlay, calculated from the actual OSM road graph.
      activeMap.addSource('4r-road-reach', {
        type: 'geojson',
        data: { type: 'FeatureCollection', features: [] }
      });
      activeMap.addLayer({
        id: '4r-road-reach-fill', type: 'fill', source: '4r-road-reach',
        filter: ['==', ['geometry-type'], 'Polygon'],
        paint: { 'fill-color': '#b58ad8', 'fill-opacity': 0.22 }
      });
      activeMap.addLayer({
        id: '4r-road-reach-outline', type: 'line', source: '4r-road-reach',
        filter: ['==', ['geometry-type'], 'Polygon'],
        paint: { 'line-color': '#d8b5f4', 'line-width': 3, 'line-opacity': 0.95 }
      });
      activeMap.addLayer({
        id: '4r-road-reach-origin', type: 'circle', source: '4r-road-reach',
        filter: ['==', ['geometry-type'], 'Point'],
        paint: { 'circle-radius': 7, 'circle-color': '#f4d27b', 'circle-stroke-color': '#211a2a', 'circle-stroke-width': 2.5 }
      });
      activeMap.addLayer({
        id: '4r-road-reach-origin-label', type: 'symbol', source: '4r-road-reach',
        filter: ['==', ['geometry-type'], 'Point'],
        layout: { 'text-field': ['get', 'label'], 'text-font': ['Noto Sans Bold'], 'text-size': 12, 'text-offset': [0, 1.5], 'text-anchor': 'top', 'text-allow-overlap': true },
        paint: { 'text-color': '#fff2d1', 'text-halo-color': '#211a2a', 'text-halo-width': 2 }
      });

      // Lane geometry is fetched for the visible viewport because the standard
      // OpenMapTiles transportation layer does not reliably retain OSM lane tags.
      // Only ways with an explicit lanes tag are drawn; unknown lane counts stay unknown.
      if (!activeMap.getSource('4r-live-lane-markings')) {
        activeMap.addSource('4r-live-lane-markings', {
          type: 'geojson',
          data: { type: 'FeatureCollection', features: [] }
        });
      }
      activeMap.addLayer({
        id: '4r-live-lane-road-widths',
        type: 'line',
        source: '4r-live-lane-markings',
        filter: ['==', ['get', 'isRoad'], true],
        minzoom: 13,
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: {
          'line-color': [
            'match', ['get', 'highway'],
            'motorway', '#d8a4c8', 'trunk', '#c59acb',
            'primary', '#e0bd91', 'secondary', '#d2c49c',
            'tertiary', '#b8b0c5', 'residential', '#8f899d',
            'service', '#777181', '#9690a2'
          ],
          'line-width': ['*', ['get', 'lanes'], ['interpolate', ['linear'], ['zoom'], 14, 2.2, 18, 5.5]],
          'line-offset': ['*', ['get', 'roadOffsetIndex'], ['interpolate', ['linear'], ['zoom'], 14, 1.8, 18, 4.5]],
          'line-opacity': 0.98
        }
      });
      activeMap.addLayer({
        id: '4r-live-lane-dividers',
        type: 'line',
        source: '4r-live-lane-markings',
        filter: ['==', ['get', 'isCenter'], false],
        minzoom: 13,
        paint: {
          'line-color': 'rgba(255,255,255,0.86)',
          'line-width': ['interpolate', ['linear'], ['zoom'], 14, 0.7, 18, 1.25],
          'line-offset': ['*', ['get', 'offsetIndex'], ['interpolate', ['linear'], ['zoom'], 14, 1.8, 18, 4.5]],
          'line-dasharray': [2.4, 2.1],
          'line-opacity': 0.9
        }
      });
      activeMap.addLayer({
        id: '4r-live-lane-centerline',
        type: 'line',
        source: '4r-live-lane-markings',
        filter: ['==', ['get', 'isCenter'], true],
        minzoom: 13,
        paint: {
          'line-color': '#f0d38a',
          'line-width': ['interpolate', ['linear'], ['zoom'], 14, 0.9, 18, 1.5],
          'line-offset': ['*', ['get', 'offsetIndex'], ['interpolate', ['linear'], ['zoom'], 14, 1.8, 18, 4.5]],
          'line-opacity': 0.98
        }
      });

      const refreshLiveLaneMarkings = async () => {
        if (activeMap.getZoom() < 13) {
          const source = activeMap.getSource('4r-live-lane-markings') as maplibregl.GeoJSONSource | undefined;
          source?.setData({ type: 'FeatureCollection', features: [] });
          return;
        }
        laneLookupController?.abort();
        const controller = new AbortController();
        laneLookupController = controller;
        const bounds = activeMap.getBounds();
        const south = bounds.getSouth().toFixed(5);
        const west = bounds.getWest().toFixed(5);
        const north = bounds.getNorth().toFixed(5);
        const east = bounds.getEast().toFixed(5);
        const queryText = `[out:json][timeout:15];(way["highway"]["lanes"](${south},${west},${north},${east});way["highway"]["lanes:forward"](${south},${west},${north},${east});way["highway"]["lanes:backward"](${south},${west},${north},${east}););out geom;`;
        try {
          const endpoints = [
            'https://overpass-api.de/api/interpreter',
            'https://overpass.kumi.systems/api/interpreter',
            'https://overpass.private.coffee/api/interpreter'
          ];
          let payload: {
            elements?: Array<{
              type: string;
              id: number;
              tags?: Record<string, string>;
              geometry?: Array<{ lon: number; lat: number }>;
            }>;
          } | null = null;
          let lastError: Error | null = null;
          for (const endpoint of endpoints) {
            if (controller.signal.aborted) return;
            try {
              const response = await fetch(endpoint, {
                method: 'POST',
                headers: { 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' },
                body: `data=${encodeURIComponent(queryText)}`,
                signal: controller.signal
              });
              if (!response.ok) throw new Error(`Lane data request returned ${response.status}`);
              payload = await response.json();
              break;
            } catch (requestError) {
              if (controller.signal.aborted) return;
              lastError = requestError instanceof Error ? requestError : new Error('Lane data request failed');
            }
          }
          if (!payload) throw lastError ?? new Error('All lane data servers are unavailable');
          if (controller.signal.aborted || !activeMap.getSource('4r-live-lane-markings')) return;
          const features: GeoJSON.Feature<GeoJSON.LineString>[] = [];
          for (const way of payload.elements ?? []) {
            const tags = way.tags ?? {};
            const parsedTotal = Number(tags.lanes);
            const parsedForward = Number(tags['lanes:forward']);
            const parsedBackward = Number(tags['lanes:backward']);
            const hasTotal = Number.isInteger(parsedTotal) && parsedTotal >= 2 && parsedTotal <= 8;
            const hasForward = Number.isInteger(parsedForward) && parsedForward >= 1 && parsedForward <= 8;
            const hasBackward = Number.isInteger(parsedBackward) && parsedBackward >= 1 && parsedBackward <= 8;
            const hasBothDirections = hasForward && hasBackward;
            // Use the complete carriageway only when the total or both directional
            // counts are explicit. If OSM records only one direction, draw only that
            // known carriageway on its correct side instead of discarding the road.
            const laneCount = hasBothDirections ? parsedForward + parsedBackward
              : hasTotal ? parsedTotal
              : hasForward ? parsedForward
              : hasBackward ? parsedBackward : 0;
            const partialDirection = !hasBothDirections && !hasTotal && (hasForward || hasBackward);
            const roadOffsetIndex = partialDirection ? (hasForward ? 1 : -1) * laneCount / 2 : 0;
            const geometry = way.geometry ?? [];
            if (!Number.isInteger(laneCount) || laneCount < 2 || laneCount > 8 || geometry.length < 2) continue;
            const coordinates = geometry.map((point) => [point.lon, point.lat] as [number, number]);
            features.push({
              type: 'Feature',
              id: `${way.id}-lane-road`,
              properties: { isRoad: true, lanes: laneCount, roadOffsetIndex, highway: tags.highway ?? 'road' },
              geometry: { type: 'LineString', coordinates }
            });
            // A road with N known lanes has N-1 internal separators. Partial
            // directional records are offset into the known carriageway only.
            for (let divider = 0; divider < laneCount - 1; divider++) {
              const offsetIndex = roadOffsetIndex + divider - (laneCount - 2) / 2;
              const centerOffset = hasBothDirections ? (parsedForward - parsedBackward) / 2 : Number.NaN;
              const isCenter = !partialDirection && Number.isFinite(centerOffset) &&
                Math.abs(offsetIndex - centerOffset) < 0.001;
              features.push({
                type: 'Feature',
                id: `${way.id}-lane-${divider}`,
                properties: { isRoad: false, offsetIndex, isCenter, lanes: laneCount, oneway: tags.oneway ?? 'unknown' },
                geometry: { type: 'LineString', coordinates }
              });
            }
          }
          if (!controller.signal.aborted) {
            const source = activeMap.getSource('4r-live-lane-markings') as maplibregl.GeoJSONSource | undefined;
            source?.setData({ type: 'FeatureCollection', features });
          }
        } catch (error) {
          if (!controller.signal.aborted) {
            console.warn('[4Riders live lane markings]', error);
            // Keep the basemap usable if Overpass is unavailable; never fabricate lanes.
          }
        }
      };
      const scheduleLiveLaneRefresh = () => {
        if (laneRefreshTimer !== null) window.clearTimeout(laneRefreshTimer);
        laneRefreshTimer = window.setTimeout(() => { void refreshLiveLaneMarkings(); }, 450);
      };
      activeMap.on('moveend', scheduleLiveLaneRefresh);
      void refreshLiveLaneMarkings();

      // Road inspection reads only attributes actually supplied by the vector tile.
      activeMap.on('click', (event) => {
        if (pickingCoverageCenterRef.current) {
          const nextCenter: [number, number] = [event.lngLat.lng, event.lngLat.lat];
          coverageCenterRef.current = nextCenter;
          setCoverageCenter(nextCenter);
          setCoverageCenterLabel(`${event.lngLat.lat.toFixed(5)}, ${event.lngLat.lng.toFixed(5)}`);
          setPickingCoverageCenter(false);
          setCoverageSettingsOpen(true);
          setReachStatus('idle');
          setReachMessage('Center updated. Recalculate to refresh the road coverage.');
          setMessage('Coverage center selected on map');
          return;
        }
        const buildingLayerIds = (activeMap.getStyle().layers ?? [])
          .filter((layer) => layer.type === 'fill' && layer.id.toLowerCase().includes('building'))
          .map((layer) => layer.id);
        const buildingHits = buildingLayerIds.length ? activeMap.queryRenderedFeatures(event.point, { layers: buildingLayerIds }) : [];
        const building = buildingHits[0];
        if (building) {
          const p = building.properties ?? {};
          const read = (...keys: string[]) => {
            for (const key of keys) {
              const value = p[key];
              if (value !== undefined && value !== null && String(value).trim() !== '') return String(value).trim();
            }
            return '';
          };
          const name = read('name', 'official_name', 'short_name') || 'Unnamed building';
          // Keep block identification separate from generic OSM references.
          const block = read('block', 'block_name', 'addr:block');
          const taggedAddress = [read('addr:housenumber', 'housenumber'), read('addr:street', 'street'), read('addr:suburb', 'suburb'), read('addr:city', 'addr:town', 'city'), read('addr:postcode', 'postcode'), read('addr:state', 'state')].filter(Boolean).join(', ');
          const details: Array<[string, string]> = [];
          const category = read('building', 'kind', 'kind_detail', 'class') || 'Not recorded';
          if (read('operator')) details.push(['Operator', read('operator')]);
          if (read('brand')) details.push(['Brand', read('brand')]);
          if (read('levels')) details.push(['Floors', read('levels')]);
          if (read('start_date')) details.push(['Built', read('start_date')]);
          const reference = read('ref', 'wikidata', 'osm_id') || (building.id !== undefined ? String(building.id) : 'No reference recorded');
          const selection = { name, address: taggedAddress || 'Address not available in building tile', addressLabel: taggedAddress ? 'Address from map data' : 'Nearby geocoded address', category, block, reference, details };
          setSelectedBuilding(selection);
          setSelectedRoad(null);
          setActivePanel('building');
          // Enrich the tapped footprint from OSM's full way tags. Choose only a
          // polygon that actually contains the tap; nearby buildings are never
          // used as a substitute for the selected footprint.
          const pointInRing = (x: number, y: number, ring: Array<{ lon: number; lat: number }>) => {
            let inside = false;
            for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) {
              const xi = ring[i].lon, yi = ring[i].lat, xj = ring[j].lon, yj = ring[j].lat;
              const intersects = ((yi > y) !== (yj > y)) && (x < ((xj - xi) * (y - yi)) / ((yj - yi) || Number.EPSILON) + xi);
              if (intersects) inside = !inside;
            }
            return inside;
          };
          const enrichBuilding = async () => {
            const queryText = `[out:json][timeout:12];way(around:15,${event.lngLat.lat.toFixed(6)},${event.lngLat.lng.toFixed(6)})["building"];out tags geom;`;
            try {
              const response = await fetch('https://overpass-api.de/api/interpreter', {
                method: 'POST',
                headers: { 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' },
                body: `data=${encodeURIComponent(queryText)}`
              });
              if (!response.ok) throw new Error('Building address lookup unavailable');
              const payload = await response.json() as { elements?: Array<{ id: number; tags?: Record<string, string>; geometry?: Array<{ lon: number; lat: number }> }> };
              const exact = (payload.elements ?? []).find((item) => item.geometry && pointInRing(event.lngLat.lng, event.lngLat.lat, item.geometry));
              if (!exact?.tags) return;
              const tags = exact.tags;
              const value = (...keys: string[]) => keys.map((key) => tags[key]?.trim()).find((v) => v);
              const address = [value('addr:housenumber'), value('addr:street'), value('addr:suburb', 'addr:neighbourhood'), value('addr:city', 'addr:town'), value('addr:postcode'), value('addr:state')].filter(Boolean).join(', ');
              const blockValue = value('addr:block', 'building:part', 'block', 'block_name');
              const extra: Array<[string, string]> = [];
              for (const [label, tag] of [['Operator', 'operator'], ['Brand', 'brand'], ['Floors', 'building:levels'], ['Built', 'start_date']] as const) {
                const v = value(tag);
                if (v) extra.push([label, v]);
              }
              setSelectedBuilding((current) => current?.reference === selection.reference ? {
                ...current,
                name: value('name', 'official_name') || current.name,
                address: address || current.address,
                addressLabel: address ? 'Verified OSM address tags' : current.addressLabel,
                block: blockValue || current.block,
                category: value('building') || current.category,
                reference: `OSM way ${exact.id}`,
                details: extra.length ? extra : current.details
              } : current);
            } catch (error) {
              console.warn('[4Riders building tags]', error);
            }
          };
          void enrichBuilding();
          // The OpenMapTiles building layer often omits full address tags.
          // Reverse geocoding gives nearby street context; it is labelled as such,
          // never presented as a verified unit/building address.
          if (!taggedAddress) {
            const lat = event.lngLat.lat, lon = event.lngLat.lng;
            fetch(`https://photon.komoot.io/reverse?lat=${lat}&lon=${lon}`)
              .then((response) => { if (!response.ok) throw new Error('Reverse geocoder unavailable'); return response.json(); })
              .then((payload: { features?: Array<{ properties?: Record<string, unknown> }> }) => {
                const p = payload.features?.[0]?.properties ?? {};
                const value = (...keys: string[]) => {
                  for (const key of keys) { const v = p[key]; if (typeof v === 'string' && v.trim()) return v.trim(); }
                  return '';
                };
                const nearby = [value('housenumber'), value('street'), value('district'), value('city'), value('postcode'), value('state')].filter(Boolean).join(', ');
                if (nearby) setSelectedBuilding((current) => current?.reference === reference ? { ...current, address: nearby, addressLabel: 'Nearby address · geocoded, not verified for this building' } : current);
              })
              .catch((error) => console.warn('[4Riders building reverse geocode]', error));
          }
          return;
        }
        setSelectedBuilding(null);
        const roadLayerIds = (activeMap.getStyle().layers ?? [])
          .filter((layer) => layer.type === 'line' && (layer.id.toLowerCase().includes('transportation') || layer.id.toLowerCase().includes('road')))
          .map((layer) => layer.id);
        if (!roadLayerIds.length) { setSelectedRoad(null); setActivePanel(null); return; }
        const pad = 9;
        const box: [[number, number], [number, number]] = [
          [event.point.x - pad, event.point.y - pad],
          [event.point.x + pad, event.point.y + pad]
        ];
        const candidates = activeMap.queryRenderedFeatures(box, { layers: roadLayerIds });
        const road = candidates.find((feature) => feature.sourceLayer === 'transportation') ?? candidates[0];
        if (!road) { setSelectedRoad(null); return; }
        const props = road.properties ?? {};
        const nearbyLabels = activeMap.queryRenderedFeatures(box).filter((feature) => feature.sourceLayer === 'transportation_name');
        const label = nearbyLabels.find((feature) => feature.properties?.name || feature.properties?.ref)?.properties;
        const oneWayValue = props.oneway === undefined || props.oneway === null ? null : Number(props.oneway);
        const direction = oneWayValue === 1 ? 'Sehala · ikut arah jalan' :
          oneWayValue === -1 ? 'Sehala · arah bertentangan' :
          oneWayValue === 0 ? 'Dua hala' : 'Maklumat arah tiada';
        const className = String(props.class ?? 'road').replaceAll('_', ' ');
        const displayName = String(label?.name || label?.ref || props.name || props.ref || 'Jalan tanpa nama');
        const selectionKey = `${event.lngLat.lng.toFixed(5)}:${event.lngLat.lat.toFixed(5)}`;
        setSelectedRoad({
          selectionKey,
          lanes: 'Checking OpenStreetMap…',
          name: displayName,
          roadClass: className,
          direction,
          surface: String(props.surface ?? 'Tidak direkodkan'),
          access: String(props.access ?? 'Tiada sekatan direkodkan'),
          structure: String(props.brunnel ?? 'Permukaan biasa')
        });
        setActivePanel('road');
        void lookupRoadTags(event.lngLat.lng, event.lngLat.lat, displayName, selectionKey);
      });
      // Do not call the archive loaded until vector tiles have actually loaded.
      setMessage('PMTiles archive verified · waiting for map tiles…');
      const verifyRenderedTiles = () => {
        if (!map || disposed) return;
        const sourceLoaded = activeMap.isSourceLoaded('openmaptiles');
        if (!sourceLoaded) return;
        const roadFeatures = activeMap.queryRenderedFeatures({ layers: ['roads'] }).length;
        const buildingFeatures = activeMap.queryRenderedFeatures({ layers: ['building-fill'] }).length;
        if (roadFeatures > 0 || buildingFeatures > 0) {
          setMessage(`4Riders map tiles rendered · ${roadFeatures.toLocaleString()} road segments · ${buildingFeatures.toLocaleString()} buildings`);
          setReady(true);
          refreshBuildingCount(map);
          activeMap.off('idle', verifyRenderedTiles);
        }
      };
      activeMap.on('idle', verifyRenderedTiles);
      verifyRenderedTiles();
    });
    activeMap.on('error', (event) => {
      if (event.error) {
        console.error('[4Riders map]', event.error);
        setMessage(`Map tile error · ${event.error.message || 'PMTiles request failed'}`);
        setReady(false);
      }
    });
    };

    void startMap();
    return () => {
      disposed = true;
      if (laneRefreshTimer !== null) window.clearTimeout(laneRefreshTimer);
      laneLookupController?.abort();
      resizeObserver?.disconnect();
      roadLookupAbort.current?.abort();
      map?.remove();
      mapRef.current = null;
    };
  }, []);

  const refreshBuildingCount = (map: MapLibreMap) => {
    try {
      const layers = (map.getStyle().layers ?? [])
        .filter((layer) => layer.id.toLowerCase().includes('building') && layer.type === 'fill')
        .map((layer) => layer.id);
      setBuildingCount(layers.length ? map.queryRenderedFeatures({ layers }).length : null);
      const nameLayer = map.getLayer('4r-building-name-labels')
        ? '4r-building-name-labels'
        : map.getLayer('4r-named-building-labels')
          ? '4r-named-building-labels'
          : null;
      setNamedBuildingCount(nameLayer ? map.queryRenderedFeatures({ layers: [nameLayer] }).length : null);
    } catch {
      setBuildingCount(null);
      setNamedBuildingCount(null);
    }
  };

  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;
    const update = () => refreshBuildingCount(map);
    map.on('moveend', update);
    return () => { map.off('moveend', update); };
  }, [ready]);

  const goHome = useCallback(() => {
    mapRef.current?.fitBounds(KLANG_VALLEY_BOUNDS, { padding: 36, duration: 900 });
    setCenterLabel('Klang Valley');
  }, []);

  const stopGpsTracking = useCallback(() => {
    if (gpsWatchId.current !== null && navigator.geolocation) {
      navigator.geolocation.clearWatch(gpsWatchId.current);
    }
    gpsWatchId.current = null;
    gpsMarkerRef.current?.remove();
    gpsMarkerRef.current = null;
    gpsArrowRef.current = null;
    setGpsTracking(false);
    setMessage('Live GPS tracking stopped');
  }, []);

  const locateMe = useCallback(() => {
    if (!navigator.geolocation) { setMessage('Location is not supported on this device'); return; }
    if (gpsWatchId.current !== null) { stopGpsTracking(); return; }
    setMessage('Starting live GPS… allow location access when prompted.');
    gpsWatchId.current = navigator.geolocation.watchPosition(
      ({ coords, timestamp }) => {
        const position: [number, number] = [coords.longitude, coords.latitude];
        setGpsAccuracy(Math.round(coords.accuracy));
        setGpsTracking(true);
        setCenterLabel('Live GPS position');
        // Render a persistent, custom current-location marker on our own map.
        // The blue centre dot shows the fix; the arrow rotates when the device
        // supplies a reliable GPS course/heading.
        const activeMap = mapRef.current;
        if (activeMap) {
          if (!gpsMarkerRef.current) {
            const markerElement = document.createElement('div');
            markerElement.className = 'user-location-marker';
            markerElement.setAttribute('aria-label', 'Your current location');
            markerElement.innerHTML = '<span class="user-location-pulse"></span><span class="user-location-core"></span><svg class="user-location-arrow" viewBox="0 0 32 32" aria-hidden="true"><path d="M16 2.5 25.5 27 16 21.5 6.5 27Z" /></svg>';
            gpsArrowRef.current = markerElement.querySelector<SVGElement>('.user-location-arrow');
            gpsMarkerRef.current = new maplibregl.Marker({ element: markerElement, anchor: 'center' })
              .setLngLat(position)
              .addTo(activeMap);
          } else {
            gpsMarkerRef.current.setLngLat(position);
          }
          const markerElement = gpsMarkerRef.current.getElement();
          const hasHeading = coords.heading !== null && Number.isFinite(coords.heading);
          markerElement.classList.toggle('has-heading', hasHeading);
          if (hasHeading && gpsArrowRef.current) {
            gpsArrowRef.current.style.transform = `rotate(${coords.heading}deg)`;
          }
        }
        // Follow each reliable fix, but ignore stale or low-quality readings.
        const fresh = timestamp >= lastGpsFixAt.current;
        lastGpsFixAt.current = Math.max(lastGpsFixAt.current, timestamp);
        if (fresh && coords.accuracy <= 100) {
          mapRef.current?.easeTo({ center: position, zoom: Math.max(mapRef.current.getZoom(), 16), duration: 450 });
        }
        setMessage(`Live GPS · accuracy ±${Math.round(coords.accuracy)} m`);
      },
      (error) => {
        const reason = error.code === 1 ? 'Location permission denied' : error.code === 2 ? 'GPS position unavailable' : 'GPS request timed out';
        setMessage(`${reason}. Check device location permission and try again.`);
        if (error.code === 1) stopGpsTracking();
      },
      { enableHighAccuracy: true, timeout: 20000, maximumAge: 2000 }
    );
    setGpsTracking(true);
  }, [stopGpsTracking]);

  useEffect(() => () => {
    if (gpsWatchId.current !== null && navigator.geolocation) navigator.geolocation.clearWatch(gpsWatchId.current);
    gpsMarkerRef.current?.remove();
    gpsMarkerRef.current = null;
    gpsArrowRef.current = null;
  }, []);

  const searchPlaces = async (event: FormEvent) => {
    event.preventDefault();
    if (!query.trim()) return;
    setSearching(true);
    setResults([]);
    try {
      const url = new URL(PHOTON_API);
      url.searchParams.set('q', query.trim());
      url.searchParams.set('lat', String(coverageCenter[1]));
      url.searchParams.set('lon', String(coverageCenter[0]));
      url.searchParams.set('limit', '5');
      const response = await fetch(url);
      if (!response.ok) throw new Error('Search request failed');
      const data = await response.json();
      setResults(data.features ?? []);
      if (!data.features?.length) setMessage('No matching places found');
    } catch { setMessage('Place search is temporarily unavailable'); }
    finally { setSearching(false); }
  };

  const chooseResult = (feature: SearchFeature) => {
    const [lng, lat] = feature.geometry.coordinates;
    const p = feature.properties;
    const placeLabel = [p.name, p.city, p.state].filter(Boolean).join(', ') || 'Selected place';
    mapRef.current?.flyTo({ center: [lng, lat], zoom: 17, duration: 900 });
    if (pickingCoverageCenter) {
      const nextCenter: [number, number] = [lng, lat];
      coverageCenterRef.current = nextCenter;
      setCoverageCenter(nextCenter);
      setCoverageCenterLabel(placeLabel);
      setPickingCoverageCenter(false);
      setReachStatus('idle');
      setReachMessage('Center updated. Recalculate to refresh the road coverage.');
      setMessage('Coverage center selected from place search');
    }
    setCenterLabel(placeLabel);
    setQuery('');
    setResults([]);
  };

  const calculateRoadReach = async () => {
    const map = mapRef.current;
    if (!map || reachStatus === 'loading') return;
    setReachStatus('loading');
    setReachMessage(`Calculating ${coverageRadius} km through the motorcycle road network…`);
    setMessage('Calculating real road-distance coverage…');
    try {
      const requestBody = {
        locations: [{ lat: coverageCenter[1], lon: coverageCenter[0] }],
        costing: 'motorcycle',
        costing_options: { motorcycle: { use_highways: 0.5, use_trails: 0 } },
        contours: [{ distance: coverageRadius, color: 'b58ad8' }],
        polygons: true,
        denoise: 0.1,
        generalize: 35,
        units: 'kilometers',
        show_locations: true
      };
      const response = await fetch(VALHALLA_ISODISTANCE_API, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'Accept': 'application/json' },
        body: JSON.stringify(requestBody)
      });
      if (!response.ok) throw new Error(`Routing service returned ${response.status}`);
      const geojson = await response.json() as {
        type: 'FeatureCollection';
        features?: Array<{ type: 'Feature'; geometry: { type: string; coordinates: unknown }; properties?: Record<string, unknown> }>;
      };
      const polygonFeatures = (geojson.features ?? []).filter((feature) => feature.geometry?.type === 'Polygon' || feature.geometry?.type === 'MultiPolygon');
      if (!polygonFeatures.length) throw new Error('Routing service returned no reachable road area');
      const collection = {
        type: 'FeatureCollection' as const,
        features: [
          ...polygonFeatures,
          { type: 'Feature' as const, geometry: { type: 'Point' as const, coordinates: coverageCenter }, properties: { label: 'COVERAGE CENTER' } }
        ]
      };
      const source = map.getSource('4r-road-reach') as maplibregl.GeoJSONSource | undefined;
      if (!source) throw new Error('Map overlay is not ready yet');
      source.setData(collection as GeoJSON.FeatureCollection);
      const coords: number[][] = [];
      const walk = (value: unknown): void => {
        if (Array.isArray(value) && typeof value[0] === 'number' && typeof value[1] === 'number') coords.push(value as number[]);
        else if (Array.isArray(value)) value.forEach(walk);
      };
      polygonFeatures.forEach((feature) => walk(feature.geometry.coordinates));
      if (coords.length) {
        const bounds = coords.reduce((acc, point) => acc.extend([point[0], point[1]]), new maplibregl.LngLatBounds());
        map.fitBounds(bounds, { padding: { top: 115, right: 70, bottom: 250, left: 55 }, maxZoom: 11.7, duration: 1100 });
      }
      setReachStatus('ready');
      setReachMessage(`${coverageRadius} km road-distance boundary loaded · motorcycle profile`);
      setCenterLabel(`${coverageRadius} km road coverage · ${coverageCenterLabel}`);
      setMessage('Coverage follows connected roads, not a straight-line circle');
    } catch (error) {
      console.error('[4Riders road-distance coverage]', error);
      setReachStatus('error');
      setReachMessage(error instanceof Error ? error.message : 'Unable to calculate road-distance coverage');
      setMessage('Road-distance calculation failed · retry when routing service is available');
    }
  };

  const toggleBuildings = () => {
    const next = !buildingsOn;
    setBuildingsOn(next);
    const map = mapRef.current;
    if (!map) return;
    (map.getStyle().layers ?? []).filter((layer) => layer.id.toLowerCase().includes('building')).forEach((layer) => {
      map.setLayoutProperty(layer.id, 'visibility', next ? 'visible' : 'none');
    });
  };

  return (
    <main className="app-shell">
      <div ref={mapNode} className="map-canvas" />
      {activePanel && <section className="map-tool-panel">
        <div className="map-tool-panel-head"><span>{activePanel === "search" ? "SEARCH PLACES" : activePanel === "route" ? "MOTORCYCLE NAVIGATION" : activePanel === "coverage" ? "ROAD COVERAGE SETTINGS" : activePanel === "road" ? "ROAD DETAILS" : activePanel === "building" ? "BUILDING DETAILS" : "MAP SETTINGS"}</span><button onClick={() => { setActivePanel(null); setPickingCoverageCenter(false); }} aria-label="Close options"><X size={18}/></button></div>
        {activePanel === "search" && <><form className="dock-search-form" onSubmit={searchPlaces}><Search size={18}/><input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="Search places, roads or landmarks" aria-label="Search map"/>{query && <button type="button" onClick={() => { setQuery(""); setResults([]); }} aria-label="Clear search"><X size={15}/></button>}<button type="submit" disabled={searching}>{searching ? "…" : "Search"}</button></form>{results.length > 0 && <div className="dock-search-results">{results.map((feature, i) => <button type="button" key={i} onClick={() => { chooseResult(feature); setActivePanel(null); }}><MapPin size={15}/><span><b>{feature.properties.name || "Unnamed place"}</b><small>{[feature.properties.street, feature.properties.city, feature.properties.state].filter(Boolean).join(", ")}</small></span></button>)}</div>}</>}
        {activePanel === "route" && ready && mapRef.current && <RoutePlanner map={mapRef.current} />}
        {activePanel === "route" && !ready && <p className="tool-message">Navigation will be available when the map is ready.</p>}
        {activePanel === "coverage" && <><span className="coverage-setting-label">CENTER POINT</span><strong className="coverage-center-name">{coverageCenterLabel}</strong><small className="coverage-coordinates">{coverageCenter[1].toFixed(5)}, {coverageCenter[0].toFixed(5)}</small><button className={pickingCoverageCenter ? "coverage-pick active" : "coverage-pick"} onClick={() => setPickingCoverageCenter((value) => !value)}>{pickingCoverageCenter ? "Tap map to set center…" : "Choose center on map"}</button><span className="coverage-setting-label">ROAD DISTANCE · KM</span><div className="coverage-radius-row"><input type="number" min="1" max="100" step="1" value={coverageRadius} onChange={(event) => setCoverageRadius(Math.max(1, Math.min(100, Number(event.target.value) || 1)))}/><input type="range" min="1" max="100" step="1" value={coverageRadius} onChange={(event) => setCoverageRadius(Number(event.target.value))}/></div><div className="coverage-presets">{[10,15,20,25,30,40,50].map((value) => <button key={value} className={coverageRadius === value ? "selected" : ""} onClick={() => setCoverageRadius(value)}>{value}</button>)}</div><button className="coverage-apply" onClick={calculateRoadReach} disabled={reachStatus === "loading"}>{reachStatus === "loading" ? "Calculating…" : "Apply & calculate coverage"}</button><small className="coverage-footnote">Motorcycle road-network distance, not a straight-line circle. Maximum 100 km.</small>{reachMessage && <p className="tool-message">{reachMessage}</p>}</>}
        {activePanel === "layers" && <><div className="dock-setting-row"><div><b>Building footprints</b><small>Show or hide 2D building outlines</small></div><button className={buildingsOn ? "dock-toggle on" : "dock-toggle"} onClick={toggleBuildings}>{buildingsOn ? "ON" : "OFF"}</button></div><div className="dock-setting-row"><div><b>Map zoom</b><small>Adjust map scale</small></div><div className="dock-zoom"><button onClick={() => mapRef.current?.zoomOut()} aria-label="Zoom out"><Minus size={17}/></button><button onClick={() => mapRef.current?.zoomIn()} aria-label="Zoom in"><Plus size={17}/></button></div></div><div className="dock-setting-row"><div><b>Map data</b><small>4Riders Klang Valley · OpenStreetMap</small></div><span className={ready ? "status-dot ready" : "status-dot"}/></div></>}
        {activePanel === "building" && selectedBuilding && <div className="building-inspector"><div className="building-inspector-title"><span className="building-inspector-icon">⌂</span><div><strong>{selectedBuilding.name}</strong><small>{selectedBuilding.category}</small></div></div><div className="building-inspector-row"><span>Complex / building</span><b>{selectedBuilding.name}</b></div><div className="building-inspector-row"><span>Block</span><b>{selectedBuilding.block || "Not recorded separately"}</b></div><div className="building-inspector-row"><span>{selectedBuilding.addressLabel}</span><b>{selectedBuilding.address}</b></div>{selectedBuilding.details.map(([label, value]) => <div className="building-inspector-row" key={label}><span>{label}</span><b>{value}</b></div>)}<div className="building-inspector-foot">Map reference: {selectedBuilding.reference}. Block and unit details appear only when the source data contains them.</div></div>}{activePanel === "road" && selectedRoad && <><strong className="coverage-center-name">{selectedRoad.name}</strong><div className="road-tags"><span>{selectedRoad.roadClass}</span><span>{selectedRoad.direction}</span></div><div className="road-detail-grid"><div><span>Surface</span><b>{selectedRoad.surface}</b></div><div><span>Access</span><b>{selectedRoad.access}</b></div><div><span>Structure</span><b>{selectedRoad.structure}</b></div><div><span>Lane count</span><b>{selectedRoad.lanes}</b></div></div></>}
      </section>}
      <nav className="bottom-dock" aria-label="Map tools">
        <button className={activePanel === "search" ? "selected" : ""} onClick={() => setActivePanel(activePanel === "search" ? null : "search")}><Search size={19}/><span>Search</span></button>
        <button className={activePanel === "route" ? "selected" : ""} onClick={() => setActivePanel(activePanel === "route" ? null : "route")}><Route size={19}/><span>Route</span></button>
        <button className={activePanel === "coverage" ? "selected" : ""} onClick={() => setActivePanel(activePanel === "coverage" ? null : "coverage")}><Crosshair size={19}/><span>Coverage</span></button>
        <button className={activePanel === "layers" ? "selected" : ""} onClick={() => setActivePanel(activePanel === "layers" ? null : "layers")}><Layers3 size={19}/><span>Layers</span></button>
        <button className={gpsTracking ? "selected" : ""} onClick={() => { setActivePanel(null); locateMe(); }}><LocateFixed size={19}/><span>{gpsTracking ? "GPS On" : "Locate"}</span></button>
        <button onClick={() => { setActivePanel(null); goHome(); }}><Home size={19}/><span>Home</span></button>
      </nav>
    </main>
  );
}