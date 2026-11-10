import { useEffect, useRef, useState } from 'react';
import * as maplibregl from 'maplibre-gl';
import type { Map as MapLibreMap } from 'maplibre-gl';

type Props = { map: MapLibreMap };
type LngLat = { lat: number; lon: number };
type Maneuver = { instruction?: string; length?: number; time?: number; type?: number };
type RouteResponse = {
  trip?: {
    summary?: { length?: number; time?: number };
    legs?: Array<{ shape?: string; maneuvers?: Maneuver[] }>;
  };
  error?: string;
  error_code?: number;
};

const ROUTE_SOURCE = '4r-motorcycle-route';
const ROUTE_LAYER = '4r-motorcycle-route-line';
const VALHALLA_URL = 'https://valhalla1.openstreetmap.de/route';

function decodePolyline6(encoded: string): [number, number][] {
  let index = 0, lat = 0, lon = 0;
  const points: [number, number][] = [];
  const read = () => {
    let result = 0, shift = 0, byte = 0;
    do {
      if (index >= encoded.length) throw new Error('Invalid route geometry');
      byte = encoded.charCodeAt(index++) - 63;
      result |= (byte & 0x1f) << shift;
      shift += 5;
    } while (byte >= 0x20);
    return (result & 1) ? ~(result >> 1) : (result >> 1);
  };
  while (index < encoded.length) {
    lat += read();
    lon += read();
    points.push([lon / 1e6, lat / 1e6]);
  }
  return points;
}

function getCurrentPosition(): Promise<LngLat> {
  return new Promise((resolve, reject) => {
    if (!navigator.geolocation) return reject(new Error('GPS is not supported on this device'));
    navigator.geolocation.getCurrentPosition(
      ({ coords }) => resolve({ lat: coords.latitude, lon: coords.longitude }),
      () => reject(new Error('GPS permission unavailable. Enable location permission and try again.')),
      { enableHighAccuracy: true, timeout: 15000, maximumAge: 5000 }
    );
  });
}

