package app.calcpace.auth

import app.calcpace.auth.AppAuthRouteDecisionHandler.Companion.APP_AUTH
import app.calcpace.auth.AppAuthRouteDecisionHandler.Companion.PROVIDER
import app.calcpace.auth.AppAuthRouteDecisionHandler.Companion.REDEEM
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppAuthPathTest {
    private fun provider(path: String) = PROVIDER.matchEntire(path)?.groupValues?.get(1)

    @Test
    fun signInButtonsMatchWithOrWithoutLocale() {
        assertEquals("google", provider("/app_auth/google"))
        assertEquals("strava", provider("/app_auth/strava/"))
        assertEquals("google", provider("/pt-BR/app_auth/google"))
        assertEquals("strava", provider("/de/app_auth/strava"))
        assertEquals(null, provider("/app_auth/facebook"))
    }

    @Test
    fun theWholeAppAuthTreeIsGuarded() {
        listOf("/app_auth/start", "/app_auth/redeem", "/pt-BR/app_auth/redeem", "/app_auth/callback", "/app_auth")
            .forEach { assertTrue(it, APP_AUTH.matches(it)) }
        assertTrue(REDEEM.matches("/es/app_auth/redeem"))
    }

    @Test
    fun ordinaryPagesAreNotGuarded() {
        listOf("/", "/pt-BR", "/auth/google", "/news/app_auth/google", "/app_authors")
            .forEach { assertFalse(it, APP_AUTH.matches(it)) }
    }
}
