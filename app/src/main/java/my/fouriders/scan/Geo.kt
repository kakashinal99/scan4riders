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

    /** level 0 = full address matched, 1 = approximate (street, name or area), 2 = postcode area only */
    data class Hit(val p: P, val level: Int, val how: String)

    private class Cand(val p: P, val postcode: String?)

    private val unitPart = Regex("""^(no\.?|lot|unit|blok|block|level|lvl)?\s*\d+[a-z]?(\s*[&/\-]\s*\d+[a-z]?)*$""", RegexOption.IGNORE_CASE)

    /** Straight-line km, used to sanity-check a found place against where you are. */
    fun straight(a: P, b: P): Double {
        val r = 6371.0
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(h))
    }

    /**
     * Finds a stop in several steps, from the most exact search to the loosest, and checks every
     * result against where you are (ref), so a same-named place in another city is not picked.
     */
    fun locate(ctx: Context, s: Stop, ref: P?): Hit? {
        val post = Regex("""\b(\d{5})\b""").find(s.address)?.groupValues?.get(1)
        val parts = s.address.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val noUnit = parts.filterNot { unitPart.matches(it) }
        val addr2 = noUnit.joinToString(", ")
        val area = noUnit.filterNot { it == post }.takeLast(3).joinToString(", ")
        val tail2 = noUnit.filterNot { it == post }.takeLast(2).joinToString(", ")
        val tiers = listOf(
            Triple(listOf("${s.name}, ${s.address}", s.address), 0, "found by the full address"),
            Triple(listOf("${s.name}, $addr2", addr2), 1, "found by street, unit number dropped"),
            Triple(listOf("${s.name}, $area" + (if (post != null) ", $post" else "")), 1, "found by name and area"),
            Triple(if (post != null) listOf("$post, $tail2") else emptyList(), 2, "postcode area only")
        )
        for ((qs, level, how) in tiers) {
            for (q in qs.map { withMy(it) }.distinct()) {
                val cands = candidates(ctx, q)
                val p = pick(cands, ref, post, if (level == 0) 60.0 else 120.0)
                if (p != null) return Hit(p, level, how)
            }
        }
        return null
    }

    private fun withMy(q: String) = if (q.contains("Malaysia", ignoreCase = true)) q else "$q, Malaysia"

    private fun pick(c: List<Cand>, ref: P?, post: String?, maxKm: Double): P? {
        if (c.isEmpty()) return null
        val near = if (ref == null) c else c.filter { straight(ref, it.p) <= maxKm }
        if (near.isEmpty()) return null
        val same = if (post != null) near.filter { it.postcode == post } else emptyList()
        val use = if (same.isNotEmpty()) same else near
        return (if (ref == null) use.first() else use.minByOrNull { straight(ref, it.p) })?.p
    }

    private fun candidates(ctx: Context, q: String): List<Cand> {
        val g = viaGeocoder(ctx, q)
        return if (g.isNotEmpty()) g else viaNominatim(q)
    }

    @Suppress("DEPRECATION")
    private fun viaGeocoder(ctx: Context, q: String): List<Cand> = try {
        if (!Geocoder.isPresent()) emptyList()
        else Geocoder(ctx, Locale.getDefault()).getFromLocationName(q, 5, 0.8, 99.5, 7.5, 119.5)
            .orEmpty().map { Cand(P(it.latitude, it.longitude), it.postalCode) }
    } catch (e: Exception) {
        emptyList()
    }

    private fun viaNominatim(q: String): List<Cand> = try {
        val wait = 1100L - (System.currentTimeMillis() - lastNominatim)
        if (wait > 0) Thread.sleep(wait)
        lastNominatim = System.currentTimeMillis()
        val url = "https://nominatim.openstreetmap.org/search?format=json&limit=5&addressdetails=1&countrycodes=my&q=" +
            URLEncoder.encode(q, "UTF-8")
        val arr = JSONArray(get(url))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Cand(P(o.getString("lat").toDouble(), o.getString("lon").toDouble()),
                o.optJSONObject("address")?.optString("postcode")?.takeIf { x -> x.isNotEmpty() })
        }
    } catch (e: Exception) {
        emptyList()
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
