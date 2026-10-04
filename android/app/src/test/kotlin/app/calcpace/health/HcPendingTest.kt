package app.calcpace.health

import org.junit.Assert.assertEquals
import org.junit.Test

class HcPendingTest {
    private val now = 1_800_000_000L

    @Test
    fun addKeepsTheFirstSighting() {
        val once = HcPending.add(emptyMap(), listOf("a"), now)
        assertEquals(mapOf("a" to now, "b" to now + 60), HcPending.add(once, listOf("a", "b"), now + 60))
    }

    @Test
    fun removeDropsTheIdsThatAreDone() {
        assertEquals(mapOf("b" to now), HcPending.remove(mapOf("a" to now, "b" to now), listOf("a", "c")))
    }

    @Test
    fun expireGivesUpAfter48Hours() {
        val pending = mapOf("old" to now - 48 * 3600 - 1, "edge" to now - 48 * 3600, "new" to now - 60)
        assertEquals(mapOf("edge" to now - 48 * 3600, "new" to now - 60), HcPending.expire(pending, now))
        assertEquals(mapOf("new" to now - 60), HcPending.expire(pending, now, maxAgeSeconds = 3600))
    }

    @Test
    fun atMostAHundredWaitAndTheNewestStay() {
        val old = (1..100).associate { "old$it" to now - it }
        val pending = HcPending.add(old, listOf("new1", "new2"), now)
        assertEquals(HcPending.MAX_ENTRIES, pending.size)
        assertEquals(now, pending["new1"])
        assertEquals(now, pending["new2"])
        assertEquals(null, pending["old100"])
        assertEquals(null, pending["old99"])
    }

    @Test
    fun itRoundTripsThroughJson() {
        val pending = mapOf("6f1c-uuid" to now, "0a9e" to now - 5)
        assertEquals(pending, HcPending.decode(HcPending.encode(pending)))
    }

    @Test
    fun anythingUnreadableIsNothingPending() {
        listOf(null, "", "nope", "[1,2]", "{\"a\":\"x\"}").forEach { assertEquals("$it", emptyMap<String, Long>(), HcPending.decode(it)) }
    }
}
