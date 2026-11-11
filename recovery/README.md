# 4Riders APK recovery (v1.3)

This branch is a safe staging area for reconstructing the current Android app from the supplied APK. It does not replace `main`.

## APK facts recovered
- Package: `my.fouriders.scan`
- Version: `1.3` (versionCode 14)
- minSdk 30; targetSdk 34
- Application: `my.fouriders.scan.App`
- Launcher activity: `MainActivity`
- Foreground overlay service: `OverlayService`

## Architecture confirmed from compiled metadata
- Native Android / Jetpack Compose activity with an embedded WebView loading `file:///android_asset/prototype.html`.
- WebView JavaScript bridge: `AndroidBridge`, including `ordersJson()` and `scannerJson()`.
- Overlay service uses MediaProjection and Google ML Kit text recognition.
- App-owned classes include `Parser`, `Geo`, `Store`, `Session`, `Parsed`, `Rec`, and `Stop`.
- `Store` uses Android SharedPreferences for local persistence.
- Address lookup uses Nominatim; route distance estimation uses OSRM.

## Cloud sync status
The APK itself displays: “Your orders are saved on this phone only. Sync with the 4riders web app is not on yet.” The Firebase component references in the manifest belong to ML Kit initialization; they are not proof of Firebase database or cloud sync.

## Recovery status
This is not yet a buildable Android source project. The APK's DEX bytecode, binary manifest, and embedded HTML are being preserved and documented. Original Gradle files, dependency declarations, signing keys, and exact Kotlin source are not present as source in the APK. The next step is DEX decompilation (JADX), followed by Gradle project reconstruction and on-device validation.

Do not promote this branch to the default branch until the rebuilt APK compiles and scanner, overlay, OCR, GPS, persistence, history, and dashboard behavior have been tested.
