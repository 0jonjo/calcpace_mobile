package app.calcpace.health

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HcLinkGateTest {
    private var now = 1_000_000L
    private val gate = HcLinkGate { now }

    @Test
    fun closedUntilTheAthleteSaysYes() {
        assertFalse(gate.consume())
    }

    @Test
    fun oneLinkPerYes() {
        gate.open()
        assertTrue(gate.consume())
        assertFalse(gate.consume())
    }

    @Test
    fun theYesLastsFiveMinutes() {
        gate.open()
        now += HcLinkGate.WINDOW_MS
        assertTrue(gate.consume())

        gate.open()
        now += HcLinkGate.WINDOW_MS + 1
        assertFalse(gate.consume())
        now += 1
        assertFalse(gate.consume())
    }

    @Test
    fun aClockGoingBackDoesNotOpenIt() {
        gate.open()
        now -= 1
        assertFalse(gate.consume())
    }

    @Test
    fun aNewYesRestartsTheWindow() {
        gate.open()
        now += HcLinkGate.WINDOW_MS
        gate.open()
        now += HcLinkGate.WINDOW_MS
        assertTrue(gate.consume())
    }
}
