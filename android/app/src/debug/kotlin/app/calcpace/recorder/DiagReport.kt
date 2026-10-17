package app.calcpace.recorder

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The summary.txt that goes in a test run's bundle: the phone, the run's
 * numbers, every stretch the GPS went silent while running (with what the
 * screen, the satellites, the fused provider and the step counter did
 * meanwhile) and a timeline of what the runner and the phone did. Built from
 * the [DiagLog] file, so a run the system killed halfway still gets one.
 * Free of Android types, for the JVM tests.
 */
object DiagReport {
    /** A GPS silence worth a line: longer than this between two fixes, running. */
    const val SILENCE_MS = 10_000L

    data class Line(val time: Long, val kind: String, val fields: List<String>)

    data class Silence(
        val from: Long,
        val to: Long,
        val screenOff: Boolean,
        val fusedFixes: Int,
        val steps: Int?,
        val fewestSatellitesUsed: Int?,
    )

    fun parse(text: String): List<Line> = text.lineSequence().mapNotNull { raw ->
        val parts = raw.trim().split(',')
        val time = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
        val kind = parts.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        Line(time, kind, parts.drop(2))
    }.toList()

    /**
     * Gaps between GPS fixes longer than [SILENCE_MS] while the run was
     * running, by when each fix was taken (unmoved by fixes delivered late in
     * a batch): a pause turns the GPS off
     * on purpose, so a gap that holds a pause is not one. The run's start and
     * stop are the edges; [stoppedAt] closes a run whose stop the readings
     * missed (stopped from a fresh process after a kill).
     */
    fun silences(lines: List<Line>, stoppedAt: Long? = null): List<Silence> {
        val result = mutableListOf<Silence>()
        val steps = steps(lines)
        var running = false
        var lastFix: Long? = null
        var screenOff = false
        fun close(at: Long) {
            lastFix?.takeIf { running && at - it > SILENCE_MS }?.let { result += silence(lines, steps, it, at, screenOff) }
        }
        for (line in lines) {
            when (line.kind) {
                "RUN" -> when (line.fields.firstOrNull()) {
                    "start", "resume" -> { running = true; lastFix = line.time }
                    "pause", "stop" -> {
                        close(line.time)
                        running = false
                        lastFix = null
                    }
                }
                "SCREEN" -> screenOff = line.fields.firstOrNull() == "off"
                "GPS" -> if (running) {
                    val fixTime = takenAt(line)
                    close(fixTime)
                    lastFix = maxOf(lastFix ?: fixTime, fixTime)
                }
            }
        }
        stoppedAt?.let(::close)
        return result
    }

    /**
     * When a GPS fix was taken, on the phone's clock like every other line:
     * the line's time less the fix's age, which the phone measures on its
     * monotonic clock. The GPS's own time would be off by however wrong the
     * phone's clock is, against the start, pause and stop around it.
     */
    private fun takenAt(line: Line): Long = line.time - (line.fields.getOrNull(9)?.toLongOrNull()?.coerceAtLeast(0) ?: 0)

    /**
     * The step count since the first reading, at each STEP line. The phone's
     * counter runs from its boot, so differences are what count; a counter
     * that went down is a reboot, starting again from 0.
     */
    fun steps(lines: List<Line>): List<Pair<Long, Long>> {
        var total = 0L
        var previous: Long? = null
        return lines.filter { it.kind == "STEP" }.mapNotNull { line ->
            val raw = line.fields.firstOrNull()?.toLongOrNull() ?: return@mapNotNull null
            previous?.let { total += if (raw >= it) raw - it else raw }
            previous = raw
            line.time to total
        }
    }

    private fun stepsAt(steps: List<Pair<Long, Long>>, time: Long) = steps.lastOrNull { it.first <= time }?.second ?: 0L

