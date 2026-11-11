package my.fouriders.scan

import android.content.Context
import java.util.Locale

/** One scanned order. decision: "T" taken, "S" skipped, "" not decided. */
data class Rec(
    val id: Long, val word: String, val rpk: Double, val fare: Double, val km: Double,
    val area: String, val decision: String,
    val pickupLabel: String = "", val pickupLat: Double? = null, val pickupLng: Double? = null,
    val pickupVerified: Boolean = false,
    val dropoffLabel: String = "", val dropoffLat: Double? = null, val dropoffLng: Double? = null,
    val dropoffVerified: Boolean = false
)

/** Local-first order history; cloud sync runs asynchronously after each upsert. */
object Store {
    private const val KEY = "rows"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("orders", Context.MODE_PRIVATE)

    fun all(ctx: Context): List<Rec> =
        prefs(ctx).getString(KEY, "").orEmpty().split("\n").mapNotNull { line ->
            val p = line.split("|")
            if (p.size < 7) null else try {
                Rec(p[0].toLong(), p[1], p[2].toDouble(), p[3].toDouble(), p[4].toDouble(), p[6], p[5], p.getOrElse(7) { "" }, p.getOrNull(8)?.toDoubleOrNull(), p.getOrNull(9)?.toDoubleOrNull(), p.getOrNull(10) == "1", p.getOrElse(11) { "" }, p.getOrNull(12)?.toDoubleOrNull(), p.getOrNull(13)?.toDoubleOrNull(), p.getOrNull(14) == "1")
            } catch (e: Exception) {
                null
            }
        }

    private fun clean(value: String) = value.replace("|", " ").replace("\n", " ").replace("\r", " ")

    private fun save(ctx: Context, rows: List<Rec>) {
        val s = rows.takeLast(3000).joinToString("\n") {
            listOf(it.id.toString(), it.word, String.format(Locale.US, "%.3f", it.rpk), String.format(Locale.US, "%.2f", it.fare), String.format(Locale.US, "%.1f", it.km), it.decision, clean(it.area), clean(it.pickupLabel), it.pickupLat?.toString().orEmpty(), it.pickupLng?.toString().orEmpty(), if (it.pickupVerified) "1" else "0", clean(it.dropoffLabel), it.dropoffLat?.toString().orEmpty(), it.dropoffLng?.toString().orEmpty(), if (it.dropoffVerified) "1" else "0").joinToString("|")
        }
        prefs(ctx).edit().putString(KEY, s).apply()
    }

    fun upsert(ctx: Context, r: Rec) {
        val rows = all(ctx).toMutableList()
        val i = rows.indexOfFirst { it.id == r.id }
        if (i >= 0) rows[i] = r else rows.add(r)
        save(ctx, rows)
        CloudSync.enqueue(ctx, r)
    }

    fun delete(ctx: Context, id: Long) {
        save(ctx, all(ctx).filter { it.id != id })
        CloudSync.deleteOrder(ctx, id)
    }

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
