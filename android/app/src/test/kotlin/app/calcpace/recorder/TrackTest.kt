package app.calcpace.recorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackTest {
    // Northward along a meridian: 0.00001° of latitude ≈ 1.112 m.
    private val lat0 = -5.84
    private val lon0 = -35.2
    private val t0 = 1_760_000_000_000L

    private fun fix(seconds: Int, metersNorth: Double, accuracy: Float = 5f) =
        RunEvent.Fix(t0 + seconds * 1000L, lat0 + metersNorth / 111_195.0, lon0, 30.0, accuracy)

    /** A steady run at [speed] m/s, one fix a second, from [from] seconds. */
    private fun run(from: Int, seconds: Int, speed: Double = 3.0, startAt: Double = 0.0) =
        (0..seconds).map { fix(from + it, startAt + it * speed) }

    @Test
    fun aSteadyRunAddsUpItsDistanceAndTime() {
        val track = Track.of(listOf(RunEvent.Start(t0)) + run(0, 600) + RunEvent.Stop(t0 + 600_000))

        assertEquals(1800.0, track.distanceMeters, 6.0)
        assertEquals(600_000L, track.movingMillis(t0 + 999_999))
        assertEquals(333.3, track.averagePace(t0 + 600_000)!!, 1.5)
        assertTrue(track.isStopped)
        assertEquals(1, track.segments.size)
        assertEquals(601, track.segments[0].size)
    }

    @Test
    fun standingStillWithADriftingGpsAddsNothing() {
        val drift = (0..120).map { fix(it, if (it % 2 == 0) 0.0 else 3.0, accuracy = 8f) }
        val track = Track.of(listOf(RunEvent.Start(t0)) + drift)

        assertEquals(0.0, track.distanceMeters, 0.001)
        assertNull(track.averagePace(t0 + 120_000))
    }

    @Test
    fun imprecisefixesAreDropped() {
        val events = listOf(RunEvent.Start(t0), fix(0, 0.0, accuracy = 60f), fix(1, 500.0, accuracy = 40f)) + run(2, 10, startAt = 0.0)
        val track = Track.of(events)

        assertEquals(2, track.dropped)
        assertEquals(30.0, track.distanceMeters, 1.0)
    }

    @Test
    fun aSingleJumpIsDroppedAndTheRunGoesOn() {
        val events = listOf(RunEvent.Start(t0)) + run(0, 10) + fix(11, 400.0) + run(12, 10, startAt = 36.0)
        val track = Track.of(events)

        assertEquals(1, track.dropped)
        // 0 → 30 m, then 36 → 66 m: the 400 m spike is not in it.
        assertEquals(66.0, track.distanceMeters, 2.0)
    }

    // The fix the track was counting from was the wrong one: after a few
    // "jumps" in a row it starts again from where the GPS now is, without
    // adding the jump itself.
    @Test
    fun aRunOfJumpsMovesTheTrackWithoutCountingTheJump() {
        val events = listOf(RunEvent.Start(t0)) + run(0, 5) +
            (6..20).map { fix(it, 2000.0 + (it - 6) * 3.0) }
        val track = Track.of(events)

        assertEquals(Track.MAX_JUMPS - 1, track.dropped)
        assertTrue("got ${track.distanceMeters}", track.distanceMeters < 15 + 40)
    }

    @Test
    fun pausesLeaveTheClockAndTheDistanceAlone() {
        val events = listOf(RunEvent.Start(t0)) + run(0, 100) +
            RunEvent.Pause(t0 + 100_000) +
            fix(150, 600.0) + // the GPS still reports while paused: ignored
            RunEvent.Resume(t0 + 200_000) + run(200, 100, startAt = 900.0) +
            RunEvent.Stop(t0 + 300_000)
        val track = Track.of(events)

        assertEquals(200_000L, track.movingMillis(t0 + 400_000))
        // Nothing counted across the pause, even though the runner moved.
        assertEquals(600.0, track.distanceMeters, 6.0)
        assertEquals(2, track.segments.size)
    }

    @Test
    fun theClockRunsUntilNowWhileRunningAndStopsWhilePaused() {
        val track = Track.of(listOf(RunEvent.Start(t0)))
        assertTrue(track.isRunning)
        assertEquals(42_000L, track.movingMillis(t0 + 42_000))

        track.add(RunEvent.Pause(t0 + 50_000))
        assertTrue(track.isPaused)
        assertEquals(50_000L, track.movingMillis(t0 + 90_000))
    }

    @Test
    fun theLongestGapBetweenFixesIsKept() {
        val events = listOf(RunEvent.Start(t0)) + run(0, 10) + run(70, 10, startAt = 30.0)
        assertEquals(60_000L, Track.of(events).longestGapMillis)
    }

    @Test
    fun eventsOutOfPlaceChangeNothing() {
        val track = Track.of(
            listOf(
                fix(0, 0.0), RunEvent.Resume(t0), RunEvent.Pause(t0), RunEvent.Stop(t0),
                RunEvent.Start(t0 + 1000), RunEvent.Start(t0 + 5000), RunEvent.Resume(t0 + 6000),
            )
        )

        assertEquals(t0 + 1000, track.startedAt)
        assertTrue(track.isRunning)
        assertFalse(track.isStopped)
        assertEquals(0, track.fixes)
    }

    @Test
    fun distanceMatchesAKnownLeg() {
        // Ponta Negra to Natal city centre, about 10.35 km as the crow flies.
        val a = RunEvent.Fix(0, -5.8796, -35.1729, null, 5f)
        val b = RunEvent.Fix(0, -5.7945, -35.2110, null, 5f)
        assertEquals(10_350.0, Track.distance(a, b), 150.0)
    }
}
