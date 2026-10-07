package app.calcpace.main

import app.calcpace.main.NotificationLaunch.Plan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationLaunchTest {
    private val base = "https://calcpace.app"
    private val run = "$base/pt-BR/activities/123"

    private fun plan(
        url: String? = run,
        fromNotification: Boolean = true,
        fromHistory: Boolean = false,
        restored: Boolean = false,
    ) = NotificationLaunch.plan(url, fromNotification, fromHistory, restored, base)

    @Test
    fun aColdTapStartsAtHomeWithTheRunOnTop() {
        assertEquals(Plan(base, run), plan())
        assertEquals(Plan(base, "$base/activities/9?tab=splits#map"), plan(url = "$base/activities/9?tab=splits#map"))
    }

    @Test
    fun otherLinksStartWhereTheyPoint() {
        assertNull(plan(fromNotification = false))
        assertNull(plan(url = null))
    }

    @Test
    fun onlyAnOrdinaryPageOfTheSiteQualifies() {
        // A marked intent from elsewhere is still held to IncomingLink's rules.
        assertNull(plan(url = "https://evil.example/pt-BR/activities/123"))
        assertNull(plan(url = "$base/app_auth/redeem?ticket=t3xOHP60nCIs5golfu10UwzzY8aZSDSkcCQ7n4nnUl4"))
        assertNull(plan(url = "$base/app_auth/callback?ticket=t3xOHP60nCIs5golfu10UwzzY8aZSDSkcCQ7n4nnUl4"))
        assertNull(plan(url = "not a url"))
    }

    @Test
    fun theHomeItselfIsNotPutOnTopOfTheHome() {
        assertEquals(Plan(base, null), plan(url = base))
        assertEquals(Plan(base, null), plan(url = "$base/"))
        assertEquals(Plan(base, null), plan(url = "$base/pt-BR"))
        assertEquals(Plan(base, null), plan(url = "$base/en?ref=push"))
    }

    @Test
    fun aRestoredActivityGetsItsBackStackBackAndNothingMore() {
        assertEquals(Plan(base, null), plan(restored = true))
    }

    @Test
    fun reopenedFromRecentsIsHomeOnly() {
        assertEquals(Plan(base, null), plan(fromHistory = true))
    }
}
