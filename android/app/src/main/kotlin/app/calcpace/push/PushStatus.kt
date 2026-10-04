package app.calcpace.push

/**
 * The answer the site's push bridge component gets: whether this phone will
 * show "your run is in" notifications, and whether it is worth asking.
 *
 * - [UNAVAILABLE]: a build without usable Firebase options (CI, forks, a
 *   ".debug" package that google-services.json doesn't know).
 * - [GRANTED]: the athlete tapped "turn on" and the system lets the app
 *   notify. Only then is there an FCM token to hand over.
 * - [DEFAULT]: not asked yet, denied once, or the prompt was dismissed: the
 *   system would still show its prompt, so the site may show its card.
 * - [DENIED]: blocked for good ([PermissionPrompt.Outcome.BLOCKED]), or
 *   allowed but switched off in the phone's settings; only the settings can
 *   change that.
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

    /** The only package google-services.json has a Firebase app for. */
    const val FIREBASE_PACKAGE = "app.calcpace.twa"

    /**
     * @param optedIn the athlete tapped "turn on" in the app. Before that no
     *   token exists, even where the system needs no permission (Android 12
     *   and older) or granted it long ago.
     * @param granted POST_NOTIFICATIONS is granted (ignored before Android 13).
     * @param notificationsEnabled the app's notifications (and its runs
     *   channel) are switched on in the phone's settings.
     * @param blocked the last prompt came back blocked for good
     *   ([PermissionPrompt]); cleared whenever the system grants again.
     */
    fun of(
        configured: Boolean,
        sdkInt: Int,
        optedIn: Boolean,
        granted: Boolean,
        notificationsEnabled: Boolean,
        blocked: Boolean,
    ): String {
        if (!configured) return UNAVAILABLE
        val allowedBySystem = sdkInt < RUNTIME_PERMISSION_SDK || granted
        return when {
            allowedBySystem && !notificationsEnabled -> DENIED
            allowedBySystem -> if (optedIn) GRANTED else DEFAULT
            blocked -> DENIED
            else -> DEFAULT
        }
    }

    /** Every Firebase option is there, and this build is the package they belong to. */
    fun isConfigured(applicationId: String, vararg options: String): Boolean =
        applicationId == FIREBASE_PACKAGE && options.all { it.isNotEmpty() }

    /**
     * An opted-in phone that may no longer notify drops its FCM token, so
     * the server's next send comes back UNREGISTERED and it prunes the
     * device. A new token comes with the next [GRANTED].
     */
    fun shouldDropToken(optedIn: Boolean, status: String): Boolean =
        optedIn && (status == DENIED || status == DEFAULT)
}
