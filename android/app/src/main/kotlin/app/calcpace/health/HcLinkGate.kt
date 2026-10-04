package app.calcpace.health

/**
 * Whether the "health" bridge component may take a "link" now. The
 * WebView's JavaScript bridge is reachable from any frame of the page, so
 * HcPages (which checks the main frame's URL) alone can't stop an embedded
 * frame from handing the app a token of some other account and collecting
 * this phone's runs there. A link is only taken shortly after the athlete
 * said yes on this very page: Health Connect's permission screen granting
 * exercise and distance, or, when those were granted before and no screen
 * shows, the app's own confirmation. Once, and for [WINDOW_MS].
 *
 * One per component instance, main thread. Kept free of Android types so it
 * is unit tested on the JVM.
 */
class HcLinkGate(private val clock: () -> Long = System::currentTimeMillis) {
    private var openedAt: Long? = null

    /** The athlete just allowed the import on this page. */
    fun open() {
        openedAt = clock()
    }

    /** True once within [WINDOW_MS] of [open]; closes the gate either way. */
    fun consume(): Boolean {
        val at = openedAt ?: return false
        openedAt = null
        val elapsed = clock() - at
        return elapsed in 0..WINDOW_MS
    }

    companion object {
        const val WINDOW_MS = 5 * 60 * 1000L
    }
}
