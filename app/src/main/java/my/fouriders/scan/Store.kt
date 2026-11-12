package my.fouriders.scan

import android.content.Context
import java.util.Locale

/** One scanned order. decision: "A" accepted, "T" completed, "S" skipped, "" not decided. */
data class Rec(
    val id: Long, val word: String, val rpk: Double, val fare: Double, val km: Double,
    val area: String, val decision: String, val st: String = "",
    val legKm: List<Double> = emptyList(), val acceptedAt: Long = 0L,
    val completedAt: Long = 0L, val actualKm: Double = 0.0,
    /** Route estimate captured/updated at acceptance; kept apart from GPS movement. */
    val expectedKm: Double = 0.0, val expectedMinutes: Double = 0.0
)

/** Local history of scanned orders, kept on this phone only. */
object Store {
    private const val KEY = "rows"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("orders", Context.MODE_PRIVATE)

    fun all(ctx: Context): List<Rec> =
        prefs(ctx).getString(KEY, "").orEmpty().split("\n").mapNotNull { line ->
            val p = line.split("|")
            if (p.size < 7) null else try {
                Rec(p[0].toLong(), p[1], p[2].toDouble(), p[3].toDouble(), p[4].toDouble(), p[6], p[5], p.getOrElse(7) { "" },
                    p.getOrElse(8) { "" }.split(',').mapNotNull { it.toDoubleOrNull() },
                    p.getOrElse(9) { "0" }.toLongOrNull() ?: 0L,
                    p.getOrElse(10) { "0" }.toLongOrNull() ?: 0L,
                    p.getOrElse(11) { "0" }.toDoubleOrNull() ?: 0.0,
                    p.getOrElse(12) { "0" }.toDoubleOrNull() ?: 0.0,
                    p.getOrElse(13) { "0" }.toDoubleOrNull() ?: 0.0)
            } catch (e: Exception) {
                null
            }
        }

    private fun save(ctx: Context, rows: List<Rec>) {
        val s = rows.takeLast(3000).joinToString("\n") {
            String.format(Locale.US, "%d|%s|%.3f|%.2f|%.1f|%s|%s|%s|%s|%d|%d|%.2f|%.2f|%.2f", it.id, it.word, it.rpk, it.fare, it.km, it.decision, it.area.replace("|", " ").replace("\n", " "), it.st.replace("|", " ").replace("\n", " "), it.legKm.joinToString(",") { km -> String.format(Locale.US, "%.1f", km) }, it.acceptedAt, it.completedAt, it.actualKm, it.expectedKm, it.expectedMinutes)
        }
        prefs(ctx).edit().putString(KEY, s).apply()
    }

    fun upsert(ctx: Context, r: Rec) {
        val rows = all(ctx).toMutableList()
        val i = rows.indexOfFirst { it.id == r.id }
        if (i >= 0) rows[i] = r else rows.add(r)
        save(ctx, rows)
        CloudSync.upsert(r)
    }

    /** Apply a cloud snapshot without writing it back to Firestore. */
    fun importRemote(ctx: Context, r: Rec) {
        val rows = all(ctx).toMutableList()
        val i = rows.indexOfFirst { it.id == r.id }
        if (i >= 0) rows[i] = r else rows.add(r)
        save(ctx, rows)
    }

    fun delete(ctx: Context, id: Long) {
        save(ctx, all(ctx).filter { it.id != id })
        CloudSync.delete(id)
    }

    fun get(ctx: Context, id: Long): Rec? = all(ctx).firstOrNull { it.id == id }

    fun clear(ctx: Context) {
        val ids = all(ctx).map { it.id }
        prefs(ctx).edit().clear().apply()
        EarningAdjustments.clear(ctx)
        CloudSync.deleteAll(ids)
    }

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

/** Durable ON/OFF intervals, independent of order scans and floating scanner visibility. */
object WorkClock {
    private const val PREFS = "work_clock"
    private val lock = Any()

    fun setOnline(ctx: Context, online: Boolean, now: Long = System.currentTimeMillis()) = synchronized(lock) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val start = p.getLong("started", 0L)
        if (online && start == 0L) p.edit().putLong("started", now).apply()
        else if (!online && start != 0L) {
            val old = org.json.JSONArray(p.getString("intervals", "[]"))
            old.put(org.json.JSONObject().put("s", start).put("e", now.coerceAtLeast(start)))
            while (old.length() > 1000) old.remove(0)
            p.edit().putString("intervals", old.toString()).remove("started").apply()
        }
    }

    fun isOnline(ctx: Context): Boolean = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("started", 0L) != 0L

    fun json(ctx: Context, now: Long = System.currentTimeMillis()): String = synchronized(lock) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = org.json.JSONArray(p.getString("intervals", "[]"))
        val start = p.getLong("started", 0L)
        if (start != 0L) arr.put(org.json.JSONObject().put("s", start).put("e", now.coerceAtLeast(start)))
        org.json.JSONObject().put("on", start != 0L).put("intervals", arr).toString()
    }
}

/** Manual earning adjustments are dated so History filters and charts can include them. */
object EarningAdjustments {
    private const val PREFS = "earning_adjustments"
    private const val KEY = "items"

    fun json(ctx: Context): String = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(KEY, "[]") ?: "[]"

    @Synchronized fun add(ctx: Context, at: Long, amount: Double) {
        if (!amount.isFinite() || amount == 0.0) return
        val arr = try { org.json.JSONArray(json(ctx)) } catch (_: Exception) { org.json.JSONArray() }
        arr.put(org.json.JSONObject().put("at", at).put("amount", amount))
        while (arr.length() > 3000) arr.remove(0)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}

/** Rider-saved addresses; JSON keeps punctuation and non-English text intact. */
object AddressBook {
    private const val KEY = "saved_addresses"

    fun all(ctx: Context): org.json.JSONArray = try {
        org.json.JSONArray(ctx.getSharedPreferences("orders", Context.MODE_PRIVATE).getString(KEY, "[]"))
    } catch (_: Exception) { org.json.JSONArray() }

    @Synchronized fun save(ctx: Context, name: String, address: String) {
        if (address.isBlank()) return
        val arr = all(ctx)
        val normalized = address.trim()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            if (item.optString("address").equals(normalized, true)) return
        }
        arr.put(org.json.JSONObject().put("name", name.trim()).put("address", normalized).put("savedAt", System.currentTimeMillis()))
        ctx.getSharedPreferences("orders", Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    @Synchronized fun delete(ctx: Context, index: Int) {
        val old = all(ctx)
        if (index !in 0 until old.length()) return
        val out = org.json.JSONArray()
        for (i in 0 until old.length()) if (i != index) out.put(old.get(i))
        ctx.getSharedPreferences("orders", Context.MODE_PRIVATE).edit().putString(KEY, out.toString()).apply()
    }
}
