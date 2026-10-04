package app.calcpace.auth

import java.io.ByteArrayOutputStream

/**
 * Path rules for the sign-in hand-off, shared by everything that decides
 * what a calcpace.app URL may do, and kept free of Android types so they are
 * unit tested on the JVM.
 *
 * The site does not route on the raw path: Rails collapses "//" and the
 * WebView resolves "/./" and "%2e" before the request leaves. Matching only
 * the exact path would let "//app_auth/redeem" through to the redeem
 * action. So anything whose cleaned-up path so much as mentions app_auth
 * is guarded, and only the exact canonical paths are let through.
 *
 * URLs are read with a deliberately lenient hand-written parser, never
 * java.net.URI: the WebView happily follows "…/app_auth/redeem?…#%zz", which
 * java.net.URI refuses to parse, and a guard that gives up on what it can't
 * parse lets exactly that URL through to the next route handler. Whatever
 * this parser can't read is guarded too.
 */
object AppAuthPaths {
    private const val LOCALES = "en|pt-BR|es|de|fr|ja|it|nl|ko|sv|pl|no|zh-TW|hu|cs|ru"
    private const val PREFIX = "(?:/(?:$LOCALES))?"

    private val PROVIDER = Regex("^$PREFIX/app_auth/(google|strava)$")
    private val REDEEM = Regex("^$PREFIX/app_auth/redeem$")
    private val CALLBACK = Regex("^$PREFIX/app_auth/callback$")
    private val PROVIDER_STEP = Regex("^$PREFIX/auth(?:/.*)?$")
    private val SIGN_IN_PAGE = Regex("^$PREFIX/session(?:/new)?$")
    private val ABSOLUTE = Regex("^([A-Za-z][A-Za-z0-9+.-]*)://([^/?#]*)([^?#]*)")

    private class Parts(val host: String, val rawPath: String, val cleanPath: String)

    /** True for any URL on [host] whose path, however spelled, touches app_auth, and for anything unreadable. */
    fun isGuarded(url: String, host: String?): Boolean {
        val parts = parse(url) ?: return true
        return parts.host.equals(host, ignoreCase = true) &&
            parts.cleanPath.contains("app_auth", ignoreCase = true)
    }

    fun providerOf(url: String): String? =
        parse(url)?.let { PROVIDER.matchEntire(it.rawPath)?.groupValues?.get(1) }

    fun isRedeem(url: String): Boolean = parse(url)?.let { REDEEM.matches(it.rawPath) } ?: false

    fun isCallback(url: String): Boolean = parse(url)?.let { CALLBACK.matches(it.rawPath) } ?: false

    /** /auth/… with or without a locale, however spelled. */
    fun isProviderStep(url: String): Boolean = parse(url)?.let { PROVIDER_STEP.matches(it.cleanPath) } ?: false

    /**
     * After a redeem, what the first finished visit says: null while still
     * under app_auth, false when the site sent it to the sign-in page (the
     * redeem failed), true when it landed anywhere else (it worked).
     */
    fun redeemSucceeded(location: String): Boolean? {
        val path = parse(location)?.cleanPath ?: return false
        return when {
            path.contains("app_auth", ignoreCase = true) -> null
            SIGN_IN_PAGE.matches(path) -> false
            else -> true
        }
    }

    private fun parse(url: String): Parts? {
        val match = ABSOLUTE.find(url.trim()) ?: return null
        val authority = match.groupValues[2].substringAfterLast('@')
        val host = if (authority.startsWith("[")) authority.substringBefore(']') + "]" else authority.substringBefore(':')
        val rawPath = match.groupValues[3].ifEmpty { "/" }
        return Parts(host.lowercase(), rawPath, clean(rawPath))
    }

    // Decoded, repeated slashes collapsed, dot segments resolved: roughly
    // what the request will look like once it reaches Rails.
    private fun clean(rawPath: String): String {
        val segments = ArrayDeque<String>()
        decode(rawPath).split("/").forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> segments.removeLastOrNull()
                else -> segments.addLast(segment)
            }
        }
        return segments.joinToString("/", prefix = "/")
    }

    // Percent-decoding that never throws: a malformed escape stays as typed.
    private fun decode(text: String): String {
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val hex = if (c == '%' && i + 2 < text.length) hexByte(text[i + 1], text[i + 2]) else null
            if (hex != null) {
                out.write(hex)
                i += 3
            } else {
                out.write(c.toString().toByteArray(Charsets.UTF_8))
                i += 1
            }
        }
        return out.toString(Charsets.UTF_8.name())
    }

    // Two hex digits exactly; toInt(16) alone would also take "+1" or "-1".
    private fun hexByte(high: Char, low: Char): Int? {
        val h = Character.digit(high, 16)
        val l = Character.digit(low, 16)
        return if (h < 0 || l < 0) null else h * 16 + l
    }
}
