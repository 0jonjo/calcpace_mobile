package app.calcpace.push

import app.calcpace.auth.AppAuthPaths

/**
 * Where the push bridge component may be driven from: the site's home page,
 * where its card lives, and nowhere else. The WebView's JavaScript bridge is
 * reachable from any frame on any page, so a message from elsewhere (an
 * embedded frame, some other page) is ignored rather than allowed to prompt
 * or collect the token.
 *
 * A plain prefix match on the base URL plus a path pattern, no URL parser:
 * anything unusual fails closed.
 *
 * Kept free of Android types so the rules are unit tested on the JVM.
 */
object PushPages {
    private val HOME = Regex("^(?:/(?:${AppAuthPaths.LOCALES}))?/?$")

    fun isHome(location: String?, baseUrl: String): Boolean {
        val base = baseUrl.trimEnd('/')
        if (location == null || !location.startsWith(base)) return false
        val path = location.substring(base.length).substringBefore('#').substringBefore('?')
        return HOME.matches(path)
    }
}
