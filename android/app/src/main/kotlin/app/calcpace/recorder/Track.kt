package app.calcpace.recorder

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A run as the recorder sees it, rebuilt by feeding it the [RunEvent]s in
 * order: the same events live on screen and, after a kill, read back from
 * the [RunLog]. It decides which fixes count:
 *
 * - a fix less precise than [MAX_ACCURACY_M] is dropped (the first seconds
 *   of a cold GPS, a run under trees);
 * - a fix that would mean running faster than [MAX_SPEED_MPS] from the last
 *   one kept is a jump, and is dropped too. After [MAX_JUMPS] jumps in a row
 *   the last kept fix is what was wrong, so the track starts again from the
 *   new position without adding the jump to the distance;
 * - distance grows only once the runner is [MIN_STEP_M] away from the last
 *   counted position, so a GPS drifting around someone standing still at a
 *   traffic light adds nothing.
 *
 * Moving time is the clock minus the pauses the runner made; a GPS that lost
 * its fix is still running time. Every kept fix goes in the route ([segments],
 * one per stretch between pauses), counted or not.
 */
class Track {
    var startedAt: Long? = null
        private set
    var stoppedAt: Long? = null
        private set
    var distanceMeters = 0.0
        private set
    var fixes = 0
        private set
    var dropped = 0
        private set
    /** The longest wait between two fixes while running: a phone that
     *  stopped the recorder in the background shows here. */
    var longestGapMillis = 0L
        private set
    var lastAccuracy: Float? = null
        private set

    private val stretches = mutableListOf<MutableList<RunEvent.Fix>>()
    val segments: List<List<RunEvent.Fix>> get() = stretches.filter { it.isNotEmpty() }

    private var movingBefore = 0L
    private var runningSince: Long? = null
    private var counted: RunEvent.Fix? = null
    private var lastKept: RunEvent.Fix? = null
    private var lastFixTime: Long? = null
    private var jumps = 0

    val isRunning: Boolean get() = runningSince != null
    val isPaused: Boolean get() = startedAt != null && stoppedAt == null && runningSince == null
    val isStopped: Boolean get() = stoppedAt != null

    fun movingMillis(now: Long): Long = movingBefore + (runningSince?.let { (now - it).coerceAtLeast(0) } ?: 0)

    /** Seconds per kilometre over the whole run, or null before 50 m. */
    fun averagePace(now: Long): Double? =
        if (distanceMeters < 50) null else movingMillis(now) / 1000.0 / (distanceMeters / 1000.0)

    fun add(event: RunEvent) {
        when (event) {
            is RunEvent.Start -> if (startedAt == null) {
                startedAt = event.time
                begin(event.time)
            }
            is RunEvent.Pause -> if (isRunning) end(event.time)
            is RunEvent.Resume -> if (isPaused) begin(event.time)
            is RunEvent.Stop -> if (startedAt != null && stoppedAt == null) {
                if (isRunning) end(event.time)
                stoppedAt = event.time
            }
            is RunEvent.Fix -> if (isRunning) fix(event)
        }
    }

    private fun begin(time: Long) {
        runningSince = time
        stretches.add(mutableListOf())
        counted = null
        lastKept = null
        lastFixTime = null
        jumps = 0
    }

    private fun end(time: Long) {
        movingBefore += (time - runningSince!!).coerceAtLeast(0)
        runningSince = null
    }

    private fun fix(fix: RunEvent.Fix) {
        fixes++
        lastAccuracy = fix.accuracy
        lastFixTime?.let { longestGapMillis = maxOf(longestGapMillis, fix.time - it) }
        lastFixTime = fix.time
        if (fix.accuracy > MAX_ACCURACY_M) {
            dropped++
            return
        }

        val previous = lastKept
        if (previous != null && isJump(previous, fix)) {
            if (++jumps < MAX_JUMPS) {
                dropped++
                return
            }
            counted = null
        }
        jumps = 0
        lastKept = fix
        stretches.last().add(fix)

        val from = counted
        if (from == null) {
            counted = fix
            return
        }
        val step = distance(from, fix)
        if (step >= MIN_STEP_M) {
            distanceMeters += step
            counted = fix
        }
    }

    private fun isJump(from: RunEvent.Fix, to: RunEvent.Fix): Boolean {
        val seconds = (to.time - from.time) / 1000.0
        if (seconds <= 0) return distance(from, to) > MIN_STEP_M
        return distance(from, to) / seconds > MAX_SPEED_MPS
    }

    companion object {
        const val MAX_ACCURACY_M = 25f
        const val MAX_SPEED_MPS = 12.0
        const val MAX_JUMPS = 5
        const val MIN_STEP_M = 5.0
        private const val EARTH_RADIUS_M = 6_371_008.8

        fun of(events: List<RunEvent>): Track = Track().apply { events.forEach(::add) }

        fun distance(from: RunEvent.Fix, to: RunEvent.Fix): Double {
            val dLat = Math.toRadians(to.lat - from.lat)
            val dLon = Math.toRadians(to.lon - from.lon)
            val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(from.lat)) * cos(Math.toRadians(to.lat)) * sin(dLon / 2).pow(2)
            return 2 * EARTH_RADIUS_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
        }
    }
}
