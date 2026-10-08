package app.calcpace.main

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserForwardsTest {
    private val step = "https://calcpace.app/auth/strava/callback?code=1&state=2"

    // A browser that hands calcpace.app links back to the app: the second
    // arrival is the bounce, and stops there.
    @Test
    fun theSameStepBouncingBackIsNotSentAgain() {
        val forwards = BrowserForwards(windowMs = 10_000)
        assertTrue(forwards.take(step, nowMs = 1_000))
        assertFalse(forwards.take(step, nowMs = 1_200))
        assertFalse(forwards.take(step, nowMs = 10_999))
    }

    @Test
    fun theSameStepLaterGoesAgain() {
        val forwards = BrowserForwards(windowMs = 10_000)
        assertTrue(forwards.take(step, nowMs = 1_000))
        assertTrue(forwards.take(step, nowMs = 11_000))
    }

    @Test
    fun aDifferentStepAlwaysGoes() {
        val forwards = BrowserForwards(windowMs = 10_000)
        assertTrue(forwards.take(step, nowMs = 1_000))
        assertTrue(forwards.take("https://calcpace.app/auth/google/callback?code=3&state=4", nowMs = 1_100))
        assertTrue(forwards.take(step, nowMs = 1_200))
    }

    // elapsedRealtime never goes back, but a clock that did must not lock a step out.
    @Test
    fun aClockGoingBackDoesNotBlock() {
        val forwards = BrowserForwards(windowMs = 10_000)
        assertTrue(forwards.take(step, nowMs = 5_000))
        assertTrue(forwards.take(step, nowMs = 4_000))
    }
}
