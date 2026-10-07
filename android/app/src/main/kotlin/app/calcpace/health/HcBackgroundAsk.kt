package app.calcpace.health

/**
 * What a tap on the account page's "allow background access" (the
 * "background" event) does. Health Connect stops showing its screen once the
 * athlete has refused twice: the request then comes back at once, still not
 * allowed, and asking again would loop. So once a request comes back without
 * background reads, the next tap opens Health Connect's settings
 * instead, where the athlete can always switch them on. Never right after the
 * refusal itself: that may have been the athlete's real "no".
 *
 * In memory, for the process ([shared]): nothing on disk to go stale, and
 * after the process dies the worst case is one more request that comes back
 * at once, after which the next tap opens the settings again.
 *
 * Main thread only. Kept free of Android types so it is unit tested on the JVM.
 */
class HcBackgroundAsk {
    enum class Step { NOTHING, REQUEST, SETTINGS }

    private var refused = false

    /** For [background] (HcBackground's tri-state) as Health Connect holds it now. */
    fun next(background: Boolean?): Step {
        if (background != false) {
            // Allowed, or nothing to allow: a later "no" starts over with the screen.
            refused = false
            return Step.NOTHING
        }
        return if (refused) Step.SETTINGS else Step.REQUEST
    }

    /** What Health Connect holds after the request [next] asked for. */
    fun requested(background: Boolean?) {
        refused = background == false
    }

    companion object {
        val shared = HcBackgroundAsk()
    }
}
