package app.calcpace.main

import app.calcpace.main.IncomingLink.BrowserTab
import app.calcpace.main.IncomingLink.Ignore
import app.calcpace.main.IncomingLink.SignIn
import app.calcpace.main.IncomingLink.Web
import org.junit.Assert.assertEquals
import org.junit.Test

class IncomingLinkTest {
    private val base = "https://calcpace.app"
    private val ticket = "t3xOHP60nCIs5golfu10UwzzY8aZSDSkcCQ7n4nnUl4"

    private fun classify(url: String) = IncomingLink.classify(url, base)

    @Test
    fun aSignInCallbackCarriesItsTicket() {
        assertEquals(SignIn(ticket), classify("$base/app_auth/callback?ticket=$ticket"))
        assertEquals(SignIn(ticket), classify("$base/pt-BR/app_auth/callback?x=1&ticket=$ticket"))
    }

    @Test
    fun aCallbackWithoutAUsableTicketDoesNothing() {
        assertEquals(Ignore, classify("$base/app_auth/callback"))
        assertEquals(Ignore, classify("$base/app_auth/callback?ticket=short"))
        assertEquals(Ignore, classify("$base/app_auth/callback?ticket=has%20space%20in%20it%20padding"))
    }

    // The attack the review found: a redeem link from outside must never
    // reach the WebView, whatever ticket and verifier it carries.
    @Test
    fun redeemAndStartLinksFromOutsideAreRefused() {
        assertEquals(Ignore, classify("$base/app_auth/redeem?ticket=$ticket&verifier=x"))
        assertEquals(Ignore, classify("$base/en/app_auth/redeem?ticket=$ticket&verifier=x"))
        assertEquals(Ignore, classify("$base/app_auth/start?provider=strava&challenge=x"))
        assertEquals(Ignore, classify("$base/app_auth/google"))
    }

    // Round-2 review: "//app_auth/redeem" reached Rails' redeem action and
    // slipped past an exact-path check.
    @Test
    fun nonCanonicalAppAuthLinksAreRefusedToo() {
        listOf(
            "//app_auth/redeem", "/en//app_auth/redeem", "/app_auth//redeem", "/./app_auth/redeem",
            "/x/../app_auth/redeem", "/%2e/app_auth/redeem", "/%61pp_auth/redeem", "//app_auth/callback"
        ).forEach { assertEquals(it, Ignore, classify("$base$it?ticket=$ticket&verifier=x")) }
    }

    @Test
    fun providerStepsGoBackToTheBrowserTab() {
        val url = "$base/en/auth/strava/callback?code=1&state=2"
        assertEquals(BrowserTab(url), classify(url))
        assertEquals(BrowserTab("$base/auth/google/callback"), classify("$base/auth/google/callback"))
    }

    @Test
    fun ordinaryPagesOpenInTheWebView() {
        assertEquals(Web("$base/pt-BR/race-calendar"), classify("$base/pt-BR/race-calendar"))
        assertEquals(Web("$base/authors"), classify("$base/authors"))
    }

    @Test
    fun otherSitesAndSchemesAreIgnored() {
        assertEquals(Ignore, classify("http://calcpace.app/pt-BR"))
        assertEquals(Ignore, classify("https://evil.example/app_auth/callback?ticket=$ticket"))
        assertEquals(Ignore, classify("https://calcpace.app:8443/pt-BR"))
        assertEquals(Ignore, classify("calcpace://auth?ticket=$ticket"))
        assertEquals(Ignore, classify("not a url"))
    }

    @Test
    fun aLocalServerWorksInDebug() {
        val local = "http://10.0.2.2:3001"
        assertEquals(SignIn(ticket), IncomingLink.classify("$local/app_auth/callback?ticket=$ticket", local))
        assertEquals(Ignore, IncomingLink.classify("http://10.0.2.2:3000/", local))
    }
}
