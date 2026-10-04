package app.calcpace.health

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Whether the "health" bridge component may take a "link" now, and from
 * whom. The WebView's JavaScript bridge is reachable from any frame of the
 * page, and HcPages only sees the main frame's URL, so a frame must not be
 * able to hand the app another account's token and collect this phone's
 * runs there.
 *
 * A granted "enable" (Health Connect's screen, or the app's own
 * confirmation when nothing was left to ask) opens the gate with a fresh
 * random grant, which goes back only in that "enable" reply, to the
 * caller's callback. A "link" is taken only with that grant, once, within
 * [WINDOW_MS]. Any "link" closes the gate, right or wrong: a frame guessing
 * grants only spoils the athlete's attempt, who taps again.
 *
 * One per component instance, main thread. Kept free of Android types so it
 * is unit tested on the JVM.
 */
class HcLinkGate(
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: (ByteArray) -> Unit = SECURE_RANDOM::nextBytes,
) {
    private var grant: String? = null
    private var openedAt = 0L

    /**
     * The athlete just allowed the import on this page: the grant the
     * "enable" reply carries. While one is still open (unused, unexpired) it
     * is handed out again, so a second "enable" (a double tap) doesn't void
     * the first one's grant; a new one only after a link, a close or expiry.
     */
    fun open(): String {
        grant?.takeIf { clock() - openedAt in 0..WINDOW_MS }?.let { return it }
        val bytes = ByteArray(GRANT_BYTES).also(random)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).also {
            grant = it
            openedAt = clock()
        }
    }

    /** True for the open gate's own grant within [WINDOW_MS] of [open]. Closes the gate either way. */
    fun consume(candidate: String?): Boolean {
        val expected = grant ?: return false
        grant = null
        if (candidate == null || clock() - openedAt !in 0..WINDOW_MS) return false
        return MessageDigest.isEqual(expected.toByteArray(), candidate.toByteArray())
    }

    companion object {
        const val WINDOW_MS = 5 * 60 * 1000L
        /** 256 bits: 43 base64url characters. */
        const val GRANT_BYTES = 32
        private val SECURE_RANDOM = SecureRandom()
    }
}
