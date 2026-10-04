package app.calcpace.health

/**
 * The "status" the "health" bridge component reports, from
 * HealthConnectClient.getSdkStatus. The SDK's constants come in as
 * parameters so the table holds whatever their values are.
 *
 * - [AVAILABLE]: Health Connect is there and can be asked.
 * - [INSTALL_REQUIRED]: it needs installing or updating from Play first
 *   (Android 13 and older, where it is an app of its own).
 * - [UNAVAILABLE]: it can't run on this phone.
 *
 * Kept free of Android types so the table is unit tested on the JVM.
 */
object HcStatus {
    const val AVAILABLE = "available"
    const val INSTALL_REQUIRED = "install_required"
    const val UNAVAILABLE = "unavailable"

    fun of(sdkStatus: Int, available: Int, updateRequired: Int): String = when (sdkStatus) {
        available -> AVAILABLE
        updateRequired -> INSTALL_REQUIRED
        else -> UNAVAILABLE
    }
}