export default function RoutePlanner({ map }: Props) {
  const [picking, setPicking] = useState(false);
  const [destination, setDestination] = useState<LngLat | null>(null);
  const [busy, setBusy] = useState(false);
  const [status, setStatus] = useState('Choose a destination on the map');
  const [summary, setSummary] = useState<{ km: number; minutes: number } | null>(null);
  const [maneuvers, setManeuvers] = useState<Maneuver[]>([]);
  const destinationMarker = useRef<maplibregl.Marker | null>(null);
  const abortRef = useRef<AbortController | null>(null);

  useEffect(() => {
    if (!map.isStyleLoaded()) return;
    if (!map.getSource(ROUTE_SOURCE)) {
      map.addSource(ROUTE_SOURCE, {
        type: 'geojson',
        data: { type: 'FeatureCollection', features: [] }
      });
    }
    if (!map.getLayer(ROUTE_LAYER)) {
      map.addLayer({
        id: ROUTE_LAYER,
        type: 'line',
        source: ROUTE_SOURCE,
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: {
          'line-color': '#c6a0f6',
          'line-width': ['interpolate', ['linear'], ['zoom'], 10, 3, 16, 6, 19, 8],
          'line-opacity': 0.96
        }
      });
    }
    return () => {
      abortRef.current?.abort();
      destinationMarker.current?.remove();
      if (map.getLayer(ROUTE_LAYER)) map.removeLayer(ROUTE_LAYER);
      if (map.getSource(ROUTE_SOURCE)) map.removeSource(ROUTE_SOURCE);
    };
  }, [map]);

  useEffect(() => {
    if (!picking) return;
    const onMapClick = (event: maplibregl.MapMouseEvent) => {
      const point = { lat: event.lngLat.lat, lon: event.lngLat.lng };
      setDestination(point);
      setSummary(null);
      setManeuvers([]);
      setStatus('Destination selected · ready to calculate');
      setPicking(false);
      destinationMarker.current?.remove();
      destinationMarker.current = new maplibregl.Marker({ color: '#c6a0f6' })
        .setLngLat([point.lon, point.lat]).addTo(map);
    };
    map.getCanvas().style.cursor = 'crosshair';
    map.on('click', onMapClick);
    return () => {
      map.off('click', onMapClick);
      map.getCanvas().style.cursor = '';
    };
  }, [map, picking]);

  const calculateRoute = async () => {
    if (!destination) return;
    setBusy(true);
    setStatus('Getting GPS location…');
    setSummary(null);
    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;
    try {
      const origin = await getCurrentPosition();
      setStatus('Calculating motorcycle route…');
      const response = await fetch(VALHALLA_URL, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        signal: controller.signal,
        body: JSON.stringify({
          locations: [origin, destination],
          costing: 'motorcycle',
          costing_options: { motorcycle: { use_highways: 0.5, use_trails: 0 } },
          units: 'kilometers',
          language: 'en-US',
          directions_options: { units: 'kilometers' }
        })
      });
      if (!response.ok) throw new Error(response.status === 429
        ? 'Routing service is busy. Try again shortly.'
        : 'Routing service is temporarily unavailable.');
      const data = await response.json() as RouteResponse;
      const leg = data.trip?.legs?.[0];
      if (!leg?.shape || !data.trip?.summary) throw new Error(data.error || 'No motorcycle route found for these points.');
      const coordinates = decodePolyline6(leg.shape);
      const source = map.getSource(ROUTE_SOURCE) as maplibregl.GeoJSONSource | undefined;
      if (!source) throw new Error('Map route layer is not ready. Try again.');
      source.setData({
        type: 'FeatureCollection',
        features: [{
          type: 'Feature',
          properties: {},
          geometry: { type: 'LineString', coordinates }
        }]
      });
      setSummary({
        km: data.trip.summary.length ?? 0,
        minutes: Math.max(1, Math.round((data.trip.summary.time ?? 0) / 60))
      });
      setManeuvers((leg.maneuvers ?? []).slice(0, 6));
      setStatus('Motorcycle route ready · preview only');
      const bounds = coordinates.reduce(
        (b, point) => b.extend(point as [number, number]),
        new maplibregl.LngLatBounds(coordinates[0], coordinates[0])
      );
      map.fitBounds(bounds, { padding: { top: 170, right: 48, bottom: 300, left: 48 }, maxZoom: 16.5, duration: 850 });
    } catch (error) {
      if (!controller.signal.aborted) setStatus(error instanceof Error ? error.message : 'Could not calculate route');
    } finally {
      if (!controller.signal.aborted) setBusy(false);
    }
  };

  const clearRoute = () => {
    abortRef.current?.abort();
    setDestination(null);
    setSummary(null);
    setManeuvers([]);
    setStatus('Choose a destination on the map');
    destinationMarker.current?.remove();
    destinationMarker.current = null;
    const source = map.getSource(ROUTE_SOURCE) as maplibregl.GeoJSONSource | undefined;
    source?.setData({ type: 'FeatureCollection', features: [] });
  };

  return (
    <section className="route-planner">
      <div className="route-planner-heading">
        <div><span className="route-eyebrow">4RIDERS NAVIGATION</span><strong>Motorcycle route</strong></div>
        <span className="route-beta">BETA</span>
      </div>
      <div className="route-destination">
        <span className="route-pin" />
        <span>{destination ? `${destination.lat.toFixed(5)}, ${destination.lon.toFixed(5)}` : 'Destination not selected'}</span>
      </div>
      <div className="route-actions">
        <button type="button" className={picking ? 'route-pick active' : 'route-pick'} onClick={() => { setPicking(!picking); setStatus(picking ? 'Choose a destination on the map' : 'Tap the map to set your destination'); }}>
          {picking ? 'Cancel selection' : 'Pick destination'}
        </button>
        <button type="button" className="route-go" disabled={!destination || busy} onClick={() => void calculateRoute()}>
          {busy ? 'Calculating…' : 'Plan route'}
        </button>
      </div>
      <p className="route-status">{status}</p>
      {summary && <div className="route-summary">
        <div><strong>{summary.km.toFixed(1)} km</strong><span>Distance</span></div>
        <div><strong>{summary.minutes} min</strong><span>Estimated time</span></div>
      </div>}
      {maneuvers.length > 0 && <ol className="route-maneuvers">
        {maneuvers.map((item, index) => <li key={index}>{item.instruction || 'Continue'}</li>)}
      </ol>}
      {summary && <button type="button" className="route-clear" onClick={clearRoute}>Clear route</button>}
      <small className="route-disclaimer">OSM-based routing · estimated time · no live traffic or automatic rerouting</small>
    </section>
  );
}
