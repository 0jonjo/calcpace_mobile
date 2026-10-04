package app.calcpace.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HcStateTest {
    private val token = "a".repeat(43)
    private val other = "b".repeat(43)
    private val prefs = FakePrefs()
    private val state = HcState(prefs)

    @Test
    fun aNewLinkStartsOver() {
        state.link(token)
        state.advance(token, "changes-1", mapOf("p" to 1L))
        assertTrue(state.link(other))
        assertEquals(other, state.linkToken)
        assertNull(state.changesToken)
        assertEquals(emptyMap<String, Long>(), state.pending)
    }

    @Test
    fun aMalformedTokenIsNeverKept() {
        assertFalse(state.link("short"))
        assertNull(state.linkToken)
        prefs.values["link_token"] = "x y"
        assertNull(state.linkToken)
    }

    @Test
    fun advanceWritesTheChangesTokenAndThePendingTogether() {
        state.link(token)
        state.advance(token, "changes-1", mapOf("p" to 1L))
        assertEquals("changes-1", state.changesToken)
        assertEquals(mapOf("p" to 1L), state.pending)
    }

    @Test
    fun aSyncOfAnOldLinkChangesNothingOfANewOne() {
        state.link(token)
        state.link(other)
        state.advance(token, "changes-1", mapOf("p" to 1L))
        assertNull(state.changesToken)
        assertEquals(emptyMap<String, Long>(), state.pending)

        state.advance(other, "changes-2", emptyMap())
        state.forgetChanges(token)
        assertEquals("changes-2", state.changesToken)
        assertFalse(state.clear(onlyIfLinkToken = token))
        assertEquals(other, state.linkToken)
    }

    @Test
    fun forgetChangesKeepsTheLink() {
        state.link(token)
        state.advance(token, "changes-1", mapOf("p" to 1L))
        state.forgetChanges(token)
        assertNull(state.changesToken)
        assertEquals(token, state.linkToken)
        assertEquals(mapOf("p" to 1L), state.pending)
    }

    @Test
    fun clearForgetsEverything() {
        state.link(token)
        state.advance(token, "changes-1", emptyMap())
        assertTrue(state.clear(onlyIfLinkToken = token))
        assertNull(state.linkToken)
        assertNull(state.changesToken)
        state.link(other)
        assertTrue(state.clear())
        assertEquals(emptyMap<String, String>(), prefs.values)
    }
}
