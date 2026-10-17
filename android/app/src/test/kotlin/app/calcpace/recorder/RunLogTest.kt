package app.calcpace.recorder

import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunLogTest {
    private val T = 1_760_000_000_000L

    @Test
    fun everyEventSurvivesTheRoundTrip() {
        val events = listOf(
            RunEvent.Start(T + 1000),
            RunEvent.Fix(T + 2000, -5.8400001, -35.2000002, 31.5, 4.5f),
            RunEvent.Fix(T + 3000, 51.5, -0.1, null, 12f),
            RunEvent.Pause(T + 4000),
            RunEvent.Resume(T + 5000),
            RunEvent.Stop(T + 6000),
        )

        assertEquals(events, RunLog.parse(events.joinToString("\n", transform = RunLog::line)))
    }

    // The process was killed in the middle of a write.
    @Test
    fun aCutOffLastLineIsSkipped() {
        val text = "S,${T + 1000}\nF,${T + 2000},-5.84,-35.2,,5.0\nF,${T + 3000},-5.8"
        assertEquals(listOf(RunEvent.Start(T + 1000), RunEvent.Fix(T + 2000, -5.84, -35.2, null, 5f)), RunLog.parse(text))
    }

    @Test
    fun linesThatDoNotHoldUpAreSkipped() {
        listOf(
            "", "X,1", "S", "S,abc", "S,1,2", "S,17", "R,1760000", "F,1700,0,0,,5", "F,1,91,0,,5", "F,1,0,181,,5", "F,1,0,0,x,5", "F,1,0,0,,", "F,1,0,0,,5,9",
        ).forEach { assertNull(it, RunLog.parseLine(it)) }
    }

    // A phone in German writes 10,5 for 10.5 unless told otherwise, and the
    // comma is the field separator.
    @Test
    fun fixesAreWrittenWithDotsWhateverTheLocale() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)
        try {
            assertEquals("F,$T,-5.8400000,-35.2000000,10.5,3.5", RunLog.line(RunEvent.Fix(T, -5.84, -35.2, 10.5, 3.5f)))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun theGpxHoldsOneSegmentPerStretchWithTimesInUtc() {
        val t0 = 1_760_000_000_000L // 2025-10-09T08:53:20Z
        val track = Track.of(
            listOf(
                RunEvent.Start(t0),
                RunEvent.Fix(t0, 1.0, 2.0, 10.0, 5f),
                RunEvent.Fix(t0 + 1000, 1.00003, 2.0, null, 5f),
                RunEvent.Pause(t0 + 2000),
                RunEvent.Resume(t0 + 3000),
                RunEvent.Fix(t0 + 4000, 1.0001, 2.0, null, 5f),
                RunEvent.Stop(t0 + 5000),
            )
        )
        val gpx = Gpx.write(track, "Run <1> & co")

        assertEquals(2, Regex("<trkseg>").findAll(gpx).count())
        assertEquals(3, Regex("<trkpt ").findAll(gpx).count())
        assertTrue(gpx.contains("""<trkpt lat="1.0000000" lon="2.0000000"><ele>10.0</ele><time>2025-10-09T08:53:20.000Z</time></trkpt>"""))
        assertTrue(gpx.contains("<name>Run &lt;1&gt; &amp; co</name>"))
        assertTrue(gpx.contains("<type>running</type>"))
    }

    @Test
    fun theFileIsNamedInTheRunnersTime() {
        assertEquals("calcpace-2025-10-09-055320.gpx", Gpx.filename(1_760_000_000_000L, ZoneId.of("America/Sao_Paulo")))
    }

    @Test
    fun theScreenReadsLikeARunningApp() {
        assertEquals("0:42", RunFormat.clock(42_400))
        assertEquals("12:05", RunFormat.clock(725_000))
        assertEquals("1:02:09", RunFormat.clock(3_729_000))
        assertEquals("3.42 km", RunFormat.km(3_417.0))
        assertEquals("5:07 /km", RunFormat.pace(307.4))
        assertEquals("5:00 /km", RunFormat.pace(299.6))
        assertEquals("–:– /km", RunFormat.pace(null))
        assertEquals("–:– /km", RunFormat.pace(Double.POSITIVE_INFINITY))
    }
}
