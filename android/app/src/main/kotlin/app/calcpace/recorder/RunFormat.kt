package app.calcpace.recorder

import java.util.Locale

/** The three numbers on the recording screen, as a runner reads them. */
object RunFormat {
    /** "0:42", "12:05", "1:02:09". */
    fun clock(millis: Long): String {
        val total = (millis / 1000).coerceAtLeast(0)
        val hours = total / 3600
        val minutes = total % 3600 / 60
        val seconds = total % 60
        return if (hours > 0) String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
        else String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
    }

    /** "3.42 km", two decimals as running apps show it. */
    fun km(meters: Double): String = String.format(Locale.ROOT, "%.2f km", meters / 1000)

    /** "5:07 /km", or "–:– /km" before there is a pace to show. */
    fun pace(secondsPerKm: Double?): String {
        if (secondsPerKm == null || !secondsPerKm.isFinite() || secondsPerKm >= 3600) return "–:– /km"
        val rounded = Math.round(secondsPerKm)
        return String.format(Locale.ROOT, "%d:%02d /km", rounded / 60, rounded % 60)
    }
}
