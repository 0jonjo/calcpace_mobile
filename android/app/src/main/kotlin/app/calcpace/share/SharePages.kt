package app.calcpace.share

import app.calcpace.auth.AppAuthPaths

/**
 * Where the "share-image" bridge component may be driven from: a run's
 * page (/activities/<id>, in any locale), where the share dialog lives. A
 * message sent while the WebView shows any other page is ignored rather than
 * allowed to write files and open the share sheet.
 *
 * Only the WebView's main-frame URL is checked: the JavaScript bridge is
 * reachable from any frame, so an iframe on a run page could still send one.
 * That is acceptable because a run page is only shown to its owner and
 * embeds no third-party frames; at worst the athlete is offered a share
 * sheet for an image of someone else's making.
 *
 * The same plain prefix match on the base URL plus a path pattern as
 * PushPages, no URL parser: anything unusual (an encoded character, a
 * doubled slash, another route under the run) fails closed.
 *
 * Kept free of Android types so the rules are unit tested on the JVM.
 */
object SharePages {
    private val RUN = Regex("^(?:/(?:${AppAuthPaths.LOCALES}))?/activities/[0-9]+/?$")

    fun isRunPage(location: String?, baseUrl: String): Boolean {
        val base = baseUrl.trimEnd('/')
        if (location == null || !location.startsWith(base)) return false
        val path = location.substring(base.length).substringBefore('#').substringBefore('?')
        return RUN.matches(path)
    }
}
