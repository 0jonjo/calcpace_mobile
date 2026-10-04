package app.calcpace.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HcLinkGateTest {
    private var now = 1_000_000L
    private var seed: Byte = 1
    private val gate = HcLinkGate(clock = { now }, random = { bytes -> bytes.fill(seed++) })

    @Test
    fun closedUntilTheAthleteSaysYes() {
        assertFalse(gate.consume(null))
        assertFalse(gate.consume("anything"))
    }

    @Test
    fun theGrantIs256RandomBitsInBase64Url() {
        val grant = HcLinkGate().open()
        assertTrue(grant, Regex("^[A-Za-z0-9_-]{43}$").matches(grant))
        assertNotEquals(grant, HcLinkGate().open())
    }

    @Test
    fun oneLinkPerGrant() {
        val grant = gate.open()
        assertTrue(gate.consume(grant))
        assertFalse(gate.consume(grant))
    }

    @Test
    fun aWrongGrantClosesTheGate() {
        val grant = gate.open()
        assertFalse(gate.consume(grant.dropLast(1) + if (grant.last() == 'A') "B" else "A"))
        assertFalse(gate.consume(grant))
    }

    @Test
    fun aMissingGrantClosesTheGate() {
        val grant = gate.open()
        assertFalse(gate.consume(null))
        assertFalse(gate.consume(grant))
    }

    @Test
    fun aSecondEnableGetsTheSameGrantWhileItIsOpen() {
        val first = gate.open()
        now += HcLinkGate.WINDOW_MS
        assertEquals(first, gate.open())
        assertTrue(gate.consume(first))
    }

    @Test
    fun aNewGrantOnlyAfterTheOldOneIsUsedClosedOrExpired() {
        val used = gate.open()
        assertTrue(gate.consume(used))
        val afterUse = gate.open()
        assertNotEquals(used, afterUse)

        assertFalse(gate.consume("wrong"))
        val afterClose = gate.open()
        assertNotEquals(afterUse, afterClose)

        now += HcLinkGate.WINDOW_MS + 1
        val afterExpiry = gate.open()
        assertNotEquals(afterClose, afterExpiry)
        assertFalse(gate.consume(afterClose))
    }

    @Test
    fun theReusedGrantKeepsItsFirstDeadline() {
        val grant = gate.open()
        now += HcLinkGate.WINDOW_MS - 1
        gate.open()
        now += 2
        assertFalse(gate.consume(grant))
    }

    @Test
    fun theGrantLastsFiveMinutes() {
        var grant = gate.open()
        now += HcLinkGate.WINDOW_MS
        assertTrue(gate.consume(grant))

        grant = gate.open()
        now += HcLinkGate.WINDOW_MS + 1
        assertFalse(gate.consume(grant))
    }

    @Test
    fun aClockGoingBackDoesNotOpenIt() {
        val grant = gate.open()
        now -= 1
        assertFalse(gate.consume(grant))
    }

    @Test
    fun theGrantComesFromTheRandomSource() {
        val grant = gate.open()
        assertEquals("AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE", grant)
    }
}
