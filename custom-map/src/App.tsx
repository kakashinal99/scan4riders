import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react';
import * as maplibregl from 'maplibre-gl';
import { PMTiles, Protocol } from 'pmtiles';
import { buildMapStyle } from './mapStyle';
import RoutePlanner from './RoutePlanner';
import type { Map as MapLibreMap } from 'maplibre-gl';
import { Crosshair, Layers3, MapPin, Minus, Plus, Search, X, Route, LocateFixed, Home } from 'lucide-react';

const SUPABASE_URL = 'https://nuebncvryoxehhnynnbb.supabase.co';
const SUPABASE_PUBLISHABLE_KEY = 'sb_publishable_Z6tTqwY3w21sMYywXT-zYg_hbM0V1oF';
declare global {
  interface Window {
    set4RidersSession?: (accessToken: string, userId: string) => void;
    __4rSessionPending?: { accessToken: string; userId: string };
    set4RidersLocation?: (latitude: number, longitude: number, accuracy?: number, heading?: number | null) => void;
    update4RidersOrderStatus?: (orderId: string, status: 'accepted' | 'rejected' | 'completed' | 'cancelled') => void;
    receive4RidersNativeOrder?: (order: Record<string, unknown>) => void;
    set4RidersNativeScannerState?: (state: 'on' | 'off', detail?: string) => void;
    Android4Riders?: { setMasterScannerEnabled?: (enabled: boolean) => void; setCloudSession?: (accessToken: string, userId: string) => void };
    save4RidersOrder?: (order: {
      external_order_id?: string; fare_amount?: number; rider_to_pickup_m?: number;
      pickup_to_dropoff_m?: number; pickup_label?: string; pickup_lat?: number;
      pickup_lng?: number; pickup_verified?: boolean; dropoff_label?: string; dropoff_lat?: number;
      dropoff_lng?: number; dropoff_verified?: boolean; raw_scan?: Record<string, unknown>;
    }) => void;
  }
}

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


function decodeGooglePolyline(encoded: string): [number, number][] {
  let index = 0, lat = 0, lng = 0;
  const points: [number, number][] = [];
  const read = () => {
    let result = 0, shift = 0, byte = 0;
    do {
      if (index >= encoded.length) throw new Error('Invalid Google route geometry');
      byte = encoded.charCodeAt(index++) - 63;
      result |= (byte & 0x1f) << shift;
      shift += 5;
    } while (byte >= 0x20);
    return (result & 1) ? ~(result >> 1) : (result >> 1);
  };
  while (index < encoded.length) {
    lat += read();
    lng += read();
    points.push([lng / 1e5, lat / 1e5]);
  }
  return points;
}

function decodeCloudRoute(encoded: string): [number, number][] {
  let index = 0, lat = 0, lng = 0;
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
    lng += read();
    points.push([lng / 1e6, lat / 1e6]);
  }
  return points;
}

