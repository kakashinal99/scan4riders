package my.fouriders.scan

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.SystemClock
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
    data class P(val lat: Double, val lon: Double, val accuracyMeters: Float? = null)

    private var lastNominatim = 0L
    private var lastOsrm = 0L

    @SuppressLint("MissingPermission")
    fun gps(ctx: Context): P? {
        val fine = ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return null
        return try {
            val lm = ctx.getSystemService(LocationManager::class.java)
            val providers = (if (fine) listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                else listOf(LocationManager.NETWORK_PROVIDER)).filter {
                try { lm.isProviderEnabled(it) } catch (_: Exception) { false }
            }
            val maxAccuracy = if (fine) 100f else 250f
            fun acceptable(location: Location?, maxAgeMs: Long): Location? {
                location ?: return null
                val ageNs = SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos
                val ageMs = TimeUnit.NANOSECONDS.toMillis(ageNs)
                return location.takeIf {
                    ageNs >= 0 && ageMs <= maxAgeMs && it.hasAccuracy() && it.accuracy <= maxAccuracy
                }
            }
            for (provider in providers) {
                var loc: Location? = null
                val latch = CountDownLatch(1)
                val cancel = CancellationSignal()
                lm.getCurrentLocation(provider, cancel, ctx.mainExecutor) { location ->
                    loc = location
                    latch.countDown()
                }
                if (!latch.await(if (provider == LocationManager.GPS_PROVIDER) 6 else 3, TimeUnit.SECONDS)) {
                    cancel.cancel()
                }
                acceptable(loc, 30_000L)?.let { return P(it.latitude, it.longitude, it.accuracy) }
            }
            providers.mapNotNull { provider ->
                try { acceptable(lm.getLastKnownLocation(provider), 15_000L) } catch (_: Exception) { null }
            }.minByOrNull { it.accuracy }?.let { P(it.latitude, it.longitude, it.accuracy) }
        } catch (e: Exception) {
            null
        }
    }

    /** Search tier: 0 = full-address query, 1 = broader address query, 2 = postcode-area query. */
    data class Hit(val p: P, val level: Int, val how: String, val reference: P?, val label: String = "")

    /** Reuse a cached place match only near the location context in which it was selected. */
    fun cacheMatchesReference(hit: Hit, ref: P?): Boolean = when {
        hit.reference == null || ref == null -> hit.reference == null && ref == null
        else -> straight(hit.reference, ref) <= 2.0
    }

    private class Cand(val p: P, val postcode: String?, val label: String)

    private val unitPart = Regex("""^(no\.?|lot|unit|blok|block|level|lvl)?\s*\d+[a-z]?(\s*[&/\-]\s*\d+[a-z]?)*$""", RegexOption.IGNORE_CASE)

    private val levelPart = Regex("""^(lot|level|lvl|unit|blok|block|floor|ground floor)\b""", RegexOption.IGNORE_CASE)

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
        val noUnit = parts.filterNot { unitPart.matches(it) || levelPart.containsMatchIn(it) }
        val addr2 = noUnit.joinToString(", ")
        val area = noUnit.filterNot { it == post }.takeLast(3).joinToString(", ")
        val tail2 = noUnit.filterNot { it == post }.takeLast(2).joinToString(", ")
        val tiers = listOf(
            Triple(listOf("${s.name}, ${s.address}", s.address), 0, "searched with the full address"),
            Triple(listOf("${s.name}, $addr2", addr2), 1, "searched by street without unit number"),
            Triple(listOf("${s.name}, $area" + (if (post != null) ", $post" else "")), 1, "searched by name and area"),
            Triple(if (post != null) listOf("$post, $tail2") else emptyList(), 2, "searched by postcode area only")
        )
        for ((qs, level, how) in tiers) {
            for (q in qs.map { withMy(it) }.distinct()) {
                val cands = candidates(ctx, q)
                val p = pick(cands, ref, post, if (level == 0) 60.0 else 120.0)
                if (p != null) return Hit(p, level, how, ref)
            }
        }
        return null
    }

    /** Suggestions for rider confirmation; no broad result is silently treated as exact. */
    fun suggestions(ctx: Context, s: Stop, ref: P?): List<Hit> {
        val post = Regex("""\b(\d{5})\b""").find(s.address)?.groupValues?.get(1)
        val parts = s.address.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val noUnit = parts.filterNot { unitPart.matches(it) || levelPart.containsMatchIn(it) }
        val area = noUnit.filterNot { it == post }.takeLast(3).joinToString(", ")
        val queries = listOf(
            Triple(listOf("${s.name}, ${s.address}", s.address), 0, "Full address"),
            Triple(listOf("${s.name}, $area" + (if (post != null) ", $post" else "")), 1, "Name + area")
        )
        for ((qs, level, how) in queries) {
            val found = ArrayList<Cand>()
            for (q in qs.map { withMy(it) }.distinct()) found.addAll(candidates(ctx, q))
            val unique = found.distinctBy { "${it.p.lat.formatCoord()},${it.p.lon.formatCoord()}" }
            if (unique.isNotEmpty()) {
                return unique.sortedBy { if (ref == null) 0.0 else straight(ref, it.p) }
                    .take(5).map { Hit(it.p, level, how, ref, it.label) }
            }
        }
        return emptyList()
    }

    private fun Double.formatCoord() = String.format(Locale.US, "%.5f", this)

    private fun withMy(q: String) = if (q.contains("Malaysia", ignoreCase = true)) q else "$q, Malaysia"

    private fun pick(c: List<Cand>, ref: P?, post: String?, maxKm: Double): P? {
        if (c.isEmpty()) return null
        val near = if (ref == null) c else c.filter { straight(ref, it.p) <= maxKm }
        if (near.isEmpty()) return null
        val same = if (post != null) near.filter { it.postcode == post } else emptyList()
        val use = if (same.isNotEmpty()) same else near
        val selected = if (ref == null) use.first() else use.minByOrNull { straight(ref, it.p) }
        return selected?.p
    }

    private fun candidates(ctx: Context, q: String): List<Cand> {
        val g = viaGeocoder(ctx, q)
        return if (g.isNotEmpty()) g else viaNominatim(q)
    }

    @Suppress("DEPRECATION")
    private fun viaGeocoder(ctx: Context, q: String): List<Cand> = try {
        if (!Geocoder.isPresent()) emptyList()
        else Geocoder(ctx, Locale.getDefault()).getFromLocationName(q, 5, 0.8, 99.5, 7.5, 119.5)
            .orEmpty().map { Cand(P(it.latitude, it.longitude), it.postalCode,
                it.getAddressLine(0) ?: it.featureName ?: q) }
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
                o.optJSONObject("address")?.optString("postcode")?.takeIf { x -> x.isNotEmpty() },
                o.optString("display_name", q))
        }
    } catch (e: Exception) {
        emptyList()
    }

    /** Road km for each leg between consecutive points, or null if the map service fails. */
    fun routeLegs(pts: List<P>): List<Double>? = try {
        val wait = 1100L - (System.currentTimeMillis() - lastOsrm)
        if (wait > 0) Thread.sleep(wait)
        lastOsrm = System.currentTimeMillis()
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

    /** Driving-road km from one saved center to each stop, in one OSRM table request. */
    fun roadDistancesFrom(center: P, destinations: List<P>): List<Double?>? {
        if (destinations.isEmpty()) return emptyList()
        return try {
        val wait = 1100L - (System.currentTimeMillis() - lastOsrm)
        if (wait > 0) Thread.sleep(wait)
        lastOsrm = System.currentTimeMillis()
        val points = listOf(center) + destinations
        val coords = points.joinToString(";") { "${it.lon},${it.lat}" }
        val dests = (1..destinations.size).joinToString(";")
        val url = "https://router.project-osrm.org/table/v1/driving/$coords?sources=0&destinations=$dests&annotations=distance"
        val root = JSONObject(get(url))
        if (root.optString("code") != "Ok") null else {
            val row = root.getJSONArray("distances").optJSONArray(0) ?: return null
            (0 until destinations.size).map { i ->
                if (row.isNull(i)) null else row.getDouble(i) / 1000.0
            }
        }
        } catch (e: Exception) {
            null
        }
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
        c.setRequestProperty("User-Agent", "4Riders/1.4.3 (Android rider order scanner)")
        return try {
            c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}
