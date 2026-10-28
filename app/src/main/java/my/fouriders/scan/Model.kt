package my.fouriders.scan

import java.util.TreeMap
import kotlin.math.abs

data class Stop(val n: Int, val collect: Boolean, val name: String, val address: String)

data class Parsed(val fare: Double?, val bonus: Double?, val total: Int?, val stops: List<Stop>)

/** Keeps what has been read so far, so two captures (before and after a scroll) join up. */
object Session {
    var fare: Double? = null
    var bonus: Double? = null
    var total: Int? = null
    val stops = TreeMap<Int, Stop>()
    val geo = HashMap<String, Geo.Hit>()
    private var at = 0L

    fun reset() {
        fare = null
        bonus = null
        total = null
        stops.clear()
        at = 0L
    }

    fun merge(p: Parsed, now: Long) {
        val oldFare = fare
        val newFare = p.fare
        val stale = now - at > 120_000L
        val different = newFare != null && oldFare != null && abs(newFare - oldFare) > 0.001
        if (stale || different) reset()
        if (newFare != null) fare = newFare
        if (p.bonus != null) bonus = p.bonus
        if (p.total != null) total = p.total
        for (s in p.stops) stops[s.n] = s
        at = now
    }

    fun missing(): List<Int> {
        val t = total ?: return emptyList()
        return (1..t).filter { !stops.containsKey(it) }
    }

    fun hasAny(): Boolean = fare != null || stops.isNotEmpty()
}
