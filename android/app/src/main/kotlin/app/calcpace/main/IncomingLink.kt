package app.calcpace.main

import java.net.URI
import java.net.URLDecoder

/**
 * What a link that Android hands to the app (an App Link tapped somewhere
 * else, or the end of a sign-in in the browser tab) is allowed to do.
 *
 * Every calcpace.app path opens in the app, but not every path may be
 * loaded into the WebView from outside: /app_auth/redeem signs the WebView
 * in, so a redeem link mailed by an attacker would sign the victim into the
 * attacker's account. Only [app.calcpace.auth.AppAuth] builds redeem URLs.
 *
 * Kept free of Android types so the rules are unit tested on the JVM.
 */
sealed interface IncomingLink {
    /** The site finished a sign-in started by this app. */
    data class SignIn(val ticket: String) : IncomingLink

    /** A provider step whose OAuth state lives in the browser tab's cookies. */
    data class BrowserTab(val url: String) : IncomingLink

    /** An ordinary page. */
    data class Web(val url: String) : IncomingLink

    data object Ignore : IncomingLink

    companion object {
        private const val LOCALE = "(?:/[A-Za-z]{2}(?:-[A-Za-z]{2})?)?"
        private val CALLBACK = Regex("^$LOCALE/app_auth/callback/?$")
        private val APP_AUTH = Regex("^$LOCALE/app_auth(?:/.*)?$")
        private val PROVIDER_AUTH = Regex("^$LOCALE/auth(?:/.*)?$")
        private val TICKET = Regex("^[A-Za-z0-9_-]{20,128}$")

        fun classify(url: String, baseUrl: String): IncomingLink {
            val uri = runCatching { URI(url) }.getOrNull() ?: return Ignore
            val base = URI(baseUrl)
            val sameSite = uri.scheme.equals(base.scheme, ignoreCase = true) &&
                uri.host.equals(base.host, ignoreCase = true) &&
                uri.port == base.port
            if (!sameSite) return Ignore

            val path = uri.rawPath.orEmpty()
            return when {
                CALLBACK.matches(path) -> ticketOf(uri)?.let { SignIn(it) } ?: Ignore
                APP_AUTH.matches(path) -> Ignore
                PROVIDER_AUTH.matches(path) -> BrowserTab(url)
                else -> Web(url)
            }
        }

        private fun ticketOf(uri: URI): String? =
            uri.rawQuery.orEmpty().split("&")
                .map { it.split("=", limit = 2) }
                .firstOrNull { it.size == 2 && it[0] == "ticket" }
                ?.let { URLDecoder.decode(it[1], "UTF-8") }
                ?.takeIf { TICKET.matches(it) }
    }
}
