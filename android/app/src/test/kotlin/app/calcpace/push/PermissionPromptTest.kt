package app.calcpace.push

import app.calcpace.push.PermissionPrompt.Outcome.BLOCKED
import app.calcpace.push.PermissionPrompt.Outcome.CAN_ASK_AGAIN
import app.calcpace.push.PermissionPrompt.Outcome.DISMISSED
import app.calcpace.push.PermissionPrompt.Outcome.GRANTED
import org.junit.Assert.assertEquals
import org.junit.Test

class PermissionPromptTest {
    private val human = 3_000L
    private val instant = 120L

    private fun outcome(granted: Boolean = false, before: Boolean, after: Boolean, elapsedMs: Long = human) =
        PermissionPrompt.outcome(granted, before, after, elapsedMs)

    @Test
    fun aGrantIsAGrant() {
        assertEquals(GRANTED, outcome(granted = true, before = false, after = false))
        assertEquals(GRANTED, outcome(granted = true, before = true, after = false, elapsedMs = instant))
    }

    // Android 13+: the first "Don't allow" turns the rationale on, and the
    // system will still ask once more.
    @Test
    fun aFirstDenialCanBeAskedAgain() {
        assertEquals(CAN_ASK_AGAIN, outcome(before = false, after = true))
    }

    // Dismissing the prompt on a later ask doesn't count as a denial.
    @Test
    fun aDismissedSecondPromptCanBeAskedAgain() {
        assertEquals(CAN_ASK_AGAIN, outcome(before = true, after = true))
    }

    // The second "Don't allow" turns the rationale off for good.
    @Test
    fun theSecondDenialBlocks() {
        assertEquals(BLOCKED, outcome(before = true, after = false))
        assertEquals(BLOCKED, outcome(before = true, after = false, elapsedMs = instant))
    }

    // Back or a tap outside the first prompt (Android 11+) leaves everything
    // as it was: still "default".
    @Test
    fun aDismissedFirstPromptChangesNothing() {
        assertEquals(DISMISSED, outcome(before = false, after = false))
        assertEquals(DISMISSED, outcome(before = false, after = false, elapsedMs = PermissionPrompt.NO_PROMPT_MS))
    }

    // Blocked before this app ever asked (the TWA days, a restore): the
    // system answers "no" without drawing anything, faster than anyone can
    // dismiss a prompt. One dead tap, then the card goes.
    @Test
    fun anInstantRefusalWithNoRationaleMeansAlreadyBlocked() {
        assertEquals(BLOCKED, outcome(before = false, after = false, elapsedMs = instant))
        assertEquals(BLOCKED, outcome(before = false, after = false, elapsedMs = PermissionPrompt.NO_PROMPT_MS - 1))
        assertEquals(BLOCKED, outcome(before = false, after = false, elapsedMs = 0))
    }
}
