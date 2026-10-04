package app.calcpace.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PkceTest {
    // The same pair calcpace_web's tests use, so both sides agree on the
    // encoding (base64url, no padding, SHA-256 of the ASCII verifier).
    @Test
    fun challengeMatchesTheSiteVector() {
        assertEquals(
            "P-rITZl1iFA1jnXaCQF2ExWGIXpzhiTyDvxPa8SWOrI",
            Pkce.challengeFor("dBjftJeZ4CVP-mJ92K9A2PSWnAZ8YYbC6MnS8Yq6pR8")
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
