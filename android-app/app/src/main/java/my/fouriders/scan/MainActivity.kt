package my.fouriders.scan

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private var receiverRegistered = false
    private var masterEnabled = false
    private var lastOrderFingerprint = ""
    private var lastOrderEmittedAt = 0L
    private val captureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null && masterEnabled) {
            val capture = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
            }
            ContextCompat.startForegroundService(this, capture)
        } else {
            masterEnabled = false
            stopService(Intent(this, RiderLocationService::class.java))
            notifyScannerState(false, "Screen capture permission was not granted")
        }
    }

    companion object {
        private const val REQUEST_LOCATION = 4101
        private const val MAP_URL = "https://fourriders-custom-map.onrender.com"
    }

    private val eventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                RiderLocationService.ACTION_LOCATION -> {
                    val lat = intent.getDoubleExtra("lat", Double.NaN)
                    val lon = intent.getDoubleExtra("lon", Double.NaN)
                    val accuracy = intent.getFloatExtra("accuracy", 0f)
                    val bearing = intent.getFloatExtra("bearing", 0f)
                    if (lat.isFinite() && lon.isFinite() && ::web.isInitialized) pushLocation(lat, lon, accuracy, bearing)
                }
                ScreenCaptureService.ACTION_OCR_TEXT -> {
                    val text = intent.getStringExtra(ScreenCaptureService.EXTRA_OCR_TEXT).orEmpty()
                    val parsed = GrabOrderParser.parse(text)
                    if (parsed != null && ::web.isInitialized) {
                        val fingerprint = listOf("fare", "rider_to_pickup_km", "pickup_to_dropoff_km", "pickup_label", "dropoff_label")
                            .joinToString("|") { key -> parsed.optString(key, "") }
                        val now = System.currentTimeMillis()
                        if (fingerprint != lastOrderFingerprint || now - lastOrderEmittedAt > 12000L) {
                            lastOrderFingerprint = fingerprint
                            lastOrderEmittedAt = now
                            val encoded = JSONObject.quote(parsed.toString())
                            web.evaluateJavascript("window.receive4RidersNativeOrder?.(JSON.parse($encoded))", null)
                        }
                    }
                }
                ScreenCaptureService.ACTION_CAPTURE_STATE -> {
                    val active = intent.getBooleanExtra(ScreenCaptureService.EXTRA_ACTIVE, false)
                    val detail = intent.getStringExtra(ScreenCaptureService.EXTRA_DETAIL).orEmpty()
                    notifyScannerState(active, detail)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.databaseEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                web.evaluateJavascript("window.dispatchEvent(new CustomEvent('fouriders-native-ready'));", null)
            }
        }
        web.webChromeClient = WebChromeClient()
        web.addJavascriptInterface(AndroidBridge(), "Android4Riders")
        web.loadUrl(MAP_URL)
        registerEventReceiver()
        requestLocationPermissionIfNeeded()
    }

    @Suppress("DEPRECATION")
    private fun registerEventReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(RiderLocationService.ACTION_LOCATION)
            addAction(ScreenCaptureService.ACTION_OCR_TEXT)
            addAction(ScreenCaptureService.ACTION_CAPTURE_STATE)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(eventReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(eventReceiver, filter)
        receiverRegistered = true
    }

    private fun requestLocationPermissionIfNeeded() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!granted) ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQUEST_LOCATION)
    }

    private fun startMasterSession() {
        masterEnabled = true
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine) {
            requestLocationPermissionIfNeeded()
            masterEnabled = false
            notifyScannerState(false, "Location permission required")
            return
        }
        ContextCompat.startForegroundService(this, Intent(this, RiderLocationService::class.java))
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        captureLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun stopMasterSession() {
        masterEnabled = false
        stopService(Intent(this, RiderLocationService::class.java))
        stopService(Intent(this, ScreenCaptureService::class.java))
        notifyScannerState(false, "Master scanner OFF")
    }

    private fun notifyScannerState(active: Boolean, detail: String) {
        if (!::web.isInitialized) return
        val state = JSONObject.quote(if (active) "on" else "off")
        val message = JSONObject.quote(detail)
        runOnUiThread { web.evaluateJavascript("window.set4RidersNativeScannerState?.($state,$message)", null) }
    }

    private inner class AndroidBridge {
        @JavascriptInterface fun setMasterScannerEnabled(enabled: Boolean) {
            runOnUiThread { if (enabled) startMasterSession() else stopMasterSession() }
        }
        @JavascriptInterface fun setOrderStatus(orderId: String, status: String) {
            val safeId = JSONObject.quote(orderId)
            val safeStatus = JSONObject.quote(status)
            runOnUiThread { web.evaluateJavascript("window.update4RidersOrderStatus?.($safeId,$safeStatus)", null) }
        }
        @JavascriptInterface fun submitRecognizedOrder(payloadJson: String) {
            runOnUiThread {
                val encoded = JSONObject.quote(payloadJson)
                web.evaluateJavascript("window.receive4RidersNativeOrder?.(JSON.parse($encoded))", null)
            }
        }
    }

    private fun pushLocation(lat: Double, lon: Double, accuracy: Float, bearing: Float) {
        val js = "window.set4RidersLocation?.($lat,$lon,$accuracy,$bearing)"
        runOnUiThread { if (::web.isInitialized) web.evaluateJavascript(js, null) }
    }

    override fun onDestroy() {
        if (receiverRegistered) {
            unregisterReceiver(eventReceiver)
            receiverRegistered = false
        }
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }
}
