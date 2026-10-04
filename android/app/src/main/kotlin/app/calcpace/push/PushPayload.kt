package app.calcpace.push

import app.calcpace.main.IncomingLink

/** What a "your run is in" notification shows, and the page a tap opens. */
data class Notice(val title: String, val body: String, val url: String)

/**
 * Reads the data-only message the server sends (title, body, path) into a
 * [Notice], or null when anything is off.
 *
 * A tap opens [Notice.url] in the app, so the path is held to the rules a
 * link from outside must follow ([IncomingLink]) and more: it has to be a
 * plain path on the site, nothing with a scheme or a host of its own. The
 * cheap string guards run first, before any URL parser sees it: Android's
 * java.net.URI and the JVM's don't agree on odd input, and the WebView reads
 * "\" as "/" whatever either of them thinks.
 *
 * Kept free of Android types so the rules are unit tested on the JVM.
 */
object PushPayload {
    private const val MAX_TEXT = 200
    private const val MAX_PATH = 2048

    fun parse(data: Map<String, String>, baseUrl: String): Notice? {
        val title = data["title"]?.trim()?.take(MAX_TEXT).orEmpty()
        val body = data["body"]?.trim()?.take(MAX_TEXT).orEmpty()
        val path = data["path"] ?: return null
        if (title.isEmpty() || body.isEmpty() || !isPlainPath(path)) return null

        val url = baseUrl.trimEnd('/') + path
        return when (IncomingLink.classify(url, baseUrl)) {
            is IncomingLink.Web -> Notice(title, body, url)
            else -> null
        }
    }

    // "/x", never "//host", "/\host", a scheme, or anything the WebView would
    // silently drop or rewrite (whitespace, control characters).
    private fun isPlainPath(path: String): Boolean =
        path.length <= MAX_PATH &&
            path.startsWith("/") &&
            !path.startsWith("//") &&
            path.none { it == '\\' || it <= ' ' || it == '\u007f' }
}
