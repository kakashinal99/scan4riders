package my.fouriders.scan

import android.content.Context
import java.util.Locale

/** One scanned order. decision: "T" taken, "S" skipped, "" not decided. */
data class Rec(
    val id: Long, val word: String, val rpk: Double, val fare: Double, val km: Double,
    val area: String, val decision: String
)

/** Local history of scanned orders, kept on this phone only. */
object Store {
    private const val KEY = "rows"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("orders", Context.MODE_PRIVATE)

    fun all(ctx: Context): List<Rec> =
        prefs(ctx).getString(KEY, "").orEmpty().split("\n").mapNotNull { line ->
            val p = line.split("|")
            if (p.size < 7) null else try {
                Rec(p[0].toLong(), p[1], p[2].toDouble(), p[3].toDouble(), p[4].toDouble(), p[6], p[5])
            } catch (e: Exception) {
                null
            }
        }

    private fun save(ctx: Context, rows: List<Rec>) {
        val s = rows.takeLast(3000).joinToString("\n") {
            String.format(Locale.US, "%d|%s|%.3f|%.2f|%.1f|%s|%s", it.id, it.word, it.rpk, it.fare, it.km, it.decision, it.area.replace("|", " ").replace("\n", " "))
        }
        prefs(ctx).edit().putString(KEY, s).apply()
    }

    fun upsert(ctx: Context, r: Rec) {
        val rows = all(ctx).toMutableList()
        val i = rows.indexOfFirst { it.id == r.id }
        if (i >= 0) rows[i] = r else rows.add(r)
        save(ctx, rows)
    }

    fun delete(ctx: Context, id: Long) = save(ctx, all(ctx).filter { it.id != id })

    fun get(ctx: Context, id: Long): Rec? = all(ctx).firstOrNull { it.id == id }

    fun clear(ctx: Context) = prefs(ctx).edit().clear().apply()

    private val skip = Regex(
        """^(no\.?|lot|unit|blok|block|level|lvl|floor)\b|^\d|malaysia|^(wilayah|kuala lumpur|selangor|johor|penang|pulau pinang|perak|negeri sembilan|melaka|kedah|pahang|sabah|sarawak|kelantan|terengganu|perlis|putrajaya|labuan)""",
        RegexOption.IGNORE_CASE
    )
    private val road = Regex("""^(jalan|jln|lorong|lrg|persiaran|lebuh|lebuhraya|lengkok|laman)\b""", RegexOption.IGNORE_CASE)

    /** A short area name from a pickup address, e.g. "Damansara Utama". */
    fun areaOf(address: String): String {
        val parts = address.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val c = parts.filterNot { skip.containsMatchIn(it) || Regex("""\d{5}""").containsMatchIn(it) }
        val noRoad = c.filterNot { road.containsMatchIn(it) }
        return (noRoad.lastOrNull() ?: c.lastOrNull() ?: parts.lastOrNull() ?: "Unknown").take(28)
    }
}
