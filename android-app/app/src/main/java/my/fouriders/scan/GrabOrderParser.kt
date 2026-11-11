package my.fouriders.scan

import org.json.JSONObject
import java.util.Locale

/**
 * Conservative first-pass Grab offer parser.
 * It only emits a candidate when a fare is detected; location labels remain unverified.
 * A rider can review/correct details before the map treats them as navigable coordinates.
 */
object GrabOrderParser {
    private val farePattern = Regex("(?i)(?:RM\\s*)?([0-9]{1,3}(?:\\.[0-9]{2})?)\\s*(?:RM)?")
    private val distancePattern = Regex("(?i)([0-9]+(?:\\.[0-9]+)?)\\s*km")
    private val pickupWords = listOf("pickup", "pick up", "restaurant", "merchant", "from")
    private val dropWords = listOf("drop-off", "dropoff", "drop off", "deliver to", "to")

    fun parse(rawText: String): JSONObject? {
        val lines = rawText.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return null
        val joined = lines.joinToString(" ")
        val fare = farePattern.findAll(joined).mapNotNull { it.groupValues[1].toDoubleOrNull() }
            .filter { it in 1.0..500.0 }.lastOrNull() ?: return null
        val distances = distancePattern.findAll(joined).mapNotNull { it.groupValues[1].toDoubleOrNull() }.toList()
        val pickup = findLabel(lines, pickupWords)
        val dropoff = findLabel(lines, dropWords)
        return JSONObject().apply {
            put("source", "grab_screen_ocr")
            put("fare", String.format(Locale.US, "%.2f", fare).toDouble())
            if (distances.isNotEmpty()) put("rider_to_pickup_km", distances.first())
            if (distances.size > 1) put("pickup_to_dropoff_km", distances[1])
            if (pickup.isNotBlank()) put("pickup_label", pickup)
            if (dropoff.isNotBlank()) put("dropoff_label", dropoff)
            put("pickup_verified", false)
            put("dropoff_verified", false)
            put("raw_scan", JSONObject().put("text", rawText))
        }
    }

    private fun findLabel(lines: List<String>, markers: List<String>): String {
        val index = lines.indexOfFirst { line -> markers.any { line.contains(it, ignoreCase = true) } }
        if (index < 0) return ""
        return lines.drop(index + 1).firstOrNull { it.length >= 3 && !it.matches(Regex("(?i).*RM\\s*[0-9].*")) }.orEmpty()
    }
}
