# 4Riders Android ↔ Custom Map integration contract

This contract connects the current Android scanner to the existing MapLibre Custom Map. The map stays hosted separately and is loaded inside the Android WebView. The Android app remains responsible for OCR, Android permissions, foreground GPS, and the dashboard-wide scanner/GPS session switch.

## WebView bootstrap

1. Android loads `https://fourriders-custom-map.onrender.com` inside the app's map container.
2. After the page reports `window.__4RidersMapReady === true` (or after the map bridge functions exist), Android injects the authenticated Supabase session:
   ```js
   window.set4RidersSession(accessToken, userId);
   ```
3. Never put the Supabase service-role/secret key in the APK. The map uses the publishable key and the signed-in user's access token; RLS scopes rows to that user.

## Android → map functions

### Rider GPS

Call on each foreground-service GPS fix:
```js
window.set4RidersLocation(latitude, longitude, accuracyMeters, headingDegrees);
``
- Latitude/longitude are WGS84 decimal degrees.
- The map renders the live rider marker and uses the latest rider position as the first stop when it calculates the order route.
- While the WebView is active, the map writes one GPS sample to `public.gps_points` at most every 15 seconds.
- The Android foreground service must ALSO persist GPS samples while the app is backgrounded or the WebView is paused. WebView JavaScript is not a reliable background location service.

### Scanned Grab order

After OCR and address resolution, call:
```js
window.save4RidersOrder({
  external_order_id: "optional Grab order reference",
  fare_amount: 8.50,
  rider_to_pickup_m: 1200,
  pickup_to_dropoff_m: 4300,
  pickup_label: "Pickup address",
  pickup_lat: 3.0,
  pickup_lng: 101.5,
  pickup_verified: true,
  dropoff_label: "Drop-off address",
  dropoff_lat: 3.01,
  dropoff_lng: 101.51,
  dropoff_verified: true,
  raw_scan: { /* OCR fields; avoid unnecessary personal data */ }
});
```
- Coordinates must be resolved from the OCR address, not guessed from the rider's current GPS.
- Set each `*_verified` flag to true only when the geocoded point has been checked as the correct address. If ambiguous, send the best candidate as unverified and keep route calculation disabled until it is confirmed.
- The map saves the order to `public.orders`, reloads the rider's cloud orders, places pickup/drop-off markers, and draws a motorcycle route from current rider GPS → pickup → drop-off when both coordinates are verified.
- The app should treat the map/cloud insert as the durable record. Keep a retry queue on-device for offline scans; flush it when connectivity returns.

### Order lifecycle

Call after each native action:
```js
window.update4RidersOrderStatus(orderUuid, "accepted");
window.update4RidersOrderStatus(orderUuid, "rejected");
window.update4RidersOrderStatus(orderUuid, "completed");
```
Allowed values: `accepted`, `rejected`, `completed`, `cancelled`. The database row is scoped by both order UUID and the signed-in user ID.

## Session and GPS lifecycle

- Dashboard scanner ON: start Android foreground GPS service and keep it running through detected, accepted, rejected, and completed order states.
- Dashboard scanner OFF: stop the foreground GPS service and stop sending GPS updates.
- Scanner popup pause/close must not stop the GPS session.
- On login/session refresh, call `set4RidersSession` again with the fresh access token.
- On logout, clear the WebView session and stop GPS.

## Current implementation boundary

The map-side bridge functions are implemented in `custom-map/src/App.tsx` on branch `feature/4riders-app-map-integration`. This does not by itself modify the installed APK. The APK still needs Android-side WebView loading, JavaScript bridge calls, location foreground service, OCR-to-order mapping, and app signing/rebuild.

## Live traffic

The current motorcycle route engine is Valhalla and does **not** provide live traffic. Malaysia is listed for Google Routes two-wheeler coverage and TomTom real-time traffic coverage. The traffic-aware provider integration requires an API key with billing enabled and must be proxied server-side; do not embed a privileged provider key in the APK or browser bundle. Until that key is configured and route traffic data is returned, label the ETA as a motorcycle routing estimate, not live-traffic ETA.
