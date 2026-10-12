package app.calcpace.main

/**
 * Keeps a provider step from bouncing between the app and the browser.
 *
 * A provider step ([IncomingLink.BrowserTab]) is sent back to the browser
 * by name. A browser that hands calcpace.app links to their app anyway
 * (Samsung Internet's or Firefox's "open links in apps") would give it
 * straight back, and the app would send it out again, forever. The same
 * URL is forwarded once per [windowMs]: a second arrival that soon is that
 * bounce, never something the athlete did (each step carries a fresh
 * OAuth code and state).
 *
 * The manifest no longer claims provider steps, so a browser has no reason
 * to hand one to the app; this stays as a safety net for one that arrives
 * some other way.
 *
 * Kept free of Android types so the rules are unit tested on the JVM.
 */
class BrowserForwards(private val windowMs: Long = 10_000L) {
    private var lastUrl: String? = null
    private var lastAt = 0L

    /** True when [url] may go to the browser now, and notes that it went. */
    @Synchronized
    fun take(url: String, nowMs: Long): Boolean {
        if (url == lastUrl && nowMs - lastAt in 0 until windowMs) return false
        lastUrl = url
        lastAt = nowMs
        return true
    }
}
