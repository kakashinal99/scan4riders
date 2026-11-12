package my.fouriders.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GrabTemplateParserTest {
    @Test fun grabFoodMultiStopScreenReadsFareAndBothVisibleStops() {
        val parsed = Parser.parseLines(listOf(
            "You'll earn RM 14.10", "+ RM 1.20", "0/3 stops to go",
            "• 1. Collect from", "Noodlicious by 1977 - Desa Sri", "Hartamas [Non-Halal]",
            "72 (Ground Floor) , Jalan 27/70A, Desa Sri", "Hartamas, Mont Kiara & Hartamas, 50480",
            "• Arrive by", "11:30 AM", "Order ID", "GF-691, GF-882", "Service", "GrabFood",
            "• 2. Deliver to", "Marco Corporation (M) Sdn. Bhd. -", "Menara JKG",
            "Jalan Raja Laut, Bandar Kuala Lumpur, 50350,", "WP Kuala Lumpur, WP Kuala Lumpur, Malaysia",
            "Aaron • # 5.0"
        ))

        assertEquals(14.10, parsed.fare!!, 0.001)
        assertEquals(1.20, parsed.bonus!!, 0.001)
        assertEquals(3, parsed.total)
        assertEquals(2, parsed.stops.size)
        assertTrue(parsed.stops[0].collect)
        assertFalse(parsed.stops[1].collect)
        assertTrue(parsed.stops[0].address.contains("Jalan 27/70A"))
        assertFalse(parsed.stops[1].address.contains("Aaron"))
    }

    @Test fun grabExpressPickupAndScrolledDropoffMergeIntoOneOrder() {
        val first = Parser.parseLines(listOf(
            "Current net earnings: RM5.20", "2/2 stops to go", "• 1. Pick Up Item",
            "LOT CC.10D, LEVEL CONCOURSE, NU", "SENTRAL SHOPPING CENTRE, JALAN",
            "TUN SAMBATHAN, 50470 KUALA", "LUMPUR., Kuala Lumpur, Kuala Lumpur",
            "Sender's name:", "CHAGEE MY Nu Sentral", "Order ID",
            "IN-2-0E5FJISDEERJ6NXFSTT5"
        ))
        val second = Parser.parseLines(listOf(
            "• 2. Drop Off Item", "Villa Scott Condominium, 33, Jalan Scott,",
            "Brickfields, 50470 Kuala Lumpur, Wilayah", "Persekutuan Kuala Lumpur",
            "Recipient's name:", "Chi*********", "Order ID", "IN-2-OE5FJISDEERJ6NXFSTT5"
        ))
        Session.reset()
        Session.merge(first, 1_000L)
        Session.merge(second, 2_000L)

        assertEquals(5.20, Session.fare!!, 0.001)
        assertEquals(2, Session.total)
        assertEquals(listOf(1, 2), Session.stops.keys.toList())
        assertTrue(Session.stops[1]!!.collect)
        assertFalse(Session.stops[2]!!.collect)
        assertTrue(Session.stops[2]!!.address.contains("Jalan Scott"))
        assertTrue(Session.missing().isEmpty())
    }
}