export default function App() {
  const mapNode = useRef<HTMLDivElement>(null);
  const mapRef = useRef<MapLibreMap | null>(null);
  const pendingCloudOrdersRef = useRef<GeoJSON.FeatureCollection>({ type: 'FeatureCollection', features: [] });
  const pendingCloudRouteRef = useRef<GeoJSON.FeatureCollection>({ type: 'FeatureCollection', features: [] });
  const lastCloudRouteKeyRef = useRef<string>('');
  const cloudSessionRef = useRef<{ accessToken: string; userId: string } | null>(null);
  const nativeGpsRef = useRef<{ latitude: number; longitude: number; accuracy: number; heading: number | null } | null>(null);
  const nativeGpsMarkerRef = useRef<maplibregl.Marker | null>(null);
  const lastNativeGpsCloudWriteAtRef = useRef(0);
  const [ready, setReady] = useState(false);
  const [expanded, setExpanded] = useState(false);
  const [sheetCollapsed, setSheetCollapsed] = useState(true);
  const [activePanel, setActivePanel] = useState<string | null>(null);
  const [gpsTracking, setGpsTracking] = useState(false);
  const [nativeScannerOn, setNativeScannerOn] = useState(false);
  const [nativeScannerMessage, setNativeScannerMessage] = useState('');
  const [gpsAccuracy, setGpsAccuracy] = useState<number | null>(null);
  const [authEmail, setAuthEmail] = useState('');
  const [authPassword, setAuthPassword] = useState('');
  const [authMode, setAuthMode] = useState<'signin' | 'signup'>('signin');
  const [authBusy, setAuthBusy] = useState(false);
  const [authError, setAuthError] = useState('');
  const [cloudUser, setCloudUser] = useState<{ id: string; email: string } | null>(null);

  const persistAuthSession = (session: Record<string, unknown>) => {
    const user = session.user as { id?: string; email?: string } | undefined;
    const accessToken = String(session.access_token ?? '');
    const refreshToken = String(session.refresh_token ?? '');
    if (!user?.id || !accessToken) throw new Error('Supabase returned an incomplete session');
    const expiresAt = Date.now() + Number(session.expires_in ?? 3600) * 1000;
    const saved = { access_token: accessToken, refresh_token: refreshToken, expires_at: expiresAt, user };
    window.localStorage.setItem('4riders.auth.session.v1', JSON.stringify(saved));
    setCloudUser({ id: user.id, email: user.email ?? '' });
    window.Android4Riders?.setCloudSession?.(accessToken, user.id);
    if (window.set4RidersSession) window.set4RidersSession(accessToken, user.id);
    else window.__4rSessionPending = { accessToken, userId: user.id };
  };

  useEffect(() => {
    let cancelled = false;
    const restore = async () => {
      try {
        const raw = window.localStorage.getItem('4riders.auth.session.v1');
        if (!raw) return;
        const saved = JSON.parse(raw) as { access_token?: string; refresh_token?: string; expires_at?: number; user?: { id?: string; email?: string } };
        if (saved.refresh_token && (!saved.expires_at || saved.expires_at < Date.now() + 120000)) {
          const response = await fetch(SUPABASE_URL + '/auth/v1/token?grant_type=refresh_token', {
            method: 'POST', headers: { apikey: SUPABASE_PUBLISHABLE_KEY, 'Content-Type': 'application/json' },
            body: JSON.stringify({ refresh_token: saved.refresh_token })
          });
          if (!response.ok) throw new Error('Session expired. Please sign in again.');
          const next = await response.json();
          if (!cancelled) persistAuthSession(next);
        } else if (saved.access_token && saved.user?.id && !cancelled) {
          setCloudUser({ id: saved.user.id, email: saved.user.email ?? '' });
          window.Android4Riders?.setCloudSession?.(saved.access_token, saved.user.id);
          if (window.set4RidersSession) window.set4RidersSession(saved.access_token, saved.user.id);
          else window.__4rSessionPending = { accessToken: saved.access_token, userId: saved.user.id };
        }
      } catch (error) {
        window.localStorage.removeItem('4riders.auth.session.v1');
        if (!cancelled) setAuthError(error instanceof Error ? error.message : 'Unable to restore login');
      }
    };
    void restore();
    return () => { cancelled = true; };
  }, []);

  useEffect(() => {
    if (!cloudUser) return;
    const timer = window.setInterval(async () => {
      try {
        const raw = window.localStorage.getItem('4riders.auth.session.v1');
        if (!raw) return;
        const saved = JSON.parse(raw);
        if (!saved.refresh_token || saved.expires_at > Date.now() + 180000) return;
        const response = await fetch(SUPABASE_URL + '/auth/v1/token?grant_type=refresh_token', {
          method: 'POST', headers: { apikey: SUPABASE_PUBLISHABLE_KEY, 'Content-Type': 'application/json' },
          body: JSON.stringify({ refresh_token: saved.refresh_token })
        });
        if (!response.ok) throw new Error('Session refresh failed');
        persistAuthSession(await response.json());
      } catch (error) {
        setAuthError(error instanceof Error ? error.message : 'Session refresh failed');
      }
    }, 60000);
    return () => window.clearInterval(timer);
  }, [cloudUser?.id]);

  const submitAuth = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setAuthBusy(true);
    setAuthError('');
    try {
      const endpoint = authMode === 'signin' ? 'token?grant_type=password' : 'signup';
      const response = await fetch(SUPABASE_URL + '/auth/v1/' + endpoint, {
        method: 'POST',
        headers: { apikey: SUPABASE_PUBLISHABLE_KEY, 'Content-Type': 'application/json' },
        body: JSON.stringify({ email: authEmail.trim(), password: authPassword })
      });
      const result = await response.json();
      if (!response.ok) throw new Error(result.msg || result.message || result.error_description || 'Authentication failed');
      if (!result.access_token) {
        setAuthError('Account created. Verify your email, then sign in.');
        setAuthMode('signin');
      } else persistAuthSession(result);
    } catch (error) {
      setAuthError(error instanceof Error ? error.message : 'Unable to sign in');
    } finally {
      setAuthBusy(false);
    }
  };

  const signOutCloud = async () => {
    const raw = window.localStorage.getItem('4riders.auth.session.v1');
    try {
      if (raw) {
        const saved = JSON.parse(raw);
        await fetch(SUPABASE_URL + '/auth/v1/logout', {
          method: 'POST', headers: { apikey: SUPABASE_PUBLISHABLE_KEY, Authorization: 'Bearer ' + saved.access_token }
        });
      }
    } catch { /* Local sign-out still clears the device session. */ }
    window.Android4Riders?.setMasterScannerEnabled?.(false);
    window.Android4Riders?.setCloudSession?.('', '');
    window.localStorage.removeItem('4riders.auth.session.v1');
    cloudSessionRef.current = null;
    setCloudUser(null);
    setNativeScannerOn(false);
    setNativeScannerMessage('Sign in to sync rider data');
  };

  useEffect(() => {
    window.set4RidersNativeScannerState = (state, detail = '') => {
      setNativeScannerOn(state === 'on');
      setNativeScannerMessage(detail);
    };
    return () => { delete window.set4RidersNativeScannerState; };
  }, []);

  const toggleNativeScanner = () => {
    const next = !nativeScannerOn;
    if (window.Android4Riders?.setMasterScannerEnabled) {
      window.Android4Riders.setMasterScannerEnabled(next);
      setNativeScannerMessage(next ? 'Requesting screen-capture permission…' : 'Stopping scanner…');
    } else {
      setNativeScannerMessage('Native scanner is available in the 4Riders Android app');
    }
  };

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
  const [centerSearchQuery, setCenterSearchQuery] = useState('');
  const [centerSearchResults, setCenterSearchResults] = useState<SearchFeature[]>([]);
  const [centerSearching, setCenterSearching] = useState(false);
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


  const loadCloudOrders = useCallback(async (accessToken: string, userId: string) => {
    cloudSessionRef.current = { accessToken, userId };
    try {
      const params = new URLSearchParams({
        select: 'id,status,pickup_label,pickup_lat,pickup_lng,pickup_verified,dropoff_label,dropoff_lat,dropoff_lng,dropoff_verified,scanned_at',
        user_id: `eq.${userId}`,
        order: 'scanned_at.desc',
        limit: '100'
      });
      const response = await fetch(`${SUPABASE_URL}/rest/v1/orders?${params.toString()}`, {
        headers: {
          apikey: SUPABASE_PUBLISHABLE_KEY,
          Authorization: `Bearer ${accessToken}`,
          Accept: 'application/json'
        }
      });
      if (!response.ok) throw new Error(`Cloud orders returned HTTP ${response.status}`);
      const rows = await response.json() as Array<Record<string, unknown>>;
      const features: GeoJSON.Feature<GeoJSON.Point, Record<string, unknown>>[] = [];
      rows.forEach((order) => {
        const status = String(order.status ?? 'detected');
        if (typeof order.pickup_lng === 'number' && typeof order.pickup_lat === 'number') {
          features.push({
            type: 'Feature',
            geometry: { type: 'Point', coordinates: [order.pickup_lng, order.pickup_lat] },
            properties: { orderId: order.id, kind: 'pickup', shortLabel: 'PICKUP', label: order.pickup_label ?? 'Pickup', status, verified: order.pickup_verified === true }
          });
        }
        if (typeof order.dropoff_lng === 'number' && typeof order.dropoff_lat === 'number') {
          features.push({
            type: 'Feature',
            geometry: { type: 'Point', coordinates: [order.dropoff_lng, order.dropoff_lat] },
            properties: { orderId: order.id, kind: 'dropoff', shortLabel: 'DROP', label: order.dropoff_label ?? 'Drop-off', status, verified: order.dropoff_verified === true }
          });
        }
      });
      const collection: GeoJSON.FeatureCollection = { type: 'FeatureCollection', features };
      pendingCloudOrdersRef.current = collection;
      const source = mapRef.current?.getSource('4r-order-locations') as maplibregl.GeoJSONSource | undefined;
      source?.setData(collection);

      const routeOrder = rows.find((order) =>
        typeof order.pickup_lat === 'number' && typeof order.pickup_lng === 'number' &&
        typeof order.dropoff_lat === 'number' && typeof order.dropoff_lng === 'number' &&
        order.pickup_verified === true && order.dropoff_verified === true &&
        (order.status === 'detected' || order.status === 'accepted')
      );
      const nativeGps = nativeGpsRef.current;
      const routeOriginKey = nativeGps ? ':' + nativeGps.latitude.toFixed(3) + ',' + nativeGps.longitude.toFixed(3) : '';
      const routeKey = routeOrder ? String(routeOrder.id) + routeOriginKey : '';
      let routeStatusLabel = '';
      if (!routeKey) {
        lastCloudRouteKeyRef.current = '';
        const emptyRoute: GeoJSON.FeatureCollection = { type: 'FeatureCollection', features: [] };
        pendingCloudRouteRef.current = emptyRoute;
        (mapRef.current?.getSource('4r-cloud-order-route') as maplibregl.GeoJSONSource | undefined)?.setData(emptyRoute);
      }
      if (routeOrder && routeKey && routeKey !== lastCloudRouteKeyRef.current) {
        lastCloudRouteKeyRef.current = routeKey;
        try {
          if (nativeGps && cloudSessionRef.current) {
            const session = cloudSessionRef.current;
            const trafficResponse = await fetch('https://fourriders-pmtiles-proxy.onrender.com/route/traffic', {
              method: 'POST',
              headers: { 'Content-Type': 'application/json', apikey: SUPABASE_PUBLISHABLE_KEY,
                Authorization: 'Bearer ' + session.accessToken },
              body: JSON.stringify({
                origin: { latitude: nativeGps.latitude, longitude: nativeGps.longitude },
                pickup: { latitude: routeOrder.pickup_lat, longitude: routeOrder.pickup_lng },
                dropoff: { latitude: routeOrder.dropoff_lat, longitude: routeOrder.dropoff_lng }
              })
            });
            if (!trafficResponse.ok) throw new Error('Live traffic unavailable (HTTP ' + trafficResponse.status + ')');
            const trafficData = await trafficResponse.json() as {
              distanceMeters?: number; duration?: string; encodedPolyline?: string;
              traffic?: Array<{ startPolylinePointIndex?: number; endPolylinePointIndex?: number; speed?: string }>;
            };
            if (!trafficData.encodedPolyline) throw new Error('Traffic provider returned no route geometry');
            const coordinates = decodeGooglePolyline(trafficData.encodedPolyline);
            const intervals = trafficData.traffic ?? [];
            const features: GeoJSON.Feature<GeoJSON.LineString, Record<string, unknown>>[] = intervals.length
              ? intervals.map((interval) => ({
                  type: 'Feature',
                  properties: { orderId: routeOrder.id, speed: interval.speed ?? 'UNKNOWN' },
                  geometry: { type: 'LineString', coordinates: coordinates.slice(
                    interval.startPolylinePointIndex ?? 0,
                    (interval.endPolylinePointIndex ?? coordinates.length - 1) + 1
                  ) }
                })).filter((feature) => feature.geometry.coordinates.length > 1)
              : [{ type: 'Feature', properties: { orderId: routeOrder.id, speed: 'UNKNOWN' },
                  geometry: { type: 'LineString', coordinates } }];
            const routeCollection: GeoJSON.FeatureCollection = { type: 'FeatureCollection', features };
            pendingCloudRouteRef.current = routeCollection;
            (mapRef.current?.getSource('4r-cloud-order-route') as maplibregl.GeoJSONSource | undefined)?.setData(routeCollection);
            const jam = intervals.filter((item) => item.speed === 'TRAFFIC_JAM').length;
            const slow = intervals.filter((item) => item.speed === 'SLOW').length;
            const trafficLabel = jam ? 'Heavy traffic' : slow ? 'Slow traffic' : intervals.length ? 'Normal traffic' : 'Traffic status unavailable';
            const minutes = Math.max(1, Math.round(Number.parseFloat(trafficData.duration ?? '0s') / 60));
            routeStatusLabel = 'Live traffic · ' + trafficLabel + ' · ETA ' + minutes + ' min';
            setMessage('Live traffic · ' + trafficLabel + ' · ETA ' + minutes + ' min');
            if (coordinates.length > 1 && mapRef.current) {
              const bounds = coordinates.reduce((acc, point) => acc.extend(point), new maplibregl.LngLatBounds(coordinates[0], coordinates[0]));
              mapRef.current.fitBounds(bounds, { padding: { top: 90, right: 55, bottom: 190, left: 55 }, maxZoom: 16, duration: 850 });
            }
          } else {
            throw new Error('Waiting for rider GPS before traffic-aware routing');
          }
        } catch (trafficError) {
          console.warn('[4Riders live traffic route]', trafficError);
          try {
            const routeResponse = await fetch('https://valhalla1.openstreetmap.de/route', {
              method: 'POST',
              headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
              body: JSON.stringify({
                locations: [
                  ...(nativeGps ? [{ lat: nativeGps.latitude, lon: nativeGps.longitude }] : []),
                  { lat: routeOrder.pickup_lat, lon: routeOrder.pickup_lng },
                  { lat: routeOrder.dropoff_lat, lon: routeOrder.dropoff_lng }
                ],
                costing: 'motorcycle',
                costing_options: { motorcycle: { use_highways: 0.5, use_trails: 0 } },
                units: 'kilometers',
                language: 'en-US'
              })
            });
            if (!routeResponse.ok) throw new Error('Motorcycle routing service returned HTTP ' + routeResponse.status);
            const routeData = await routeResponse.json() as { trip?: { legs?: Array<{ shape?: string }> } };
            const shapes = (routeData.trip?.legs ?? []).map((leg) => leg.shape).filter((shape): shape is string => Boolean(shape));
            if (!shapes.length) throw new Error('No motorcycle route returned for this order');
            const coordinates = shapes.flatMap((shape, index) => {
              const legCoordinates = decodeCloudRoute(shape);
              return index ? legCoordinates.slice(1) : legCoordinates;
            });
            const routeCollection: GeoJSON.FeatureCollection = {
              type: 'FeatureCollection',
              features: [{ type: 'Feature', properties: { orderId: routeOrder.id, speed: 'UNKNOWN' },
                geometry: { type: 'LineString', coordinates } }]
            };
            pendingCloudRouteRef.current = routeCollection;
            (mapRef.current?.getSource('4r-cloud-order-route') as maplibregl.GeoJSONSource | undefined)?.setData(routeCollection);
            routeStatusLabel = 'Motorcycle route ready · live traffic unavailable';
            if (coordinates.length > 1 && mapRef.current) {
              const bounds = coordinates.reduce((acc, point) => acc.extend(point), new maplibregl.LngLatBounds(coordinates[0], coordinates[0]));
              mapRef.current.fitBounds(bounds, { padding: { top: 90, right: 55, bottom: 190, left: 55 }, maxZoom: 16, duration: 850 });
            }
          } catch (routeError) {
            console.warn('[4Riders cloud order route]', routeError);
            lastCloudRouteKeyRef.current = '';
          }
        }
      }

      setMessage(routeStatusLabel || `Cloud orders loaded · ${rows.length} records · ${features.length} mapped stops`);
    } catch (error) {
      console.warn('[4Riders cloud orders]', error);
      setMessage('Cloud order sync unavailable · local map remains available');
    }
  }, []);

  useEffect(() => {
    const applySession = (accessToken: string, userId: string) => {
      void loadCloudOrders(accessToken, userId);
    };
    window.set4RidersSession = applySession;
    window.set4RidersLocation = (latitude, longitude, accuracy = 25, heading = null) => {
      if (!Number.isFinite(latitude) || !Number.isFinite(longitude) || Math.abs(latitude) > 90 || Math.abs(longitude) > 180) return;
      nativeGpsRef.current = { latitude, longitude, accuracy, heading };
      const position: [number, number] = [longitude, latitude];
      const activeMap = mapRef.current;
      if (activeMap) {
        if (!nativeGpsMarkerRef.current) {
          const element = document.createElement('div');
          element.className = 'user-location-marker';
          element.setAttribute('aria-label', 'Your current location');
          element.innerHTML = '<span class="user-location-pulse"></span><span class="user-location-core"></span>';
          nativeGpsMarkerRef.current = new maplibregl.Marker({ element, anchor: 'center' }).setLngLat(position).addTo(activeMap);
        } else nativeGpsMarkerRef.current.setLngLat(position);
      }
      setGpsAccuracy(Math.round(accuracy));
      setGpsTracking(true);
      const session = cloudSessionRef.current;
      const now = Date.now();
      if (session && now - lastNativeGpsCloudWriteAtRef.current >= 15000) {
        lastNativeGpsCloudWriteAtRef.current = now;
        void fetch(SUPABASE_URL + '/rest/v1/gps_points', {
          method: 'POST',
          headers: { apikey: SUPABASE_PUBLISHABLE_KEY, Authorization: 'Bearer ' + session.accessToken,
            'Content-Type': 'application/json', Prefer: 'return=minimal' },
          body: JSON.stringify({ user_id: session.userId, latitude, longitude,
            accuracy_m: accuracy, heading_deg: heading, speed_mps: null })
        }).catch((error) => console.warn('[4Riders GPS cloud sync]', error));
      }
      setCenterLabel('Live GPS position');
    };
    window.update4RidersOrderStatus = (orderId, status) => {
      const session = cloudSessionRef.current;
      if (!session || !orderId) return;
      const timestampField = status === 'accepted' ? 'accepted_at' : status === 'completed' ? 'completed_at' : null;
      const patch: Record<string, unknown> = { status, updated_at: new Date().toISOString() };
      if (timestampField) patch[timestampField] = new Date().toISOString();
      void fetch(SUPABASE_URL + '/rest/v1/orders?id=eq.' + encodeURIComponent(orderId) + '&user_id=eq.' + encodeURIComponent(session.userId), {
        method: 'PATCH',
        headers: { apikey: SUPABASE_PUBLISHABLE_KEY, Authorization: 'Bearer ' + session.accessToken,
          'Content-Type': 'application/json', Prefer: 'return=minimal' },
        body: JSON.stringify(patch)
      }).then(async (response) => {
        if (!response.ok) throw new Error('Order status sync failed (HTTP ' + response.status + ')');
        await loadCloudOrders(session.accessToken, session.userId);
      }).catch((error) => console.warn('[4Riders order status bridge]', error));
    };
    window.receive4RidersNativeOrder = (rawOrder) => {
      const numberOrUndefined = (value: unknown): number | undefined => {
        if (typeof value === 'number' && Number.isFinite(value)) return value;
        if (typeof value === 'string' && value.trim() && Number.isFinite(Number(value))) return Number(value);
        return undefined;
      };
      const textOrUndefined = (value: unknown): string | undefined =>
        typeof value === 'string' && value.trim() ? value.trim() : undefined;
      const pickup = rawOrder.pickup && typeof rawOrder.pickup === 'object' ? rawOrder.pickup as Record<string, unknown> : {};
      const dropoff = rawOrder.dropoff && typeof rawOrder.dropoff === 'object' ? rawOrder.dropoff as Record<string, unknown> : {};
      const order = {
        external_order_id: textOrUndefined(rawOrder.external_order_id ?? rawOrder.orderId),
        fare_amount: numberOrUndefined(rawOrder.fare_amount ?? rawOrder.fare),
        rider_to_pickup_m: numberOrUndefined(rawOrder.rider_to_pickup_m ?? rawOrder.riderToPickupMeters) ?? (numberOrUndefined(rawOrder.rider_to_pickup_km) !== undefined ? numberOrUndefined(rawOrder.rider_to_pickup_km)! * 1000 : undefined),
        pickup_to_dropoff_m: numberOrUndefined(rawOrder.pickup_to_dropoff_m ?? rawOrder.pickupToDropoffMeters) ?? (numberOrUndefined(rawOrder.pickup_to_dropoff_km) !== undefined ? numberOrUndefined(rawOrder.pickup_to_dropoff_km)! * 1000 : undefined),
        pickup_label: textOrUndefined(rawOrder.pickup_label ?? pickup.address ?? pickup.label),
        pickup_lat: numberOrUndefined(rawOrder.pickup_lat ?? pickup.latitude ?? pickup.lat),
        pickup_lng: numberOrUndefined(rawOrder.pickup_lng ?? pickup.longitude ?? pickup.lng ?? pickup.lon),
        pickup_verified: rawOrder.pickup_verified === true || pickup.verified === true,
        dropoff_label: textOrUndefined(rawOrder.dropoff_label ?? dropoff.address ?? dropoff.label),
        dropoff_lat: numberOrUndefined(rawOrder.dropoff_lat ?? dropoff.latitude ?? dropoff.lat),
        dropoff_lng: numberOrUndefined(rawOrder.dropoff_lng ?? dropoff.longitude ?? dropoff.lng ?? dropoff.lon),
        dropoff_verified: rawOrder.dropoff_verified === true || dropoff.verified === true,
        raw_scan: rawOrder
      };
      if (!order.fare_amount && !order.pickup_label && !order.dropoff_label) {
        setMessage('Scanner data incomplete — order was not saved');
        return;
      }
      const geocode = async (label: string | undefined): Promise<{ lat?: number; lng?: number }> => {
        if (!label || label.trim().length < 8 || label.trim().split(/\\s+/).length < 2) return {};
        try {
          const params = new URLSearchParams({ q: label + ', Selangor, Malaysia', limit: '1', lang: 'en' });
          const response = await fetch(PHOTON_API + '?' + params.toString(), { headers: { Accept: 'application/json' } });
          if (!response.ok) return {};
          const result = await response.json() as { features?: Array<{ geometry?: { coordinates?: number[] }; properties?: { country?: string } }> };
          const feature = result.features?.[0];
          const coordinates = feature?.geometry?.coordinates;
          if (!coordinates || coordinates.length < 2 || feature?.properties?.country !== 'Malaysia') return {};
          return { lng: coordinates[0], lat: coordinates[1] };
        } catch { return {}; }
      };
      void (async () => {
        if (order.pickup_label && (order.pickup_lat === undefined || order.pickup_lng === undefined)) {
          const point = await geocode(order.pickup_label);
          order.pickup_lat = point.lat;
          order.pickup_lng = point.lng;
          order.pickup_verified = false;
        }
        if (order.dropoff_label && (order.dropoff_lat === undefined || order.dropoff_lng === undefined)) {
          const point = await geocode(order.dropoff_label);
          order.dropoff_lat = point.lat;
          order.dropoff_lng = point.lng;
          order.dropoff_verified = false;
        }
        window.save4RidersOrder?.(order);
      })();
    };
    window.save4RidersOrder = (order) => {
      const session = cloudSessionRef.current;
      if (!session) { setMessage('Sign in required before saving a scanned order'); return; }
      const payload = {
        user_id: session.userId, source: 'grab', status: 'detected',
        external_order_id: order.external_order_id ?? null,
        fare_amount: Number.isFinite(order.fare_amount) ? order.fare_amount : null,
        rider_to_pickup_m: Number.isFinite(order.rider_to_pickup_m) ? order.rider_to_pickup_m : null,
        pickup_to_dropoff_m: Number.isFinite(order.pickup_to_dropoff_m) ? order.pickup_to_dropoff_m : null,
        pickup_label: order.pickup_label ?? null,
        pickup_lat: Number.isFinite(order.pickup_lat) ? order.pickup_lat : null,
        pickup_lng: Number.isFinite(order.pickup_lng) ? order.pickup_lng : null,
        pickup_verified: order.pickup_verified === true && Number.isFinite(order.pickup_lat) && Number.isFinite(order.pickup_lng),
        dropoff_label: order.dropoff_label ?? null,
        dropoff_lat: Number.isFinite(order.dropoff_lat) ? order.dropoff_lat : null,
        dropoff_lng: Number.isFinite(order.dropoff_lng) ? order.dropoff_lng : null,
        dropoff_verified: order.dropoff_verified === true && Number.isFinite(order.dropoff_lat) && Number.isFinite(order.dropoff_lng),
        raw_scan: order.raw_scan ?? {}
      };
      void fetch(SUPABASE_URL + '/rest/v1/orders', {
        method: 'POST',
        headers: { apikey: SUPABASE_PUBLISHABLE_KEY, Authorization: 'Bearer ' + session.accessToken,
          'Content-Type': 'application/json', Prefer: 'return=minimal' },
        body: JSON.stringify(payload)
      }).then(async (response) => {
        if (!response.ok) throw new Error('Cloud order save failed (HTTP ' + response.status + ')');
        setMessage('Scanned order saved to cloud');
        await loadCloudOrders(session.accessToken, session.userId);
      }).catch((error) => {
        console.error('[4Riders native order bridge]', error);
        setMessage(error instanceof Error ? error.message : 'Cloud order save failed');
      });
    };
    if (window.__4rSessionPending) {
      applySession(window.__4rSessionPending.accessToken, window.__4rSessionPending.userId);
      delete window.__4rSessionPending;
    }
    return () => {
      delete window.set4RidersSession;
      delete window.set4RidersLocation;
      delete window.receive4RidersNativeOrder;
      delete window.save4RidersOrder;
      delete window.update4RidersOrderStatus;
    };
  }, [loadCloudOrders]);

  useEffect(() => {
    const poll = window.setInterval(() => {
      const session = cloudSessionRef.current;
      if (session) void loadCloudOrders(session.accessToken, session.userId);
    }, 5000);
    return () => window.clearInterval(poll);
  }, [loadCloudOrders]);

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

      // Cloud-synced pickup and drop-off points from the authenticated rider account.
      activeMap.addSource('4r-order-locations', {
        type: 'geojson',
        data: pendingCloudOrdersRef.current
      });
      activeMap.addSource('4r-cloud-order-route', {
        type: 'geojson',
        data: pendingCloudRouteRef.current
      });
      activeMap.addLayer({
        id: '4r-cloud-order-route-casing',
        type: 'line',
        source: '4r-cloud-order-route',
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: { 'line-color': '#101719', 'line-width': ['interpolate', ['linear'], ['zoom'], 10, 5, 16, 11, 19, 14], 'line-opacity': 0.96 }
      });
      activeMap.addLayer({
        id: '4r-cloud-order-route-line',
        type: 'line',
        source: '4r-cloud-order-route',
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: { 'line-color': ['match', ['get', 'speed'], 'NORMAL', '#39D98A', 'SLOW', '#F4B942', 'TRAFFIC_JAM', '#FF5D62', '#36D9FF'], 'line-width': ['interpolate', ['linear'], ['zoom'], 10, 2.8, 16, 6, 19, 8], 'line-opacity': 0.98 }
      });
      activeMap.addLayer({
        id: '4r-order-points',
        type: 'circle',
        source: '4r-order-locations',
        paint: {
          'circle-radius': ['interpolate', ['linear'], ['zoom'], 10, 5, 16, 9, 19, 12],
          'circle-color': ['match', ['get', 'kind'], 'pickup', '#36D9FF', '#FFAD55'],
          'circle-stroke-color': '#101719',
          'circle-stroke-width': 2.5,
          'circle-opacity': 0.98
        }
      });
      activeMap.addLayer({
        id: '4r-order-point-labels',
        type: 'symbol',
        source: '4r-order-locations',
        minzoom: 14,
        layout: {
          'text-field': ['get', 'shortLabel'],
          'text-font': ['Noto Sans Bold'],
          'text-size': 10,
          'text-offset': [0, 1.4],
          'text-anchor': 'top',
          'text-allow-overlap': true
        },
        paint: {
          'text-color': '#F5FAFC',
          'text-halo-color': '#101719',
          'text-halo-width': 1.6
        }
      });
      activeMap.on('click', '4r-order-points', (event) => {
        const feature = event.features?.[0];
        if (!feature) return;
        const coordinates = (feature.geometry as GeoJSON.Point).coordinates as [number, number];
        const props = feature.properties ?? {};
        new maplibregl.Popup({ closeButton: true, offset: 12 })
          .setLngLat(coordinates)
          .setText(`${String(props.shortLabel ?? 'STOP')} · ${String(props.label ?? 'Location')} · ${props.verified === true || props.verified === 'true' ? 'Verified address' : 'Approximate / unverified'}`)
          .addTo(activeMap);
      });
      activeMap.on('mouseenter', '4r-order-points', () => { activeMap.getCanvas().style.cursor = 'pointer'; });
      activeMap.on('mouseleave', '4r-order-points', () => { activeMap.getCanvas().style.cursor = ''; });

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

  const searchCoverageCenter = async (event: FormEvent) => {
    event.preventDefault();
    const term = centerSearchQuery.trim();
    if (!term || centerSearching) return;
    setCenterSearching(true);
    setCenterSearchResults([]);
    try {
      const url = new URL(PHOTON_API);
      url.searchParams.set('q', term);
      url.searchParams.set('lat', String(coverageCenter[1]));
      url.searchParams.set('lon', String(coverageCenter[0]));
      url.searchParams.set('limit', '6');
      const response = await fetch(url, { headers: { Accept: 'application/json' } });
      if (!response.ok) throw new Error(`Location search failed (${response.status})`);
      const data = await response.json() as { features?: SearchFeature[] };
      setCenterSearchResults(data.features ?? []);
      if (!data.features?.length) setReachMessage('No matching roads or places found. Try another name.');
    } catch (error) {
      console.warn('[4Riders center search]', error);
      setReachMessage('Center search is temporarily unavailable. Try again.');
    } finally {
      setCenterSearching(false);
    }
  };

  const chooseCoverageCenterResult = (feature: SearchFeature) => {
    const [lng, lat] = feature.geometry.coordinates;
    const p = feature.properties;
    const label = [p.name, p.street, p.city, p.state].filter(Boolean).join(', ') || 'Selected place';
    const nextCenter: [number, number] = [lng, lat];
    coverageCenterRef.current = nextCenter;
    setCoverageCenter(nextCenter);
    setCoverageCenterLabel(label);
    setPickingCoverageCenter(false);
    setCenterSearchQuery('');
    setCenterSearchResults([]);
    setReachStatus('idle');
    setReachMessage('Center updated. Recalculate to refresh the road coverage.');
    setMessage('Coverage center selected from place search');
    mapRef.current?.flyTo({ center: nextCenter, zoom: 16.5, duration: 900 });
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
      {cloudUser && <div style={{position:'absolute',top:14,right:14,zIndex:25,display:'flex',alignItems:'center',gap:8,padding:'8px 11px',borderRadius:18,background:'rgba(13,19,23,.94)',border:'1px solid rgba(255,255,255,.14)',color:'#eef3f5',fontSize:10}}>
        <span style={{maxWidth:130,overflow:'hidden',textOverflow:'ellipsis',whiteSpace:'nowrap'}}>{cloudUser.email || 'Rider account'}</span>
        <button type="button" onClick={signOutCloud} style={{border:0,background:'transparent',color:'#f4b942',fontWeight:800}}>SIGN OUT</button>
      </div>}
      {!cloudUser && <div style={{position:'absolute',inset:0,zIndex:50,display:'grid',placeItems:'center',padding:22,background:'rgba(5,9,12,.82)',backdropFilter:'blur(12px)'}}>
        <form onSubmit={submitAuth} style={{width:'min(100%,360px)',padding:24,borderRadius:22,background:'#141c22',border:'1px solid rgba(255,255,255,.12)',boxShadow:'0 24px 80px rgba(0,0,0,.55)',display:'flex',flexDirection:'column',gap:13,color:'#f4f7f8'}}>
          <div style={{fontSize:11,letterSpacing:'.2em',fontWeight:900,color:'#f4b942'}}>4RIDERS · RIDER CLOUD</div>
          <strong style={{fontSize:23}}>{authMode === 'signin' ? 'Sign in' : 'Create rider account'}</strong>
          <span style={{fontSize:12,color:'#aab7bf'}}>Your orders, GPS history and settings sync to your own account.</span>
          <input type="email" required value={authEmail} onChange={e=>setAuthEmail(e.target.value)} placeholder="Email address" autoComplete="email" style={{padding:13,borderRadius:10,border:'1px solid #34434c',background:'#0d1419',color:'#fff'}}/>
          <input type="password" required minLength={6} value={authPassword} onChange={e=>setAuthPassword(e.target.value)} placeholder="Password" autoComplete={authMode === 'signin' ? 'current-password' : 'new-password'} style={{padding:13,borderRadius:10,border:'1px solid #34434c',background:'#0d1419',color:'#fff'}}/>
          {authError && <span role="alert" style={{fontSize:11,color:'#ff8c80'}}>{authError}</span>}
          <button type="submit" disabled={authBusy} style={{padding:13,border:0,borderRadius:11,background:'#f4b942',color:'#111820',fontWeight:900}}>{authBusy ? 'PLEASE WAIT…' : authMode === 'signin' ? 'SIGN IN' : 'CREATE ACCOUNT'}</button>
          <button type="button" onClick={()=>{setAuthMode(authMode === 'signin' ? 'signup' : 'signin');setAuthError('');}} style={{padding:8,border:0,background:'transparent',color:'#d5e0e5',fontSize:12}}>{authMode === 'signin' ? 'New rider? Create account' : 'Already registered? Sign in'}</button>
        </form>
      </div>}

      <div className="native-scanner-control" style={{position:"absolute",top:"max(14px, env(safe-area-inset-top))",left:14,zIndex:20,display:"flex",flexDirection:"column",gap:5,pointerEvents:"auto"}}>
        <button type="button" className={nativeScannerOn ? "native-scanner-toggle active" : "native-scanner-toggle"} onClick={toggleNativeScanner} style={{display:"flex",alignItems:"center",gap:9,padding:"11px 14px",borderRadius:22,border:"1px solid rgba(255,255,255,.18)",background:nativeScannerOn?"#173d32":"#171d24",color:"#f5f7f8",fontSize:11,fontWeight:800,letterSpacing:".08em",boxShadow:"0 6px 24px rgba(0,0,0,.35)"}}>
          <span className={nativeScannerOn ? "native-scanner-indicator active" : "native-scanner-indicator"} style={{width:8,height:8,borderRadius:8,background:nativeScannerOn?"#46df9a":"#f0a33a",boxShadow:nativeScannerOn?"0 0 10px #46df9a":"none"}} />
          <span>{nativeScannerOn ? "SCANNER ON" : "SCANNER OFF"}</span>
        </button>
        {nativeScannerMessage && <small style={{maxWidth:230,padding:"6px 9px",borderRadius:8,background:"rgba(12,16,20,.86)",color:"#cbd3d9",fontSize:10}}>{nativeScannerMessage}</small>}
      </div>
      {activePanel && <section className="map-tool-panel">
        <div className="map-tool-panel-head"><span>{activePanel === "search" ? "SEARCH PLACES" : activePanel === "route" ? "MOTORCYCLE NAVIGATION" : activePanel === "coverage" ? "ROAD COVERAGE SETTINGS" : activePanel === "road" ? "ROAD DETAILS" : activePanel === "building" ? "BUILDING DETAILS" : "MAP SETTINGS"}</span><button onClick={() => { setActivePanel(null); setPickingCoverageCenter(false); }} aria-label="Close options"><X size={18}/></button></div>
        {activePanel === "search" && <><form className="dock-search-form" onSubmit={searchPlaces}><Search size={18}/><input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="Search places, roads or landmarks" aria-label="Search map"/>{query && <button type="button" onClick={() => { setQuery(""); setResults([]); }} aria-label="Clear search"><X size={15}/></button>}<button type="submit" disabled={searching}>{searching ? "…" : "Search"}</button></form>{results.length > 0 && <div className="dock-search-results">{results.map((feature, i) => <button type="button" key={i} onClick={() => { chooseResult(feature); setActivePanel(null); }}><MapPin size={15}/><span><b>{feature.properties.name || "Unnamed place"}</b><small>{[feature.properties.street, feature.properties.city, feature.properties.state].filter(Boolean).join(", ")}</small></span></button>)}</div>}</>}
        {activePanel === "route" && ready && mapRef.current && <RoutePlanner map={mapRef.current} />}
        {activePanel === "route" && !ready && <p className="tool-message">Navigation will be available when the map is ready.</p>}
        {activePanel === "coverage" && <><span className="coverage-setting-label">CENTER POINT</span><strong className="coverage-center-name">{coverageCenterLabel}</strong><small className="coverage-coordinates">{coverageCenter[1].toFixed(5)}, {coverageCenter[0].toFixed(5)}</small><form className="dock-search-form coverage-center-search" onSubmit={searchCoverageCenter}><Search size={18}/><input value={centerSearchQuery} onChange={(event) => setCenterSearchQuery(event.target.value)} placeholder="Search road, building or address" aria-label="Search center point"/>{centerSearchQuery && <button type="button" onClick={() => { setCenterSearchQuery(''); setCenterSearchResults([]); }} aria-label="Clear center search"><X size={15}/></button>}<button type="submit" disabled={centerSearching || !centerSearchQuery.trim()}>{centerSearching ? "…" : "Search"}</button></form>{centerSearchResults.length > 0 && <div className="dock-search-results coverage-center-results">{centerSearchResults.map((feature, index) => <button type="button" key={index} onClick={() => chooseCoverageCenterResult(feature)}><MapPin size={15}/><span><b>{feature.properties.name || feature.properties.street || "Unnamed place"}</b><small>{[feature.properties.street, feature.properties.city, feature.properties.state, feature.properties.country].filter(Boolean).join(", ")}</small></span></button>)}</div>}<button className={pickingCoverageCenter ? "coverage-pick active" : "coverage-pick"} onClick={() => setPickingCoverageCenter((value) => !value)}>{pickingCoverageCenter ? "Tap map to set center…" : "Or choose center on map"}</button><span className="coverage-setting-label">ROAD DISTANCE · KM</span><div className="coverage-radius-row"><input type="number" min="1" max="100" step="1" value={coverageRadius} onChange={(event) => setCoverageRadius(Math.max(1, Math.min(100, Number(event.target.value) || 1)))}/><input type="range" min="1" max="100" step="1" value={coverageRadius} onChange={(event) => setCoverageRadius(Number(event.target.value))}/></div><div className="coverage-presets">{[10,15,20,25,30,40,50].map((value) => <button key={value} className={coverageRadius === value ? "selected" : ""} onClick={() => setCoverageRadius(value)}>{value}</button>)}</div><button className="coverage-apply" onClick={calculateRoadReach} disabled={reachStatus === "loading"}>{reachStatus === "loading" ? "Calculating…" : "Apply & calculate coverage"}</button><small className="coverage-footnote">Motorcycle road-network distance, not a straight-line circle. Maximum 100 km.</small>{reachMessage && <p className="tool-message">{reachMessage}</p>}</>}
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