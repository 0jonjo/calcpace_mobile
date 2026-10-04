package app.calcpace.health

/**
 * The rules of one sync, free of Android types so they are unit tested on
 * the JVM (HcSyncWorker does the reading and the sending).
 *
 * - With no changes token, the sync is the [Step.Initial] read of the last
 *   30 days; with one, it asks Health Connect what changed since.
 * - The changes token only moves on once the server has answered every
 *   request of the sync ([After.ADVANCE]); otherwise the same changes are
 *   read and sent again next time, which the server takes idempotently.
 */
object HcSyncPlan {
    sealed interface Step {
        data object Initial : Step
        data class Changes(val token: String) : Step
    }

    enum class After { ADVANCE, RETRY, UNLINK }

    /** No answer at all (the network). */
    const val NO_ANSWER = -1

    fun next(changesToken: String?): Step = changesToken?.let { Step.Changes(it) } ?: Step.Initial

    /** What an HTTP answer means for the changes token. */
    fun after(status: Int): After = when (status) {
        200 -> After.ADVANCE
        // The link is gone (signed out, disconnected on the account page).
        401 -> After.UNLINK
        // Our own payload refused, or an account without a profile: sending
        // it again would loop for ever. The server is the judge; move on.
        400, 409, 413, 422 -> After.ADVANCE
        // 429, 5xx, no answer: later.
        else -> After.RETRY
    }

    /**
     * Whether DELETE /health_connect/token settled it: 204, or a refusal
     * that asking again won't change (401, a token the server no longer
     * knows). On 429, 5xx or no answer the app keeps its token and asks again.
     */
    fun forgotten(status: Int): Boolean = status == 204 || (status in 400..499 && status != 429)

    /** The answers of one sync's requests, sent in order until one isn't [After.ADVANCE]. */
    fun overall(afters: List<After>): After = afters.firstOrNull { it != After.ADVANCE } ?: After.ADVANCE
}
