import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react';
import * as maplibregl from 'maplibre-gl';
import type { Map as MapLibreMap } from 'maplibre-gl';
import { ChevronDown, ChevronUp, Crosshair, Layers3, MapPin, Minus, Plus, Search, X } from 'lucide-react';

const KLANG_VALLEY_CENTER: [number, number] = [101.55, 3.105];
const KLANG_VALLEY_BOUNDS: [[number, number], [number, number]] = [[101.20, 2.76], [102.00, 3.62]];
const MAP_STYLE = 'https://tiles.openfreemap.org/styles/dark';
const PHOTON_API = 'https://photon.komoot.io/api/';

type SearchFeature = { geometry: { coordinates: [number, number] }; properties: { name?: string; street?: string; city?: string; state?: string; country?: string } };

export default function App() {
  const mapNode = useRef<HTMLDivElement>(null);
  const mapRef = useRef<MapLibreMap | null>(null);
  const [ready, setReady] = useState(false);
  const [expanded, setExpanded] = useState(false);
  const [buildingsOn, setBuildingsOn] = useState(true);
  const [buildingCount, setBuildingCount] = useState<number | null>(null);
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<SearchFeature[]>([]);
  const [searching, setSearching] = useState(false);
  const [message, setMessage] = useState('Loading Klang Valley map…');
  const [centerLabel, setCenterLabel] = useState('Klang Valley');

  useEffect(() => {
    const container = mapNode.current;
    if (!container || mapRef.current) return;

    const map = new maplibregl.Map({
      container,
      style: MAP_STYLE,
      center: KLANG_VALLEY_CENTER,
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
    map.addControl(new maplibregl.AttributionControl({ compact: true }), 'bottom-right');

    // Mobile browsers can report an incomplete container size on first paint.
    // Observe the actual map element and explicitly resize after layout settles.
    const resizeObserver = new ResizeObserver(() => map.resize());
    resizeObserver.observe(container);
    requestAnimationFrame(() => map.resize());

    map.on('load', () => {
      requestAnimationFrame(() => map.resize());
      const layers = map.getStyle().layers ?? [];
      let count = 0;
      // Keep the road hierarchy legible and visually distinct at every useful zoom.
      layers.forEach((layer) => {
        const id = layer.id.toLowerCase();
        if (layer.type !== 'line' || !(id.includes('transportation') || id.includes('road'))) return;
        try {
          map.setPaintProperty(layer.id, 'line-color', [
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
          map.setPaintProperty(layer.id, 'fill-color', '#76658f');
          map.setPaintProperty(layer.id, 'fill-opacity', 0.82);
          map.setPaintProperty(layer.id, 'fill-outline-color', '#b9a5cd');
        }
        if (layer.type === 'line') {
          map.setPaintProperty(layer.id, 'line-color', '#b4a2c8');
          map.setPaintProperty(layer.id, 'line-opacity', 0.8);
        }
      });
      if (map.getSource('openmaptiles') && !map.getLayer('4r-building-name-labels')) {
        map.addLayer({
          id: '4r-building-name-labels', type: 'symbol', source: 'openmaptiles', 'source-layer': 'building', minzoom: 16,
          filter: ['all', ['has', 'name'], ['!=', ['get', 'name'], '']],
          layout: { 'text-field': ['get', 'name'], 'text-font': ['Noto Sans Regular'], 'text-size': ['interpolate', ['linear'], ['zoom'], 16, 9, 19, 12], 'text-anchor': 'center', 'text-max-width': 7, 'text-allow-overlap': false, 'symbol-placement': 'point' },
          paint: { 'text-color': '#eee4f4', 'text-halo-color': '#332b3d', 'text-halo-width': 1.5, 'text-opacity': 0.95 }
        });
      }
      if (map.getSource('openmaptiles') && !map.getLayer('4r-building-outlines')) {
        map.addLayer({
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
      setMessage(count ? 'Map data loaded' : 'Map loaded · building layer not detected in this style');
      setReady(true);
      refreshBuildingCount(map);
    });
    map.on('error', (event) => {
      if (event.error) {
        console.error('[4Riders map]', event.error);
        setMessage('Map data error · check tile connection');
      }
    });

    return () => {
      resizeObserver.disconnect();
      map.remove();
      mapRef.current = null;
    };
  }, []);

  const refreshBuildingCount = (map: MapLibreMap) => {
    try {
      const layers = (map.getStyle().layers ?? []).filter((layer) => layer.id.toLowerCase().includes('building') && layer.type === 'fill').map((layer) => layer.id);
      if (!layers.length) { setBuildingCount(null); return; }
      setBuildingCount(map.queryRenderedFeatures({ layers }).length);
    } catch { setBuildingCount(null); }
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
      url.searchParams.set('lat', String(KLANG_VALLEY_CENTER[1]));
      url.searchParams.set('lon', String(KLANG_VALLEY_CENTER[0]));
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
    mapRef.current?.flyTo({ center: [lng, lat], zoom: 17, duration: 900 });
    setCenterLabel([p.name, p.city, p.state].filter(Boolean).join(', ') || 'Selected place');
    setQuery('');
    setResults([]);
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

      <div className="map-actions">
        <button className={buildingsOn ? 'map-action active' : 'map-action'} onClick={toggleBuildings} aria-label="Toggle building footprints"><Layers3 size={18}/><span>Buildings</span></button>
        <button className="map-action locate-action" onClick={locateMe} aria-label="Find my location"><Crosshair size={19}/></button>
      </div>
      <button className="home-control" onClick={goHome}><MapPin size={15}/> Klang Valley</button>

      <section className={expanded ? 'bottom-sheet expanded' : 'bottom-sheet'}>
        <button className="sheet-handle" onClick={() => setExpanded(!expanded)} aria-label={expanded ? 'Collapse map panel' : 'Expand map panel'}>
          <span />
        </button>
        <div className="sheet-heading">
          <div><span className="eyebrow">MAP AREA</span><h1>{centerLabel}</h1><p>{message}</p></div>
          <button className="expand-button" onClick={() => setExpanded(!expanded)} aria-label="Expand or collapse panel">{expanded ? <ChevronDown/> : <ChevronUp/>}</button>
        </div>
        <div className="quick-stats">
          <div><span className="stat-value">{buildingCount === null ? '—' : buildingCount.toLocaleString()}</span><span className="stat-label">Visible buildings</span></div>
          <div><span className="stat-value">16.2</span><span className="stat-label">Target zoom</span></div>
          <div><span className="stat-value">OSM</span><span className="stat-label">Map data</span></div>
        </div>
        {expanded && <div className="sheet-details">
          <div className="detail-row"><span>Map engine</span><b>MapLibre GL</b></div>
          <div className="detail-row"><span>Vector map source</span><b>OpenFreeMap</b></div>
          <div className="detail-row"><span>Building geometry</span><b>OpenStreetMap footprints</b></div>
          <div className="pilot-note"><b>Current milestone</b><p>Establish a reliable, zoomable Klang Valley base map. Real hotspot and delivery-zone layers will be connected only after we have verified rider and order data — no fabricated map markers.</p></div>
        </div>}
        <div className="sheet-footer"><span className={ready ? 'status-dot ready' : 'status-dot'} />{ready ? 'Map renderer ready' : 'Connecting to map data'}<span className="footer-version">4R MAP · 0.1.0</span></div>
      </section>
      <div className="zoom-controls"><button onClick={() => mapRef.current?.zoomIn()} aria-label="Zoom in"><Plus size={18}/></button><button onClick={() => mapRef.current?.zoomOut()} aria-label="Zoom out"><Minus size={18}/></button></div>
    </main>
  );
}