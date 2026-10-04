package app.calcpace.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingCallbacksTest {
    @Test
    fun onlyTheFirstCallerLaunchesAndEveryoneGetsTheOneResult() {
        val pending = PendingCallbacks<Boolean>()
        val got = mutableListOf<String>()

        assertTrue(pending.enqueue { got += "a:$it" })
        assertFalse(pending.enqueue { got += "b:$it" })
        assertFalse(pending.enqueue { got += "c:$it" })
        pending.resolve(true)

        assertEquals(listOf("a:true", "b:true", "c:true"), got)
    }

    @Test
    fun afterAResultTheNextCallerLaunchesAgain() {
        val pending = PendingCallbacks<Boolean>()
        val got = mutableListOf<Boolean>()

        assertTrue(pending.enqueue { got += it })
        pending.resolve(false)
        assertTrue(pending.enqueue { got += it })
        pending.resolve(true)

        assertEquals(listOf(false, true), got)
    }

    @Test
    fun aResultWithNobodyWaitingIsIgnored() {
        PendingCallbacks<Boolean>().resolve(true)
    }

    // A callback that asks again (say, replies and the page re-sends) must
    // start a fresh request, not join the one being resolved.
    @Test
    fun aCallbackThatEnqueuesStartsAFreshRequest() {
        val pending = PendingCallbacks<Boolean>()
        var relaunched: Boolean? = null
        pending.enqueue { relaunched = pending.enqueue { } }
        pending.resolve(true)
        assertEquals(true, relaunched)
    }
}
