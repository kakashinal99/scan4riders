import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react';
import * as maplibregl from 'maplibre-gl';
import { PMTiles, Protocol } from 'pmtiles';
import { buildMapStyle } from './mapStyle';
import RoutePlanner from './RoutePlanner';
import type { Map as MapLibreMap } from 'maplibre-gl';
import { ChevronDown, ChevronUp, Crosshair, Layers3, MapPin, Minus, Plus, Search, X } from 'lucide-react';

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

export default function App() {
  const mapNode = useRef<HTMLDivElement>(null);
  const mapRef = useRef<MapLibreMap | null>(null);
  const [ready, setReady] = useState(false);
  const [expanded, setExpanded] = useState(false);
  const [sheetCollapsed, setSheetCollapsed] = useState(false);
  const [topControlsCollapsed, setTopControlsCollapsed] = useState(true);
  const [buildingsOn, setBuildingsOn] = useState(true);
  const [buildingCount, setBuildingCount] = useState<number | null>(null);
  const [namedBuildingCount, setNamedBuildingCount] = useState<number | null>(null);
  const roadLookupAbort = useRef<AbortController | null>(null);
  const [selectedRoad, setSelectedRoad] = useState<RoadSelection | null>(null);
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
        const roadLayerIds = (activeMap.getStyle().layers ?? [])
          .filter((layer) => layer.type === 'line' && (layer.id.toLowerCase().includes('transportation') || layer.id.toLowerCase().includes('road')))
          .map((layer) => layer.id);
        if (!roadLayerIds.length) { setSelectedRoad(null); return; }
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

  const locateMe = useCallback(() => {
    if (!navigator.geolocation) { setMessage('Location is not supported on this device'); return; }
    setMessage('Finding your location…');
    navigator.geolocation.getCurrentPosition(
      ({ coords }) => {
        mapRef.current?.flyTo({ center: [coords.longitude, coords.latitude], zoom: 16.5, duration: 900 });
        setCenterLabel('Your current location');
        setMessage('GPS location found');
      },
      () => setMessage('Location permission unavailable. Enable GPS permission and try again.'),
      { enableHighAccuracy: true, timeout: 12000, maximumAge: 10000 }
    );
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
      <div className={"top-controls" + (topControlsCollapsed ? " collapsed" : "")}>
      {ready && mapRef.current && <RoutePlanner map={mapRef.current} />}
      <header className="topbar">
        <div className="brand-lockup"><div className="brand-mark">4</div><div><strong>4Riders</strong><span>CUSTOM MAP</span></div></div>
        <div className="area-pill"><span className="live-dot" /> KLANG VALLEY</div>
      </header>

      <form className="search-box" onSubmit={searchPlaces}>
        <Search size={18} />
        <input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="Search places, roads or landmarks" aria-label="Search map" />
        {query && <button type="button" className="icon-button clear-button" onClick={() => { setQuery(''); setResults([]); }} aria-label="Clear search"><X size={16}/></button>}
        <button className="search-submit" type="submit" disabled={searching}>{searching ? '…' : 'Search'}</button>
        {results.length > 0 && <div className="search-results">{results.map((feature, i) => <button type="button" key={i} onClick={() => chooseResult(feature)}><MapPin size={15}/><span><b>{feature.properties.name || 'Unnamed place'}</b><small>{[feature.properties.street, feature.properties.city, feature.properties.state].filter(Boolean).join(', ')}</small></span></button>)}</div>}
      </form>
      </div>
      <button className="top-controls-toggle" onClick={() => setTopControlsCollapsed((value) => !value)} aria-expanded={!topControlsCollapsed} aria-label={topControlsCollapsed ? "Show map controls" : "Hide map controls"}>
        <Search size={16}/><span>{topControlsCollapsed ? "Search & route" : "Hide controls"}</span>{topControlsCollapsed ? <ChevronDown size={15}/> : <X size={15}/>}
      </button>

      <div className="map-actions">
        <button className="map-action" onClick={() => setCoverageSettingsOpen((open) => !open)} aria-expanded={coverageSettingsOpen}><Crosshair size={18}/><span>Coverage Settings</span></button>
        <button className={reachStatus === 'ready' ? 'map-action reach-active' : 'map-action reach-action'} onClick={calculateRoadReach} disabled={reachStatus === 'loading'} aria-label={`Calculate ${coverageRadius} kilometre road coverage`}><MapPin size={18}/><span>{reachStatus === 'loading' ? 'Calculating…' : `${coverageRadius} km Road`}</span></button>

        <button className={buildingsOn ? 'map-action active' : 'map-action'} onClick={toggleBuildings} aria-label="Toggle building footprints"><Layers3 size={18}/><span>Buildings</span></button>
        <button className="map-action locate-action" onClick={locateMe} aria-label="Find my location"><Crosshair size={19}/></button>
      </div>
      {coverageSettingsOpen && <section className="coverage-settings">
        <div className="coverage-settings-heading"><b>ROAD COVERAGE SETTINGS</b><button onClick={() => setCoverageSettingsOpen(false)} aria-label="Close coverage settings"><X size={15}/></button></div>
        <span className="coverage-setting-label">CENTER POINT</span>
        <strong className="coverage-center-name">{coverageCenterLabel}</strong>
        <small className="coverage-coordinates">{coverageCenter[1].toFixed(5)}, {coverageCenter[0].toFixed(5)}</small>
        <button className={pickingCoverageCenter ? 'coverage-pick active' : 'coverage-pick'} onClick={() => setPickingCoverageCenter((value) => !value)}>{pickingCoverageCenter ? 'Tap the map to set center…' : 'Choose center on map'}</button>
        <span className="coverage-setting-label">ROAD DISTANCE · KM</span>
        <div className="coverage-radius-row"><input type="number" min="1" max="100" step="1" value={coverageRadius} onChange={(event) => setCoverageRadius(Math.max(1, Math.min(100, Number(event.target.value) || 1)))} /><input type="range" min="1" max="100" step="1" value={coverageRadius} onChange={(event) => setCoverageRadius(Number(event.target.value))} /></div>
        <div className="coverage-presets">{[10, 15, 20, 25, 30, 40, 50].map((value) => <button key={value} className={coverageRadius === value ? 'selected' : ''} onClick={() => setCoverageRadius(value)}>{value}</button>)}</div>
        <button className="coverage-apply" onClick={calculateRoadReach} disabled={reachStatus === 'loading'}>{reachStatus === 'loading' ? 'Calculating…' : 'Apply & calculate coverage'}</button>
        <small className="coverage-footnote">Uses motorcycle road-network distance, not a straight-line circle. Maximum 100 km.</small>
      </section>}
      <button className="home-control" onClick={goHome}><MapPin size={15}/> Klang Valley</button>

      <section className={`bottom-sheet${expanded ? ' expanded' : ''}${sheetCollapsed ? ' collapsed' : ''}`}>
        <button className="sheet-handle" onClick={() => { setSheetCollapsed(false); setExpanded(false); }} aria-label="Show map information panel">
          <span />
        </button>
        <div className="sheet-heading">
          <div><span className="eyebrow">MAP AREA</span><h1>{centerLabel}</h1><p>{message}</p></div>
          <div className="sheet-controls"><button className="sheet-minimize" onClick={() => { setSheetCollapsed(true); setExpanded(false); }} aria-label="Hide map information panel"><ChevronDown size={19}/></button><button className="expand-button" onClick={() => { setSheetCollapsed(false); setExpanded(!expanded); }} aria-label="Expand or collapse panel">{expanded ? <ChevronDown/> : <ChevronUp/>}</button></div>
        </div>
        {reachMessage && <div className="reach-note"><b>{reachStatus === 'ready' ? 'ROAD COVERAGE ACTIVE' : reachStatus === 'loading' ? 'ROUTING CALCULATION' : 'ROAD COVERAGE'}</b><span>{reachMessage}</span></div>}
        {selectedRoad && <div className="road-inspector">

          <div className="road-inspector-top"><span className="eyebrow">ROAD INSPECTOR</span><button type="button" onClick={() => setSelectedRoad(null)} aria-label="Close road details"><X size={15}/></button></div>
          <strong>{selectedRoad.name}</strong>
          <div className="road-tags"><span>{selectedRoad.roadClass}</span><span>{selectedRoad.direction}</span></div>
          <div className="road-detail-grid">
            <div><span>Surface</span><b>{selectedRoad.surface}</b></div>
            <div><span>Access</span><b>{selectedRoad.access}</b></div>
            <div><span>Structure</span><b>{selectedRoad.structure}</b></div>
            <div><span>Lane count</span><b>{selectedRoad.lanes}</b></div>
          </div>
        </div>}
        <div className="quick-stats">
          <div><span className="stat-value">{buildingCount === null ? '—' : buildingCount.toLocaleString()}</span><span className="stat-label">Visible buildings</span></div>
          <div><span className="stat-value">16.2</span><span className="stat-label">Target zoom</span></div>
          <div><span className="stat-value">OSM</span><span className="stat-label">Map data</span></div>
        </div>
        {expanded && <div className="sheet-details">
          <div className="detail-row"><span>Map engine</span><b>MapLibre GL</b></div>
          <div className="detail-row"><span>Vector map source</span><b>4Riders Klang Valley PMTiles</b></div>
          <div className="detail-row"><span>Building geometry</span><b>OpenStreetMap 2D footprints</b></div>
          <div className="detail-row"><span>Named building footprints</span><b>{namedBuildingCount === null ? 'Zoom to street level to load' : namedBuildingCount.toLocaleString()}</b></div>
          <div className="detail-row"><span>Road direction</span><b>Vector tile + raw OSM tags where available</b></div>
          <div className="detail-row"><span>Lane data</span><b>Raw OSM lanes tag when mapped; otherwise marked unavailable</b></div>
          <div className="pilot-note"><b>Current milestone</b><p>Building names are rendered from the vector-tile building layer at street-level zoom. Coverage depends on OSM name tags; lane counts still use a separate OSM tag lookup.</p></div>
        </div>}
        <div className="sheet-footer"><span className={ready ? 'status-dot ready' : 'status-dot'} />{ready ? 'Map renderer ready' : 'Connecting to map data'}<span className="footer-version">4R MAP · 0.1.0</span></div>
      </section>
      <div className="zoom-controls"><button onClick={() => mapRef.current?.zoomIn()} aria-label="Zoom in"><Plus size={18}/></button><button onClick={() => mapRef.current?.zoomOut()} aria-label="Zoom out"><Minus size={18}/></button></div>
    </main>
  );
}