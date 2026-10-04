package app.calcpace.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AppAuthPathTest {
    private fun provider(path: String) =
        AppAuthRouteDecisionHandler.PATH.matchEntire(path)?.groupValues?.get(1)

    @Test
    fun matchesBothProvidersWithOrWithoutLocale() {
        assertEquals("google", provider("/app_auth/google"))
        assertEquals("strava", provider("/app_auth/strava/"))
        assertEquals("google", provider("/pt-BR/app_auth/google"))
        assertEquals("strava", provider("/de/app_auth/strava"))
    }

    @Test
    fun leavesEverythingElseToTheWebView() {
        assertFalse(AppAuthRouteDecisionHandler.PATH.matches("/app_auth/start"))
        assertFalse(AppAuthRouteDecisionHandler.PATH.matches("/app_auth/redeem"))
        assertFalse(AppAuthRouteDecisionHandler.PATH.matches("/auth/google"))
        assertFalse(AppAuthRouteDecisionHandler.PATH.matches("/app_auth/facebook"))
        assertFalse(AppAuthRouteDecisionHandler.PATH.matches("/news/app_auth/google"))
    }
}
