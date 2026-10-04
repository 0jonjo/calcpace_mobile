package app.calcpace.auth

import java.net.URI

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
 */
object AppAuthPaths {
    private const val LOCALES = "en|pt-BR|es|de|fr|ja|it|nl|ko|sv|pl|no|zh-TW|hu|cs|ru"
    private const val PREFIX = "(?:/(?:$LOCALES))?"

    private val PROVIDER = Regex("^$PREFIX/app_auth/(google|strava)$")
    private val REDEEM = Regex("^$PREFIX/app_auth/redeem$")
    private val CALLBACK = Regex("^$PREFIX/app_auth/callback$")
    private val PROVIDER_STEP = Regex("^$PREFIX/auth(?:/.*)?$")

    /** True for any URL on [host] whose path, however spelled, touches app_auth. */
    fun isGuarded(url: String, host: String?): Boolean {
        val uri = parse(url) ?: return false
        return uri.host.equals(host, ignoreCase = true) &&
            cleanPath(uri).contains("app_auth", ignoreCase = true)
    }

    fun providerOf(url: String): String? =
        parse(url)?.let { PROVIDER.matchEntire(it.rawPath.orEmpty())?.groupValues?.get(1) }

    fun isRedeem(url: String): Boolean = parse(url)?.rawPath?.let { REDEEM.matches(it) } ?: false

    fun isCallback(url: String): Boolean = parse(url)?.rawPath?.let { CALLBACK.matches(it) } ?: false

    /** /auth/… with or without a locale, however spelled. */
    fun isProviderStep(url: String): Boolean = parse(url)?.let { PROVIDER_STEP.matches(cleanPath(it)) } ?: false

    private fun parse(url: String): URI? = runCatching { URI(url) }.getOrNull()

    // Decoded, repeated slashes collapsed, dot segments resolved: roughly
    // what the request will look like once it reaches Rails. Done by hand on
    // purpose: rebuilding a java.net.URI from a path that starts with "//"
    // throws on the JVM but, on Android, reads the first segment as a host
    // and silently drops it.
    private fun cleanPath(uri: URI): String {
        val segments = ArrayDeque<String>()
        uri.path.orEmpty().split("/").forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> segments.removeLastOrNull()
                else -> segments.addLast(segment)
            }
        }
        return segments.joinToString("/", prefix = "/")
    }
}
