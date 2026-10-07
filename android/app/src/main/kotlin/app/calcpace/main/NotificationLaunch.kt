package app.calcpace.main

import app.calcpace.push.PushPages

/**
 * Where the app begins when a "your run is in" notification starts it with
 * no MainActivity running (a cold start): the home page, with the run on
 * top of it, so Back from the run goes home and a second Back leaves the
 * app, as Android's own apps do. With the activity already running the tap
 * goes through onNewIntent and is routed on top of whatever is showing;
 * nothing here applies.
 *
 * Only the app's own notification asks for this (it marks its intent with
 * [EXTRA]); any other link starts where it points, as before. A marked
 * intent from elsewhere gets nothing more than that back stack: the link
 * is still held to [IncomingLink]'s rules, and only an ordinary page
 * ([IncomingLink.Web]) qualifies.
 *
 * Kept free of Android types so the rules are unit tested on the JVM.
 */
object NotificationLaunch {
    const val EXTRA = "app.calcpace.extra.RUN_NOTIFICATION"

    /** Start at [start]; once the navigator is ready, route to [then] on top of it. */
    data class Plan(val start: String, val then: String?)

    /**
     * The plan for a cold start from [url], or null to start as any other
     * link does.
     *
     * - [restored]: the activity is coming back from saved state (a
     *   configuration change, or the process having been killed); Android
     *   brings back its back stack, home and run included, so nothing more
     *   is routed.
     * - [fromHistory]: reopened from Recents after Back had closed the app.
     *   The intent is still the notification's, but its tap was used up:
     *   home only.
     */
    fun plan(url: String?, fromNotification: Boolean, fromHistory: Boolean, restored: Boolean, baseUrl: String): Plan? {
        if (!fromNotification || url == null) return null
        val link = IncomingLink.classify(url, baseUrl) as? IncomingLink.Web ?: return null
        val then = link.url.takeUnless { restored || fromHistory || PushPages.isHome(it, baseUrl) }
        return Plan(baseUrl, then)
    }
}
