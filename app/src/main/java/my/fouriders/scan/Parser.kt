package my.fouriders.scan

import com.google.mlkit.vision.text.Text

/** Turns the text read from a Grab "Route details" screen into fare and stops. */
object Parser {
    private val head = Regex("""^\s*(?:[OoQ0●•©®]\s+)?([0-9OIl]{1,2})\s*[.,]?\s*(Collect from|Deliver to|Pick[\s-]?up item|Drop[\s-]?off item|Pick[\s-]?up|Drop[\s-]?off|Collect|Deliver)""", RegexOption.IGNORE_CASE)
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
    private val rating = Regex("""^.{1,40}[\s.·•]+#?\s*[0-5][.,]\d$""")
    // An id must contain a digit, or an all-capitals address line would be mistaken for one.
    private val ids = Regex("""^(?=.*\d)(?:[A-Z]{1,3}-?[0-9A-Z]{2,}(?:[,\s]+[A-Z]{1,3}-?[0-9A-Z]+)*|[A-Z]{1,3}(?:-[0-9A-Z]+)+)$""")
    private val noise = Regex("""detected this screenshot|no order found|stops?\s*[·.]?\s*scroll|stops not seen|fare not seen|finding route|reading\.\.\.""", RegexOption.IGNORE_CASE)

    private class Ln(val top: Int, val left: Int, val s: String)

    private fun orderedLines(t: Text): List<String> {
        val all = ArrayList<Ln>()
        var missingBounds = false
        for (b in t.textBlocks) {
            for (l in b.lines) {
                val s = l.text.trim()
                if (s.isEmpty()) continue
                val box = l.boundingBox
                if (box == null) missingBounds = true else all.add(Ln(box.top, box.left, s))
            }
        }
        // Some device/OS combinations return OCR lines without geometry. Do not silently
        // discard those lines: the plain ML Kit transcript still contains usable route text.
        if (missingBounds || all.isEmpty()) return t.text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        return all.sortedWith(compareBy<Ln>({ it.top }, { it.left })).map { it.s }
    }

    private fun isEnd(s: String): Boolean =
        labels.matches(s.trimStart(' ', '\t', '•', '●', '·')) || parties.containsMatchIn(s) || time.matches(s) || rating.matches(s) || ids.matches(s) ||
            earn.containsMatchIn(s) || plus.containsMatchIn(s) || left.containsMatchIn(s)

    private fun num(s: String): Double = s.replace(',', '.').toDouble()

    fun parse(t: Text): Parsed {
        val positioned = parseLines(orderedLines(t))
        val plain = parseLines(t.text.lineSequence().toList())
        // Prefer the transcript that yields the most stops. Android builds can order
        // text blocks differently, and choosing a single representation caused a
        // valid route to look empty on some devices.
        return listOf(positioned, plain).maxByOrNull { p ->
            p.stops.size * 100 + (if (p.fare != null) 20 else 0) +
                (if (p.total != null) 10 else 0) + p.stops.sumOf { it.address.length.coerceAtMost(30) }
        } ?: positioned
    }

    /** Kept separate so real Grab OCR transcripts can be regression-tested without a device. */
    internal fun parseLines(lines: List<String>): Parsed {
        val ls = lines.map { it.trim() }.filter { it.isNotEmpty() && !noise.containsMatchIn(it) }
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
                curN = h.groupValues[1].replace('O', '0').replace('o', '0').replace('I', '1').replace('l', '1').toIntOrNull() ?: -1
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

    /** Keep address tokens as OCR read them; only join wrapped lines and normalize spacing. */
    private fun cleanAddress(lines: List<String>): String = lines
        .joinToString(" ")
        .replace(Regex("""\s+"""), " ")
        .replace(Regex("""\s+([,.;])"""), "\$1")
        .replace(Regex("""([,;])(?=\S)"""), "\$1 ")
        .trim()

    /** Return only a clearly address-like OCR string; fail closed on place names/labels. */
    fun addressForCopy(stop: Stop): String? {
        val address = stop.address.trim()
        if (address.isBlank()) return null
        if (Regex("""^(?:\d+\s*[.,]\s*)?(?:collect from|deliver to|pick[\s-]?up|drop[\s-]?off)\b""", RegexOption.IGNORE_CASE).containsMatchIn(address)) return null
        if (Regex("""\b(?:pickup|drop-off)\s+\d+\b""", RegexOption.IGNORE_CASE).containsMatchIn(address)) return null
        val streetOrUnit = Regex("""\b(jalan|jln|lorong|lrg|persiaran|lebuh(?:raya)?|taman|kampung|kg\.?|lot|unit|blok|block|level|floor|no\.?)\b""", RegexOption.IGNORE_CASE)
        val strongSignal = streetOrUnit.containsMatchIn(address) || Regex("""\b\d{5}\b|,""").containsMatchIn(address)
        if (!strongSignal) return null
        if (address.equals(stop.name.trim(), ignoreCase = true) && !streetOrUnit.containsMatchIn(address)) return null
        return address
    }

    private fun build(n: Int, collect: Boolean, lines: List<String>, parcel: Boolean): Stop {
        if (parcel) {
            // Parcel orders show only the address; the shop name comes later for pickups.
            val full = cleanAddress(lines)
            val parts = full.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val nm = parts.firstOrNull { !unitLike.containsMatchIn(it) } ?: parts.firstOrNull() ?: full
            return Stop(n, collect, nm, full)
        }
        val idx = lines.indexOfFirst { it.contains(',') }
        val name: String
        val addr: String
        if (idx > 0) {
            name = lines.subList(0, idx).joinToString(" ")
            addr = cleanAddress(lines.subList(idx, lines.size))
        } else if (idx == 0) {
            // A comma on the first line is stronger evidence that this line is an address
            // than a place title. Keep it verbatim for copy; never prepend the stop label/name.
            name = lines.first()
            addr = cleanAddress(lines)
        } else if (lines.size > 1) {
            name = lines.first()
            addr = cleanAddress(lines.drop(1))
        } else {
            name = lines.first()
            // One OCR line cannot safely distinguish a place name from an address.
            // Leave it unavailable rather than copying a guessed address.
            addr = ""
        }
        return Stop(n, collect, clean(name), addr)
    }
}