    private fun silence(lines: List<Line>, steps: List<Pair<Long, Long>>, from: Long, to: Long, screenOffAtStart: Boolean): Silence {
        val inside = lines.filter { it.time in from..to }
        return Silence(
            from = from,
            to = to,
            screenOff = screenOffAtStart || inside.any { it.kind == "SCREEN" && it.fields.firstOrNull() == "off" },
            fusedFixes = inside.count { it.kind == "FUSED" },
            // No counter, no number; a counter that said nothing meanwhile, 0.
            steps = if (steps.isEmpty()) null else (stepsAt(steps, to) - stepsAt(steps, from)).toInt(),
            fewestSatellitesUsed = inside.filter { it.kind == "GNSS" }.mapNotNull { it.fields.getOrNull(1)?.toIntOrNull() }.minOrNull(),
        )
    }

    fun build(facts: List<Pair<String, String>>, track: Track, diagText: String, zone: ZoneId): String {
        val lines = parse(diagText)
        val clock = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT).withZone(zone)
        val start = track.startedAt ?: lines.firstOrNull()?.time ?: 0L
        fun at(time: Long) = "${clock.format(Instant.ofEpochMilli(time))}  +${RunFormat.clock(time - start)}"

        return buildString {
            appendLine("Calcpace test run")
            appendLine()
            appendLine("== Phone ==")
            facts.forEach { (key, value) -> appendLine("$key: $value") }
            appendLine()

            appendLine("== Run ==")
            val stop = track.stoppedAt ?: lines.lastOrNull()?.time ?: start
            appendLine("Start ${clock.format(Instant.ofEpochMilli(start))} · stop ${clock.format(Instant.ofEpochMilli(stop))}")
            appendLine("Elapsed ${RunFormat.clock(stop - start)} · moving ${RunFormat.clock(track.movingMillis(stop))}")
            appendLine("Distance ${RunFormat.km(track.distanceMeters)} · pace ${RunFormat.pace(track.averagePace(stop))}")
            appendLine("GPS fixes ${track.fixes} (dropped ${track.dropped}) · longest wait ${track.longestGapMillis / 1000} s")
            appendLine("Route stretches ${track.segments.size}")
            steps(lines).lastOrNull()?.let { appendLine("Steps ${it.second}") }
            appendLine()

            appendLine("== Readings ==")
            appendLine(lines.groupingBy { it.kind }.eachCount().entries.sortedBy { it.key }.joinToString(" · ") { "${it.key} ${it.value}" })
            appendLine()

            appendLine("== GPS silences over ${SILENCE_MS / 1000} s while running ==")
            val silences = silences(lines, track.stoppedAt)
            if (silences.isEmpty()) appendLine("none")
            silences.forEach { s ->
                append("${at(s.from)} → ${clock.format(Instant.ofEpochMilli(s.to))}  ${(s.to - s.from) / 1000} s")
                append(" · screen ${if (s.screenOff) "off" else "on"}")
                append(" · fused fixes ${s.fusedFixes}")
                append(" · steps ${s.steps ?: "?"}")
                append(" · fewest satellites used ${s.fewestSatellitesUsed ?: "?"}")
                appendLine()
            }
            appendLine()

            appendLine("== Timeline ==")
            var lastActivity: String? = null
            val events = lines.mapNotNull { line ->
                val what = when (line.kind) {
                    "RUN", "SCREEN", "APP", "POWER" -> "${line.kind.lowercase(Locale.ROOT)} ${line.fields.firstOrNull().orEmpty()}"
                    "NOTE" -> line.fields.joinToString(",")
                    // Its answer every few seconds: only when it changes its mind.
                    "ACT" -> line.fields.firstOrNull()?.takeIf { it != lastActivity }?.also { lastActivity = it }
                        ?.let { "activity $it (${line.fields.getOrNull(1)}%)" }
                    else -> null
                }
                what?.let { line.time to it }
            } + silences.map { it.from to "gps silent for ${(it.to - it.from) / 1000} s" }
            events.sortedBy { it.first }.forEach { (time, what) -> appendLine("${at(time)}  $what") }
        }
    }
}
