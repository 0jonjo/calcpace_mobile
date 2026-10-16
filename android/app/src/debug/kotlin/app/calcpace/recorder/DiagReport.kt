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
     * running: a pause turns the GPS off on purpose, so a gap that holds a
     * pause is not one. The run's start and stop count as the edges.
     */
    fun silences(lines: List<Line>): List<Silence> {
        val result = mutableListOf<Silence>()
        var running = false
        var lastFix: Long? = null
        var screenOff = false
        for (line in lines) {
            when (line.kind) {
                "RUN" -> when (line.fields.firstOrNull()) {
                    "start", "resume" -> { running = true; lastFix = line.time }
                    "pause", "stop" -> {
                        lastFix?.takeIf { running && line.time - it > SILENCE_MS }?.let { result += silence(lines, it, line.time, screenOff) }
                        running = false
                        lastFix = null
                    }
                }
                "SCREEN" -> screenOff = line.fields.firstOrNull() == "off"
                "GPS" -> if (running) {
                    lastFix?.takeIf { line.time - it > SILENCE_MS }?.let { result += silence(lines, it, line.time, screenOff) }
                    lastFix = line.time
                }
            }
        }
        return result
    }

    private fun silence(lines: List<Line>, from: Long, to: Long, screenOffAtStart: Boolean): Silence {
        val inside = lines.filter { it.time in from..to }
        fun stepsOf(line: Line?) = line?.fields?.firstOrNull()?.toIntOrNull()
        // Counted from the run's start: no reading before the silence is 0.
        val before = stepsOf(lines.lastOrNull { it.kind == "STEP" && it.time <= from }) ?: 0
        val steps = stepsOf(inside.lastOrNull { it.kind == "STEP" })?.minus(before)
        return Silence(
            from = from,
            to = to,
            screenOff = screenOffAtStart || inside.any { it.kind == "SCREEN" && it.fields.firstOrNull() == "off" },
            fusedFixes = inside.count { it.kind == "FUSED" },
            steps = steps,
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
            lines.lastOrNull { it.kind == "STEP" }?.fields?.firstOrNull()?.let { appendLine("Steps $it") }
            appendLine()

            appendLine("== Readings ==")
            appendLine(lines.groupingBy { it.kind }.eachCount().entries.sortedBy { it.key }.joinToString(" · ") { "${it.key} ${it.value}" })
            appendLine()

            appendLine("== GPS silences over ${SILENCE_MS / 1000} s while running ==")
            val silences = silences(lines)
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
