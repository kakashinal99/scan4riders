package my.fouriders.scan

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private var receiverRegistered = false

    companion object {
        private const val REQUEST_LOCATION = 4101
        private const val MAP_URL = "https://fourriders-custom-map.onrender.com"
    }

    private val locationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != RiderLocationService.ACTION_LOCATION) return
            val lat = intent.getDoubleExtra("lat", Double.NaN)
            val lon = intent.getDoubleExtra("lon", Double.NaN)
            val accuracy = intent.getFloatExtra("accuracy", 0f)
            val bearing = intent.getFloatExtra("bearing", 0f)
            if (lat.isFinite() && lon.isFinite() && ::web.isInitialized) {
                pushLocation(lat, lon, accuracy, bearing)
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
        registerLocationReceiver()
        requestLocationPermissionIfNeeded()
    }

    private fun registerLocationReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter(RiderLocationService.ACTION_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(locationReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(locationReceiver, filter)
        receiverRegistered = true
    }

    private fun requestLocationPermissionIfNeeded() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!granted) ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQUEST_LOCATION)
    }

    private inner class AndroidBridge {
        @JavascriptInterface fun setMasterScannerEnabled(enabled: Boolean) {
            runOnUiThread {
                if (enabled) {
                    val fine = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    if (!fine) { requestLocationPermissionIfNeeded(); return@runOnUiThread }
                    ContextCompat.startForegroundService(this@MainActivity, Intent(this@MainActivity, RiderLocationService::class.java))
                } else {
                    stopService(Intent(this@MainActivity, RiderLocationService::class.java))
                }
            }
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
            unregisterReceiver(locationReceiver)
            receiverRegistered = false
        }
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }
}
