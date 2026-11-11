package my.fouriders.scan

import android.app.Activity
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebSettings
import android.view.ViewGroup

/** Loads the hosted 4Riders map inside the APK and passes the authenticated Supabase session. */
class MapActivity : Activity() {
    private lateinit var mapView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mapView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    CloudSync.provideSession(this@MapActivity) { accessToken, userId ->
                        val token = JSONObjectString.quote(accessToken)
                        val uid = JSONObjectString.quote(userId)
                        mapView.evaluateJavascript(
                            "window.set4RidersSession && window.set4RidersSession($token, $uid)",
                            null
                        )
                    }
                }
            }
        }
        setContentView(mapView, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        mapView.loadUrl(BuildConfig.CUSTOM_MAP_URL)
    }

    override fun onBackPressed() {
        if (mapView.canGoBack()) mapView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        mapView.stopLoading()
        mapView.destroy()
        super.onDestroy()
    }
}

/** JSON string escaping for safe JavaScript string literals. */
private object JSONObjectString {
    fun quote(value: String): String =
        org.json.JSONObject.quote(value)
}
