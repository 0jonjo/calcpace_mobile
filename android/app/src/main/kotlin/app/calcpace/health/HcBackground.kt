package app.calcpace.health

/**
 * The "background" the "health" bridge component reports:
 *
 * - `true`: background reads exist on this phone and are allowed.
 * - `false`: they exist but aren't allowed; the "background" event can ask.
 * - `null`: nothing to ask: the phone has no background reads (Health
 *   Connect too old for them), or Health Connect itself isn't usable.
 *
 * Kept free of Android types so it is unit tested on the JVM.
 */
object HcBackground {
    fun of(featureAvailable: Boolean, granted: Boolean): Boolean? = if (featureAvailable) granted else null
}
