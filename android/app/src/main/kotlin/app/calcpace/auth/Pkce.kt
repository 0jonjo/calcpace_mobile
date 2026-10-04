package app.calcpace.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * The verifier/challenge pair of RFC 7636, reused for handing a sign-in from
 * the browser tab back to the app: the site binds the one-time ticket to the
 * challenge, and only this app ever held the verifier.
 */
object Pkce {
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    /** 32 random bytes, base64url: 43 characters. */
    fun newVerifier(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return encoder.encodeToString(bytes)
    }

    fun challengeFor(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return encoder.encodeToString(digest)
    }
}
