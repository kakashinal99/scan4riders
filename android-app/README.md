# 4Riders Android app (native rebuild)

This is a clean Android project scaffold for the current 4Riders APK lineage. It deliberately does not reuse the legacy GitHub app UI.

## Implemented in this scaffold
- Android WebView host for the deployed Custom Map.
- JavaScript bridge for master scanner ON/OFF and order status forwarding.
- Foreground location service using Fused Location Provider.
- Native-to-map location and order bridge contract placeholders.

## Required before production APK
1. Add a runtime location receiver in MainActivity and forward service broadcasts to `pushLocation()`.
2. Implement the original Grab overlay capture workflow and ML Kit OCR parser from the APK's recovered behavior.
3. Add verified pickup/drop-off geocoding and send normalized order payloads to `window.save4RidersOrder()`.
4. Add offline order/GPS queue with retry and user/session binding.
5. Implement notification permission flow for Android 13+ and background-location policy if required by the final UX.
6. Verify hosted map URL, sign-in/session handoff, order lifecycle, GPS while screen is off, and traffic route on a physical Android device.
7. Build and sign with a new keystore after uninstalling the old APK if signature mismatch occurs.

The project is a source scaffold, not a verified release APK. Build requires Android SDK + Gradle.
