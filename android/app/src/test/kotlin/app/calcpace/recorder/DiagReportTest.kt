package app.calcpace.recorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class DiagReportTest {
    private val t0 = 1_760_000_000_000L

    private fun at(seconds: Int, kind: String, vararg fields: Any) =
        (listOf(t0 + seconds * 1000L, kind) + fields).joinToString(",")

    private fun gps(seconds: Int) = at(seconds, "GPS", t0 + seconds * 1000L, "-5.8573418", "-35.2061699", "38.00", "4.00", "", "", "", "", "0")

    @Test
    fun aSilenceWhileRunningIsFoundWithWhatThePhoneDidMeanwhile() {
        val text = listOf(
            at(0, "SCREEN", "on"),
            at(0, "RUN", "start"),
            gps(1),
            at(2, "STEP", 6),
            gps(2), gps(3),
            at(10, "SCREEN", "off"),
            at(20, "GNSS", 30, 0, "", ""),
            at(30, "FUSED", t0, "-5.8", "-35.2", "", "60.00", "", "", "", "", "0"),
            at(300, "STEP", 820),
            gps(325), gps(326),
            at(330, "RUN", "stop"),
        ).joinToString("\n")

        val silences = DiagReport.silences(DiagReport.parse(text))

        assertEquals(1, silences.size)
        val silence = silences.single()
        assertEquals(t0 + 3_000, silence.from)
        assertEquals(t0 + 325_000, silence.to)
        assertTrue(silence.screenOff)
        assertEquals(1, silence.fusedFixes)
        assertEquals(814, silence.steps)
        assertEquals(0, silence.fewestSatellitesUsed)
    }

    // The GPS is off on purpose while paused: a gap that holds a pause is none.
    @Test
    fun aPauseIsNotASilence() {
        val text = listOf(
            at(0, "RUN", "start"), gps(1), gps(2),
            at(3, "RUN", "pause"),
            at(90, "RUN", "resume"), gps(91), gps(92),
            at(93, "RUN", "stop"),
        ).joinToString("\n")

        assertTrue(DiagReport.silences(DiagReport.parse(text)).isEmpty())
    }

    // No fix for the first minute: the start is the silence's edge.
    @Test
    fun aSilenceFromTheStartAndOneUpToAPauseCount() {
        val text = listOf(
            at(0, "RUN", "start"),
            gps(60), gps(61),
            at(120, "RUN", "pause"),
        ).joinToString("\n")

        val silences = DiagReport.silences(DiagReport.parse(text))

        assertEquals(listOf(t0 to t0 + 60_000, t0 + 61_000 to t0 + 120_000), silences.map { it.from to it.to })
        assertEquals(null, silences.first().steps)
    }

    @Test
    fun aTornOrForeignLineIsSkipped() {
        val lines = DiagReport.parse("garbage\n${t0},RUN,start\n17600000\n${t0 + 1},GPS,")

        assertEquals(listOf("RUN", "GPS"), lines.map { it.kind })
    }

    @Test
    fun theSummaryHasThePhoneTheRunTheSilencesAndATimeline() {
        val text = listOf(
            at(0, "RUN", "start"), gps(1),
            at(5, "SCREEN", "off"),
            at(6, "ACT", "running", 80), at(11, "ACT", "running", 90), at(16, "ACT", "still", 70),
            gps(40),
            at(41, "RUN", "stop"),
        ).joinToString("\n")
        val track = Track.of(listOf(RunEvent.Start(t0), RunEvent.Stop(t0 + 41_000)))

        val summary = DiagReport.build(listOf("Phone" to "samsung SM-M556B"), track, text, ZoneOffset.UTC)

        assertTrue(summary, summary.contains("Phone: samsung SM-M556B"))
        assertTrue(summary, summary.contains("Elapsed 0:41"))
        assertTrue(summary, summary.contains("+0:01 → "))
        assertTrue(summary, summary.contains("39 s · screen off"))
        // Activity recognition only when it changes its mind.
        assertEquals(1, Regex("activity running").findAll(summary).count())
        assertTrue(summary, summary.contains("activity still (70%)"))
        val timeline = summary.substringAfter("== Timeline ==")
        // In time order: the silence began (+0:01) before the screen went off (+0:05).
        assertTrue(timeline, timeline.indexOf("gps silent") in 0 until timeline.indexOf("screen off"))
    }
}
