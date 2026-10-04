package app.calcpace.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PkceTest {
    @Test
    fun challengeMatchesRfc7636AppendixB() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            Pkce.challengeFor("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")
        )
    }

    @Test
    fun verifierIs43UrlSafeCharacters() {
        val verifier = Pkce.newVerifier()
        assertEquals(43, verifier.length)
        assertTrue(verifier.matches(Regex("^[A-Za-z0-9_-]+$")))
    }

    @Test
    fun verifiersDoNotRepeat() {
        assertNotEquals(Pkce.newVerifier(), Pkce.newVerifier())
    }
}
