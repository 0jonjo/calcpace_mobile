package app.calcpace.health

import app.calcpace.auth.AppAuthPaths

/**
 * Where the "health" bridge component may be driven from: the site's home
 * page (the Health Connect card) and the account page (its Health Connect
 * section), in any locale, and nowhere else. The WebView's JavaScript bridge
 * is reachable from any frame on any page, so a message from elsewhere is
 * ignored rather than allowed to open Health Connect's permission screen or
 * hand the app a link token.
 *
 * The same plain prefix match on the base URL plus a path pattern as
 * PushPages, no URL parser: anything unusual fails closed.
 *
 * Kept free of Android types so the rules are unit tested on the JVM.
 */
object HcPages {
    private val ALLOWED = Regex("^(?:/(?:${AppAuthPaths.LOCALES}))?(?:/account)?/?$")

    fun isAllowed(location: String?, baseUrl: String): Boolean {
        val base = baseUrl.trimEnd('/')
        if (location == null || !location.startsWith(base)) return false
        val path = location.substring(base.length).substringBefore('#').substringBefore('?')
        return ALLOWED.matches(path)
    }
}
