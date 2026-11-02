package my.fouriders.scan

import android.content.Context
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/** One scanned order. decision: "T" taken, "S" skipped, "" not decided. */
data class Rec(
    val id: Long, val word: String, val rpk: Double, val fare: Double, val km: Double,
    val area: String, val decision: String,
    val stops: String = ""   // "P:name;D:name", P = pickup, D = drop-off
)

/** Local history of scanned orders, kept on this phone only. */
object Store {
    private const val KEY = "rows"

    // Rows are stored one per line. Older builds wrote real newlines; one build wrote the
    // two characters backslash + n by mistake. Read both, always write real newlines.
    private val ROW_SPLIT = Regex("""\r?\n|\\n""")

    private fun field(s: String) =
        s.replace("|", " ").replace("\r", " ").replace("\n", " ").replace("\\n", " ")

    private fun encode(rows: List<Rec>): String = rows.joinToString("\n") {
        String.format(
            Locale.US, "%d|%s|%.3f|%.2f|%.1f|%s|%s|%s",
            it.id, it.word, it.rpk, it.fare, it.km, it.decision, field(it.area), field(it.stops)
        )
    }

    // Keep a hot in-memory snapshot so Accept/Reject never reparses the full
    // history on the main thread. Serialize persistence on one background lane.
    private val lock = Any()
    @Volatile private var cachedRows: List<Rec>? = null
    private val io = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + io)

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("orders", Context.MODE_PRIVATE)

    private fun decode(ctx: Context): List<Rec> =
        prefs(ctx).getString(KEY, "").orEmpty().split(ROW_SPLIT).mapNotNull { line ->
            val p = line.split("|")
            if (p.size < 7) null else try {
                Rec(p[0].toLong(), p[1], p[2].toDouble(), p[3].toDouble(), p[4].toDouble(), p[6], p[5], p.getOrNull(7).orEmpty())
            } catch (_: Exception) {
                null
            }
        }

    private fun snapshot(ctx: Context): List<Rec> {
        cachedRows?.let { return it }
        return synchronized(lock) {
            cachedRows ?: decode(ctx).takeLast(3000).also { cachedRows = it }
        }
    }

    fun all(ctx: Context): List<Rec> = snapshot(ctx).toList()

    private fun persist(ctx: Context, rows: List<Rec>) {
        val safe = rows.takeLast(3000)
        synchronized(lock) { cachedRows = safe }
        scope.launch {
            val s = encode(safe)
            prefs(ctx).edit().putString(KEY, s).apply()
        }
    }

    fun upsert(ctx: Context, r: Rec) {
        val updated = synchronized(lock) {
            val rows = snapshot(ctx).toMutableList()
            val i = rows.indexOfFirst { it.id == r.id }
            if (i >= 0) rows[i] = r else rows.add(r)
            rows.takeLast(3000).also { cachedRows = it }
        }
        scope.launch {
            // Disk and Room work stay off the UI thread and are serialized.
            val s = encode(updated)
            prefs(ctx).edit().putString(KEY, s).apply()
            try {
                TrackingDatabase.get(ctx).trackingDao().putOrder(
                    TrackedOrderEntity(
                        orderId = r.id,
                        sessionId = TrackingController.activeSessionId(ctx),
                        word = r.word,
                        fare = r.fare,
                        plannedKm = r.km,
                        rmPerKm = r.rpk,
                        area = r.area,
                        stops = r.stops,
                        scannedAt = r.id,
                        decision = r.decision
                    )
                )
            } catch (_: Exception) { }
        }
    }

    /** Changes the in-memory list at once so the screen can redraw now. Returns the old list for rollback. */
    fun applyNow(ctx: Context, r: Rec): List<Rec> {
        val before = snapshot(ctx)
        synchronized(lock) {
            val rows = (cachedRows ?: before).toMutableList()
            val i = rows.indexOfFirst { it.id == r.id }
            if (i >= 0) rows[i] = r else rows.add(r)
            cachedRows = rows.takeLast(3000)
        }
        return before
    }

    fun restore(rows: List<Rec>) {
        synchronized(lock) { cachedRows = rows }
    }

    /**
     * Saves a decision off the UI thread, then records its event. It runs on the app-wide
     * lane, so it still finishes if the screen is closed. done() is called on the main thread.
     */
    fun saveDecisionAsync(ctx: Context, r: Rec, event: String?, done: (Boolean) -> Unit) {
        val app = ctx.applicationContext
        scope.launch {
            val ok = try { upsertAndAwait(app, r) } catch (_: Exception) { false }
            if (ok && event != null) {
                try { OrderEventRecorder.record(app, r.id, event) } catch (_: Exception) { }
            }
            withContext(Dispatchers.Main) { done(ok) }
        }
    }

    /** Persist a rider decision before the UI reports it as complete. */
    suspend fun upsertAndAwait(ctx: Context, r: Rec): Boolean = withContext(io) {
        val previous = snapshot(ctx)
        val updated = synchronized(lock) {
            val rows = previous.toMutableList()
            val i = rows.indexOfFirst { it.id == r.id }
            if (i >= 0) rows[i] = r else rows.add(r)
            rows.takeLast(3000).also { cachedRows = it }
        }
        val s = encode(updated)
        val saved = prefs(ctx).edit().putString(KEY, s).commit()
        if (!saved) {
            synchronized(lock) { cachedRows = previous }
            return@withContext false
        }
        try {
            TrackingDatabase.get(ctx).trackingDao().putOrder(
                TrackedOrderEntity(
                    orderId = r.id,
                    sessionId = TrackingController.activeSessionId(ctx),
                    word = r.word,
                    fare = r.fare,
                    plannedKm = r.km,
                    rmPerKm = r.rpk,
                    area = r.area,
                    stops = r.stops,
                    scannedAt = r.id,
                    decision = r.decision
                )
            )
        } catch (_: Exception) { /* SharedPreferences is the primary local record. */ }
        true
    }

    fun delete(ctx: Context, id: Long) {
        val updated = synchronized(lock) {
            snapshot(ctx).filter { it.id != id }.also { cachedRows = it }
        }
        persist(ctx, updated)
        scope.launch {
            try { TrackingDatabase.get(ctx).trackingDao().deleteOrder(id) } catch (_: Exception) { }
        }
    }

    fun get(ctx: Context, id: Long): Rec? = snapshot(ctx).firstOrNull { it.id == id }

    fun clear(ctx: Context) {
        synchronized(lock) { cachedRows = emptyList() }
        scope.launch {
            prefs(ctx).edit().clear().apply()
            try { TrackingDatabase.get(ctx).trackingDao().clearOrders() } catch (_: Exception) { }
        }
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
