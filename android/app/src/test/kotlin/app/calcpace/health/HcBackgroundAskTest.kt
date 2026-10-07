package app.calcpace.health

import app.calcpace.health.HcBackgroundAsk.Step
import org.junit.Assert.assertEquals
import org.junit.Test

class HcBackgroundAskTest {
    private val ask = HcBackgroundAsk()

    @Test
    fun nothingToDoWhenAllowedOrMissing() {
        assertEquals(Step.NOTHING, ask.next(true))
        assertEquals(Step.NOTHING, ask.next(null))
    }

    @Test
    fun theFirstTapAsksHealthConnect() {
        assertEquals(Step.REQUEST, ask.next(false))
    }

    @Test
    fun aRefusalSendsOnlyTheNextTapToTheSettings() {
        assertEquals(Step.REQUEST, ask.next(false))
        ask.requested(false)
        assertEquals(Step.SETTINGS, ask.next(false))
        // Back from the settings without allowing it: the settings again, not a request that comes back at once.
        assertEquals(Step.SETTINGS, ask.next(false))
    }

    @Test
    fun anAllowedRequestStartsOver() {
        ask.next(false)
        ask.requested(true)
        assertEquals(Step.REQUEST, ask.next(false))
    }

    @Test
    fun seeingItAllowedForgetsTheRefusal() {
        ask.next(false)
        ask.requested(false)
        // Allowed in the settings, later switched off again.
        assertEquals(Step.NOTHING, ask.next(true))
        assertEquals(Step.REQUEST, ask.next(false))
    }

    @Test
    fun aPhoneThatLostTheFeatureForgetsTheRefusal() {
        ask.next(false)
        ask.requested(false)
        assertEquals(Step.NOTHING, ask.next(null))
        assertEquals(Step.REQUEST, ask.next(false))
    }

    @Test
    fun aRequestThatEndsWithoutTheFeatureIsNoRefusal() {
        ask.next(false)
        ask.requested(null)
        assertEquals(Step.REQUEST, ask.next(false))
    }
}
