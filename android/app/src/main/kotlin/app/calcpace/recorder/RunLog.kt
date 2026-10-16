package app.calcpace.recorder

import java.util.Locale

/**
 * What the recorder writes down while a run is on, one line per event,
 * appended and flushed as it happens: if the process dies mid-run, the file
 * holds everything up to the last fix and the run can be finished from it.
 *
 * - `S,<ms>` start, `P,<ms>` pause, `R,<ms>` resume, `E,<ms>` stop;
 * - `F,<ms>,<lat>,<lon>,<altitude or empty>,<accuracy m>` a GPS fix.
 *
 * Times are epoch milliseconds. A line that does not parse (the last one,
 * cut off by a kill) is skipped, never fatal. Free of Android types so the
 * format is unit tested on the JVM.
 */
sealed interface RunEvent {
    val time: Long

    data class Start(override val time: Long) : RunEvent
    data class Pause(override val time: Long) : RunEvent
    data class Resume(override val time: Long) : RunEvent
    data class Stop(override val time: Long) : RunEvent
    data class Fix(
        override val time: Long,
        val lat: Double,
        val lon: Double,
        val altitude: Double?,
        val accuracy: Float,
    ) : RunEvent
}

object RunLog {
    fun line(event: RunEvent): String = when (event) {
        is RunEvent.Start -> "S,${event.time}"
        is RunEvent.Pause -> "P,${event.time}"
        is RunEvent.Resume -> "R,${event.time}"
        is RunEvent.Stop -> "E,${event.time}"
        is RunEvent.Fix -> String.format(
            Locale.ROOT, "F,%d,%.7f,%.7f,%s,%.1f",
            event.time, event.lat, event.lon,
            event.altitude?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "",
            event.accuracy,
        )
    }

    fun parse(text: String): List<RunEvent> = text.lineSequence().mapNotNull(::parseLine).toList()

    fun parseLine(line: String): RunEvent? {
        val parts = line.trim().split(',')
        val time = parts.getOrNull(1)?.toLongOrNull() ?: return null
        return when (parts[0]) {
            "S" -> if (parts.size == 2) RunEvent.Start(time) else null
            "P" -> if (parts.size == 2) RunEvent.Pause(time) else null
            "R" -> if (parts.size == 2) RunEvent.Resume(time) else null
            "E" -> if (parts.size == 2) RunEvent.Stop(time) else null
            "F" -> fix(time, parts)
            else -> null
        }
    }

    private fun fix(time: Long, parts: List<String>): RunEvent.Fix? {
        if (parts.size != 6) return null
        val lat = parts[2].toDoubleOrNull()?.takeIf { it in -90.0..90.0 } ?: return null
        val lon = parts[3].toDoubleOrNull()?.takeIf { it in -180.0..180.0 } ?: return null
        val altitude = if (parts[4].isEmpty()) null else parts[4].toDoubleOrNull() ?: return null
        val accuracy = parts[5].toFloatOrNull() ?: return null
        return RunEvent.Fix(time, lat, lon, altitude, accuracy)
    }
}
