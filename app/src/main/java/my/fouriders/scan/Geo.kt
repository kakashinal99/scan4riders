package my.fouriders.scan

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Location, address lookup and road distance. Call from a background thread. */
object Geo {
    data class P(val lat: Double, val lon: Double)

    private var lastNominatim = 0L

    @SuppressLint("MissingPermission")
    fun gps(ctx: Context): P? {
        if (ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) return null
        return try {
            val lm = ctx.getSystemService(LocationManager::class.java)
            var loc: Location? = null
            val provider = when {
                lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                else -> null
            }
            if (provider != null) {
                val latch = CountDownLatch(1)
                lm.getCurrentLocation(provider, null, ctx.mainExecutor) { l ->
                    loc = l
                    latch.countDown()
                }
                latch.await(7, TimeUnit.SECONDS)
            }
            if (loc == null) {
                loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            }
            loc?.let { P(it.latitude, it.longitude) }
        } catch (e: Exception) {
            null
        }
    }

    fun geocode(ctx: Context, s: Stop): P? {
        val area = s.address.split(',').map { it.trim() }.filter { it.isNotEmpty() }.takeLast(4).joinToString(", ")
        val tries = listOf(
            "${s.name}, ${s.address}",
            s.address,
            "${s.name}, $area"
        ).map { if (it.contains("Malaysia", ignoreCase = true)) it else "$it, Malaysia" }.distinct()
        for (q in tries) viaGeocoder(ctx, q)?.let { return it }
        for (q in tries.drop(1)) viaNominatim(q)?.let { return it }
        return null
    }

    @Suppress("DEPRECATION")
    private fun viaGeocoder(ctx: Context, q: String): P? = try {
        if (!Geocoder.isPresent()) {
            null
        } else {
            val r = Geocoder(ctx, Locale.getDefault()).getFromLocationName(q, 1, 0.8, 99.5, 7.5, 119.5)
            r?.firstOrNull()?.let { P(it.latitude, it.longitude) }
        }
    } catch (e: Exception) {
        null
    }

    private fun viaNominatim(q: String): P? = try {
        val wait = 1100L - (System.currentTimeMillis() - lastNominatim)
        if (wait > 0) Thread.sleep(wait)
        lastNominatim = System.currentTimeMillis()
        val url = "https://nominatim.openstreetmap.org/search?format=json&limit=1&countrycodes=my&q=" +
            URLEncoder.encode(q, "UTF-8")
        val arr = JSONArray(get(url))
        if (arr.length() == 0) null else {
            val o = arr.getJSONObject(0)
            P(o.getString("lat").toDouble(), o.getString("lon").toDouble())
        }
    } catch (e: Exception) {
        null
    }

    /** Road km for each leg between consecutive points, or null if the map service fails. */
    fun routeLegs(pts: List<P>): List<Double>? = try {
        val coords = pts.joinToString(";") { "${it.lon},${it.lat}" }
        val url = "https://router.project-osrm.org/route/v1/driving/$coords?overview=false&steps=false"
        val root = JSONObject(get(url))
        if (root.getString("code") != "Ok") null else {
            val legs = root.getJSONArray("routes").getJSONObject(0).getJSONArray("legs")
            (0 until legs.length()).map { legs.getJSONObject(it).getDouble("distance") / 1000.0 }
        }
    } catch (e: Exception) {
        null
    }

    /** Straight line x 1.35, used only when the map service cannot be reached. */
    fun rough(a: P, b: P): Double {
        val r = 6371.0
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(h)) * 1.35
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 10000
        c.setRequestProperty("User-Agent", "4ridersScan/0.2")
        return try {
            c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}
