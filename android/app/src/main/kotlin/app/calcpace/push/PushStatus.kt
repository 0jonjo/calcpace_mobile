package app.calcpace.push

/**
 * The answer the site's push bridge component gets: whether this phone will
 * show "your run is in" notifications, and whether it is worth asking.
 *
 * - [UNAVAILABLE]: a build without Firebase options (CI, forks).
 * - [GRANTED]: the athlete tapped "turn on" and the system lets the app
 *   notify. Only then is there an FCM token to hand over.
 * - [DEFAULT]: not asked yet, or the system would still show its prompt, so
 *   the site may show its card.
 * - [DENIED]: the system won't let the app notify and won't ask again;
 *   only the phone's settings can change that.
 *
 * Kept free of Android types so the table is unit tested on the JVM.
 */
object PushStatus {
    const val GRANTED = "granted"
    const val DEFAULT = "default"
    const val DENIED = "denied"
    const val UNAVAILABLE = "unavailable"

    /** Android 13, when notifications became a runtime permission. */
    const val RUNTIME_PERMISSION_SDK = 33

    /**
     * @param optedIn the athlete tapped "turn on" in the app. Before that no
     *   token exists, even where the system needs no permission (Android 12
     *   and older) or granted it long ago.
     * @param granted POST_NOTIFICATIONS is granted (ignored before Android 13).
     * @param notificationsEnabled the app's notifications (and its runs
     *   channel) are switched on in the phone's settings.
     * @param askedBefore this app has shown the system prompt before.
     * @param canAskAgain shouldShowRequestPermissionRationale: the system
     *   would show its prompt again.
     */
    fun of(
        configured: Boolean,
        sdkInt: Int,
        optedIn: Boolean,
        granted: Boolean,
        notificationsEnabled: Boolean,
        askedBefore: Boolean,
        canAskAgain: Boolean,
    ): String {
        if (!configured) return UNAVAILABLE
        val allowedBySystem = sdkInt < RUNTIME_PERMISSION_SDK || granted
        return when {
            allowedBySystem && !notificationsEnabled -> DENIED
            allowedBySystem -> if (optedIn) GRANTED else DEFAULT
            !askedBefore || canAskAgain -> DEFAULT
            else -> DENIED
        }
    }
}
