package app.calcpace.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppAuthPathTest {
    private val site = "https://calcpace.app"
    private val host = "calcpace.app"

    @Test
    fun signInButtonsMatchWithOrWithoutLocale() {
        assertEquals("google", AppAuthPaths.providerOf("$site/app_auth/google"))
        assertEquals("strava", AppAuthPaths.providerOf("$site/pt-BR/app_auth/strava"))
        assertEquals("google", AppAuthPaths.providerOf("$site/de/app_auth/google"))
        assertEquals(null, AppAuthPaths.providerOf("$site/app_auth/facebook"))
        assertEquals(null, AppAuthPaths.providerOf("$site/xx/app_auth/google"))
    }

    @Test
    fun onlyTheExactRedeemAndCallbackPathsCount() {
        assertTrue(AppAuthPaths.isRedeem("$site/app_auth/redeem?ticket=a&verifier=b"))
        assertTrue(AppAuthPaths.isRedeem("$site/es/app_auth/redeem"))
        assertTrue(AppAuthPaths.isCallback("$site/app_auth/callback?ticket=a"))
        listOf("//app_auth/redeem", "/en//app_auth/redeem", "/app_auth//redeem", "/app_auth/redeem/", "/./app_auth/redeem")
            .forEach { assertFalse(it, AppAuthPaths.isRedeem("$site$it")) }
        assertFalse(AppAuthPaths.isCallback("$site//app_auth/callback?ticket=a"))
    }

    // Round-2 review: Rails collapses "//", the WebView resolves "/./" and
    // "%2e", so every spelling of an app_auth path has to be guarded.
    @Test
    fun everySpellingOfAnAppAuthPathIsGuarded() {
        listOf(
            "/app_auth/redeem", "//app_auth/redeem", "/en//app_auth/redeem", "/app_auth//redeem",
            "/./app_auth/redeem", "/x/../app_auth/redeem", "/%2e/app_auth/redeem", "/%61pp_auth/redeem",
            "/APP_AUTH/redeem", "/app_auth/start", "/app_auth/callback", "/pt-BR/app_auth/google"
        ).forEach { assertTrue(it, AppAuthPaths.isGuarded("$site$it?ticket=a&verifier=b", host)) }
    }

    @Test
    fun guardingIsByHostAloneSoNoSchemeOrPortSlipsPast() {
        assertTrue(AppAuthPaths.isGuarded("http://calcpace.app/app_auth/redeem", host))
        assertTrue(AppAuthPaths.isGuarded("https://calcpace.app:443/app_auth/redeem", host))
        assertFalse(AppAuthPaths.isGuarded("https://evil.example/app_auth/redeem", host))
    }

    @Test
    fun ordinaryPagesAreNotGuarded() {
        listOf("/", "/pt-BR", "/auth/google", "/authors", "/pt-BR/race-calendar")
            .forEach { assertFalse(it, AppAuthPaths.isGuarded("$site$it", host)) }
    }

    @Test
    fun providerStepsAreRecognisedHoweverSpelled() {
        assertTrue(AppAuthPaths.isProviderStep("$site/auth/google/callback"))
        assertTrue(AppAuthPaths.isProviderStep("$site/en/auth/strava/callback"))
        assertTrue(AppAuthPaths.isProviderStep("$site//en//auth/strava/callback"))
        assertFalse(AppAuthPaths.isProviderStep("$site/authors"))
    }
}
