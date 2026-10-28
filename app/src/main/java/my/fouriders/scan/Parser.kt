package my.fouriders.scan

import com.google.mlkit.vision.text.Text

/** Turns the text read from a Grab "Route details" screen into fare and stops. */
object Parser {
    private val head = Regex("""^\s*(?:[OoQ0●•©®]\s+)?(\d{1,2})\s*[.,]\s*(Collect from|Deliver to|Pick[\s-]?up item|Drop[\s-]?off item|Pick[\s-]?up|Drop[\s-]?off|Collect|Deliver)""", RegexOption.IGNORE_CASE)
    private val earn = Regex("""earn(?:ings)?\s*:?\s*RM\s*([0-9]+(?:[.,][0-9]{1,2})?)""", RegexOption.IGNORE_CASE)
    private val sender = Regex("""^sender'?s name\s*:?\s*(.*)$""", RegexOption.IGNORE_CASE)
    private val parties = Regex("""^(sender|recipient)'?s name\s*:?|^(drop[\s-]?off|pick[\s-]?up)\s+details|^(hide|show)\s+details|^item details|^call |^chat with |^cancel booking""", RegexOption.IGNORE_CASE)
    private val unitLike = Regex("""^(lot|level|lvl|unit|blok|block|floor|no\.?)\b|^\d""", RegexOption.IGNORE_CASE)
    private val plus = Regex("""^\s*\+\s*RM\s*([0-9]+(?:[.,][0-9]{1,2})?)""", RegexOption.IGNORE_CASE)
    private val left = Regex("""(\d+)\s*/\s*(\d+)\s*stops?""", RegexOption.IGNORE_CASE)
    private val labels = Regex(
        """^(arrive by|order\s*[i|l1]d|service|special instruction|payment method|no of items|booking id|hide|show|back to top|grabpay|promo|cash|grab(food|mart|express|pay))$""",
        RegexOption.IGNORE_CASE
    )
    private val time = Regex("""^\d{1,2}:\d{2}\s*(AM|PM)$""", RegexOption.IGNORE_CASE)
    private val rating = Regex("""^.{1,30}[\s.·•]+[0-5][.,]\d$""")
    // An id must contain a digit, or an all-capitals address line would be mistaken for one.
    private val ids = Regex("""^(?=.*\d)(?:[A-Z]{1,3}-?[0-9A-Z]{2,}(?:[,\s]+[A-Z]{1,3}-?[0-9A-Z]+)*|[A-Z]{1,3}(?:-[0-9A-Z]+)+)$""")
    private val noise = Regex("""detected this screenshot|no order found|stops?\s*[·.]?\s*scroll|stops not seen|fare not seen|finding route|reading\.\.\.""", RegexOption.IGNORE_CASE)

    private class Ln(val top: Int, val left: Int, val s: String)

    private fun orderedLines(t: Text): List<String> {
        val all = ArrayList<Ln>()
        for (b in t.textBlocks) {
            for (l in b.lines) {
                val box = l.boundingBox ?: continue
                val s = l.text.trim()
                if (s.isNotEmpty()) all.add(Ln(box.top, box.left, s))
            }
        }
        return all.sortedWith(compareBy<Ln>({ it.top }, { it.left })).map { it.s }
    }

    private fun isEnd(s: String): Boolean =
        labels.matches(s) || parties.containsMatchIn(s) || time.matches(s) || rating.matches(s) || ids.matches(s) ||
            earn.containsMatchIn(s) || plus.containsMatchIn(s) || left.containsMatchIn(s)

    private fun num(s: String): Double = s.replace(',', '.').toDouble()

    fun parse(t: Text): Parsed {
        val ls = orderedLines(t).filter { !noise.containsMatchIn(it) }
        var fare: Double? = null
        var bonus: Double? = null
        var total: Int? = null
        val stops = ArrayList<Stop>()
        var curN = -1
        var curCollect = false
        var curParcel = false
        var wantName = -1 // index in stops waiting for the sender's shop name
        val buf = ArrayList<String>()

        fun flush() {
            if (curN >= 0 && buf.isNotEmpty()) stops.add(build(curN, curCollect, ArrayList(buf), curParcel))
            curN = -1
            buf.clear()
        }

        for (s in ls) {
            if (wantName >= 0) {
                val stillLabel = isEnd(s) || head.containsMatchIn(s)
                if (!stillLabel && wantName < stops.size) {
                    val old = stops[wantName]
                    stops[wantName] = old.copy(name = clean(s))
                }
                wantName = -1
                if (!stillLabel) continue
            }
            earn.find(s)?.let { fare = num(it.groupValues[1]) }
            plus.find(s)?.let { if (bonus == null) bonus = num(it.groupValues[1]) }
            left.find(s)?.let { total = it.groupValues[2].toInt() }
            val h = head.find(s)
            if (h != null) {
                flush()
                curN = h.groupValues[1].toInt()
                val w = h.groupValues[2]
                curCollect = w.startsWith("Collect", ignoreCase = true) || w.startsWith("Pick", ignoreCase = true)
                curParcel = w.contains("item", ignoreCase = true) || w.startsWith("Pick", true) || w.startsWith("Drop", true)
                continue
            }
            if (curN >= 0) {
                if (isEnd(s)) {
                    val had = buf.isNotEmpty()
                    val coll = curCollect
                    flush()
                    val m = sender.find(s)
                    if (m != null && had && coll) {
                        val inline = m.groupValues[1].trim()
                        if (inline.isNotEmpty()) stops[stops.size - 1] = stops.last().copy(name = clean(inline))
                        else wantName = stops.size - 1
                    }
                } else buf.add(s)
            }
        }
        flush()
        return Parsed(fare, bonus, total, stops)
    }

    private fun dedupe(s: String): String {
        val seen = HashSet<String>()
        return s.split(',').map { it.trim() }.filter { it.isNotEmpty() && seen.add(it.lowercase()) }.joinToString(", ")
    }

    private fun clean(s: String): String =
        dedupe(s.replace(Regex("""\.\s*,"""), ","))
            .replace(Regex("""\[[^\]]*\]"""), " ")
            .replace(Regex("""\(\s*Ground Floor\s*\)""", RegexOption.IGNORE_CASE), " ")
            .replace("lpoh", "Ipoh")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun build(n: Int, collect: Boolean, lines: List<String>, parcel: Boolean): Stop {
        if (parcel) {
            // Parcel orders show only the address; the shop name comes later for pickups.
            val full = clean(lines.joinToString(" "))
            val parts = full.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val nm = parts.firstOrNull { !unitLike.containsMatchIn(it) } ?: parts.firstOrNull() ?: full
            return Stop(n, collect, nm, full)
        }
        val idx = lines.indexOfFirst { it.contains(',') }
        val name: String
        val addr: String
        if (idx > 0) {
            name = lines.subList(0, idx).joinToString(" ")
            addr = lines.subList(idx, lines.size).joinToString(" ")
        } else {
            name = lines.first()
            addr = if (lines.size > 1) lines.drop(1).joinToString(" ") else lines.first()
        }
        return Stop(n, collect, clean(name), clean(addr))
    }
}
