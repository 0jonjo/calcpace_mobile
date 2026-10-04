package app.calcpace.push

/**
 * What one run of the system's notification prompt (Android 13+) says about
 * asking again, read from shouldShowRequestPermissionRationale just before
 * and just after it.
 *
 * | granted | before | after | took             | outcome         |
 * |---------|--------|-------|------------------|-----------------|
 * | yes     | any    | any   | any              | [Outcome.GRANTED] |
 * | no      | any    | true  | any              | [Outcome.CAN_ASK_AGAIN]: a first "Don't allow", or a later prompt dismissed |
 * | no      | true   | false | any              | [Outcome.BLOCKED]: the second "Don't allow" |
 * | no      | false  | false | < [NO_PROMPT_MS] | [Outcome.BLOCKED]: no prompt was drawn at all |
 * | no      | false  | false | longer           | [Outcome.DISMISSED]: back or a tap outside the first prompt |
 *
 * The last two rows look the same to the API. A phone blocked before this
 * app ever asked (the TWA days, a restored device) gets an answer with no
 * prompt drawn, which comes back faster than anyone can see a prompt and
 * dismiss it. So one tap on the card does nothing, and after it the card
 * goes. A slow phone that misses the threshold only costs one more dead tap.
 *
 * Kept free of Android types so the rules are unit tested on the JVM.
 */
object PermissionPrompt {
    /** Faster than the prompt can animate in and be dismissed by a person. */
    const val NO_PROMPT_MS = 500L

    enum class Outcome { GRANTED, CAN_ASK_AGAIN, DISMISSED, BLOCKED }

    fun outcome(granted: Boolean, rationaleBefore: Boolean, rationaleAfter: Boolean, elapsedMs: Long): Outcome =
        when {
            granted -> Outcome.GRANTED
            rationaleAfter -> Outcome.CAN_ASK_AGAIN
            rationaleBefore -> Outcome.BLOCKED
            elapsedMs < NO_PROMPT_MS -> Outcome.BLOCKED
            else -> Outcome.DISMISSED
        }
}
