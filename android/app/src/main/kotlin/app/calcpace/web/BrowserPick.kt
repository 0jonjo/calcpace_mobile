package app.calcpace.web

/**
 * Which app opens a calcpace.app page that is meant for the browser: the
 * sign-in's /app_auth/start, a provider step handed back to the tab, the
 * privacy policy from Health Connect.
 *
 * This app is the verified App Link handler for every calcpace.app URL, so
 * a Custom Tab launched without a package resolves to the app itself, which
 * refuses those pages, and nothing opens. So a browser is always named: the
 * default one when it can show a Custom Tab, else the first that can, else a
 * plain browser window, and never this app.
 *
 * Kept free of Android types so the rules are unit tested on the JVM.
 */
sealed interface BrowserPick {
    /** A Custom Tab in this browser. */
    data class CustomTab(val packageName: String) : BrowserPick

    /** No browser shows Custom Tabs: a plain window in this one. */
    data class Plain(val packageName: String) : BrowserPick

    /** No browser at all. */
    data object None : BrowserPick

    companion object {
        /**
         * @param defaultBrowser what the phone opens web links with, if it was set (may be anything)
         * @param browsers every app that opens an ordinary https link, in the system's order
         * @param customTabs those of [browsers] that show Custom Tabs
         * @param ownPackage this app, never a browser for its own links
         */
        fun choose(
            defaultBrowser: String?,
            browsers: List<String>,
            customTabs: Set<String>,
            ownPackage: String,
        ): BrowserPick {
            val candidates = (listOfNotNull(defaultBrowser).filter { it in browsers } + browsers)
                .distinct()
                .filterNot { it == ownPackage }

            candidates.firstOrNull { it in customTabs }?.let { return CustomTab(it) }
            return candidates.firstOrNull()?.let { Plain(it) } ?: None
        }
    }
}
