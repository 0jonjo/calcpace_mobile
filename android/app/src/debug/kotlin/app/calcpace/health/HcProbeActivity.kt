package app.calcpace.health

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.contracts.ExerciseRouteRequestContract
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseRoute
import androidx.health.connect.client.records.ExerciseRouteResult
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.PowerRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsCadenceRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.lifecycle.lifecycleScope
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.coroutines.cancellation.CancellationException
import kotlin.reflect.KClass
import kotlinx.coroutines.launch

/**
 * Debug builds only, with its own launcher icon ("HC Probe"): lists what
 * Health Connect holds for each exercise session of the last 30 days,
 * beyond the totals the import reads — laps, segments, the route, and the
 * series (heart rate, speed, cadence, power) with the app each came from.
 * Nothing is saved or sent; "Share report" hands the text to any app.
 *
 * The question it answers: which running apps write more than a distance
 * and a time, and so whether a fuller import through Health Connect is worth
 * building. A route written by another app is read only through Health
 * Connect's per-session dialog ("Ask for route"), and only in the
 * foreground; the import never asks for it.
 */
class HcProbeActivity : ComponentActivity() {
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var shareButton: Button
    private val reports = linkedMapOf<String, StringBuilder>()
    private val views = mutableMapOf<String, TextView>()
    private var pendingRoute: String? = null

    private val request = registerForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        probe()
    }

    private val routeRequest = registerForActivityResult(ExerciseRouteRequestContract()) { route ->
        val id = pendingRoute ?: return@registerForActivityResult
        pendingRoute = null
        append(id, "  route (asked): " + (route?.let(::describe) ?: "refused or none"))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { textSize = 14f }
        // Off until every session is read: the chooser (like the route
        // dialog) would put the probe in the background, where Health
        // Connect refuses the reads still to come.
        shareButton = Button(this).apply {
            text = "Share report"
            isEnabled = false
            setOnClickListener { share() }
        }
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            addView(status)
            addView(shareButton)
        }
        val root = ScrollView(this).apply { addView(list) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(root)

        val client = HealthConnect.client(this) ?: return say("Health Connect is ${HealthConnect.status(this)}")
        lifecycleScope.launch {
            if (client.permissionController.getGrantedPermissions().containsAll(PERMISSIONS)) probe()
            else request.launch(PERMISSIONS)
        }
    }

    private fun probe() {
        val client = HealthConnect.client(this) ?: return say("Health Connect is ${HealthConnect.status(this)}")
        lifecycleScope.launch {
            try {
                val granted = client.permissionController.getGrantedPermissions()
                val missing = PERMISSIONS - granted
                val since = Instant.now().minus(Duration.ofDays(30))
                val sessions = readAll(client, ExerciseSessionRecord::class, TimeRangeFilter.after(since))
                    .sortedByDescending { it.startTime }
                say(
                    "${sessions.size} sessions since ${DAY.format(since)}." +
                        (if (missing.isEmpty()) "" else "\nNot granted: " + missing.joinToString { it.substringAfterLast('.') })
                )
                sessions.forEach { session(client, it, granted) }
                sessions.filter { it.exerciseRouteResult is ExerciseRouteResult.ConsentRequired }
                    .forEach { addRouteButton(it.metadata.id) }
                say(status.text.toString() + "\nDone.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                say("failed: $e")
            }
            shareButton.isEnabled = true
        }
    }

    private suspend fun session(client: HealthConnectClient, session: ExerciseSessionRecord, granted: Set<String>) {
        val id = session.metadata.id
        val report = StringBuilder()
        reports[id] = report
        val view = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setPadding(0, 24, 0, 8)
        }
        views[id] = view
        list.addView(view)

        val device = session.metadata.device
        report.appendLine("== ${session.metadata.dataOrigin.packageName} · ${TYPES[session.exerciseType] ?: "type ${session.exerciseType}"}")
        report.appendLine("  ${TIME.format(session.startTime)} · ${minutes(session.startTime, session.endTime)} min · title: ${session.title ?: "-"}")
        report.appendLine(
            "  device: " + (device?.let { listOfNotNull(DEVICES[it.type], it.manufacturer, it.model).joinToString(" ") } ?: "-") +
                " · recording: " + (RECORDING[session.metadata.recordingMethod] ?: session.metadata.recordingMethod)
        )
        report.appendLine(
            "  laps: ${session.laps.size}" +
                session.laps.take(5).joinToString("") { " [${it.length?.inMeters?.let { m -> "%.0fm".format(m) } ?: "?"}/${minutes(it.startTime, it.endTime)}min]" } +
                (if (session.laps.size > 5) " …" else "")
        )
        report.appendLine("  segments: ${session.segments.size}" + session.segments.groupingBy { it.segmentType }.eachCount().entries.joinToString("") { " ${it.key}×${it.value}" })
        report.appendLine(
            "  route: " + when (val route = session.exerciseRouteResult) {
                is ExerciseRouteResult.Data -> describe(route.exerciseRoute)
                is ExerciseRouteResult.ConsentRequired -> "exists, needs consent"
                is ExerciseRouteResult.NoData -> "none"
                else -> route.javaClass.simpleName
            }
        )

        // Records that overlap the session, from every app, not only the
        // session's own: a phone app may record the run while the watch's
        // own app writes the heart rate. Overlap, not ownership: an app's
        // hourly steps or calories count whole. VO2max is written once the
        // run is over, so its window runs two hours past the end.
        SERIES.forEach { (name, type) ->
            val end = if (type == Vo2MaxRecord::class) session.endTime.plus(Duration.ofHours(2)) else session.endTime
            val window = TimeRangeFilter.between(session.startTime, end)
            if (HealthPermission.getReadPermission(type) !in granted) {
                report.appendLine("  $name: not granted")
                return@forEach
            }
            var records: List<Record> = emptyList()
            val line = try {
                records = readAll(client, type, window)
                if (records.isEmpty()) "none"
                else records.groupBy { it.metadata.dataOrigin.packageName }.entries.joinToString(" · ") { (origin, list) ->
                    val own = if (origin == session.metadata.dataOrigin.packageName) "" else " (other app)"
                    "$origin$own ${list.size} rec" + samples(list) + total(list)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "failed: ${e.javaClass.simpleName}: ${e.message}"
            }
            report.appendLine("  $name: $line")
            if (type == StepsRecord::class) steps(records).forEach { report.appendLine(it) }
        }
        render(id)
    }

    // Right under the session's text, once every session is read.
    private fun addRouteButton(id: String) {
        val view = views[id] ?: return
        list.addView(Button(this).apply {
            text = "Ask for route"
            setOnClickListener {
                pendingRoute = id
                routeRequest.launch(id)
            }
        }, list.indexOfChild(view) + 1)
    }

    private fun samples(records: List<Record>): String {
        val count = records.sumOf { record ->
            when (record) {
                is HeartRateRecord -> record.samples.size
                is SpeedRecord -> record.samples.size
                is StepsCadenceRecord -> record.samples.size
                is PowerRecord -> record.samples.size
                else -> 0
            }
        }
        return if (count > 0) ", $count samples" else ""
    }

    // One line per steps record, in time order, with its pace and the gap
    // since the one before: Samsung Health writes no speed and no segments,
    // so a stretch of few or no steps is the only trace of a pause it leaves.
    private fun steps(records: List<Record>): List<String> {
        val sorted = records.filterIsInstance<StepsRecord>().sortedBy { it.startTime }
        return sorted.mapIndexed { i, record ->
            val seconds = Duration.between(record.startTime, record.endTime).seconds
            val rate = if (seconds > 0) "%.0f/min".format(record.count * 60.0 / seconds) else "-"
            val gap = sorted.getOrNull(i - 1)?.let { Duration.between(it.endTime, record.startTime).seconds }
            "    ${CLOCK.format(record.startTime)}–${CLOCK.format(record.endTime)} ${seconds}s ${record.count} steps $rate" +
                (if (gap != null && gap != 0L) " (gap ${gap}s)" else "") +
                " ${record.metadata.dataOrigin.packageName}"
        }
    }

    private fun total(records: List<Record>): String = when (records.first()) {
        is DistanceRecord -> ", %.2f km".format(records.sumOf { (it as DistanceRecord).distance.inKilometers })
        is ElevationGainedRecord -> ", %.0f m".format(records.sumOf { (it as ElevationGainedRecord).elevation.inMeters })
        is StepsRecord -> ", ${records.sumOf { (it as StepsRecord).count }} steps"
        is ActiveCaloriesBurnedRecord -> ", %.0f kcal".format(records.sumOf { (it as ActiveCaloriesBurnedRecord).energy.inKilocalories })
        is TotalCaloriesBurnedRecord -> ", %.0f kcal".format(records.sumOf { (it as TotalCaloriesBurnedRecord).energy.inKilocalories })
        is Vo2MaxRecord -> ", " + records.joinToString { "%.1f".format((it as Vo2MaxRecord).vo2MillilitersPerMinuteKilogram) }
        else -> ""
    }

    private fun describe(route: ExerciseRoute): String {
        val points = route.route
        if (points.isEmpty()) return "0 points"
        val altitude = points.count { it.altitude != null }
        val accuracy = points.count { it.horizontalAccuracy != null }
        val gap = points.zipWithNext { a, b -> Duration.between(a.time, b.time).seconds }.average()
        val every = if (gap.isNaN()) "" else ", every ~%.0fs".format(gap)
        return "${points.size} points$every, altitude on $altitude, accuracy on $accuracy"
    }

    private suspend fun <T : Record> readAll(client: HealthConnectClient, type: KClass<T>, window: TimeRangeFilter): List<T> {
        val all = mutableListOf<T>()
        var page: String? = null
        do {
            val response = client.readRecords(ReadRecordsRequest(type, window, pageToken = page))
            all += response.records
            page = response.pageToken
        } while (!page.isNullOrEmpty())
        return all
    }

    private fun append(id: String, line: String) {
        reports[id]?.appendLine(line)
        render(id)
    }

    private fun render(id: String) {
        val text = reports[id].toString()
        views[id]?.text = text
        Log.i(TAG, text)
    }

    private fun say(message: String) {
        status.text = message
        Log.i(TAG, message)
    }

    private fun share() {
        val text = "HC Probe\n${status.text}\n\n" + reports.values.joinToString("\n")
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
    }

    private fun minutes(from: Instant, to: Instant) = Duration.between(from, to).toMinutes()

    private companion object {
        const val TAG = "HcProbe"
        val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM").withZone(ZoneId.systemDefault())
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(ZoneId.systemDefault())
        val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

        val SERIES: List<Pair<String, KClass<out Record>>> = listOf(
            "distance" to DistanceRecord::class,
            "heart rate" to HeartRateRecord::class,
            "speed" to SpeedRecord::class,
            "cadence" to StepsCadenceRecord::class,
            "steps" to StepsRecord::class,
            "power" to PowerRecord::class,
            "climb" to ElevationGainedRecord::class,
            "active kcal" to ActiveCaloriesBurnedRecord::class,
            "total kcal" to TotalCaloriesBurnedRecord::class,
            "vo2max" to Vo2MaxRecord::class,
        )

        val PERMISSIONS: Set<String> =
            setOf(HealthPermission.getReadPermission(ExerciseSessionRecord::class)) +
                SERIES.map { HealthPermission.getReadPermission(it.second) }

        val TYPES = mapOf(
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING to "running",
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL to "treadmill",
            ExerciseSessionRecord.EXERCISE_TYPE_WALKING to "walking",
            ExerciseSessionRecord.EXERCISE_TYPE_HIKING to "hiking",
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING to "biking",
        )

        val DEVICES = mapOf(
            Device.TYPE_WATCH to "watch",
            Device.TYPE_PHONE to "phone",
            Device.TYPE_RING to "ring",
            Device.TYPE_FITNESS_BAND to "band",
            Device.TYPE_CHEST_STRAP to "chest strap",
        )

        val RECORDING = mapOf(
            Metadata.RECORDING_METHOD_ACTIVELY_RECORDED to "active",
            Metadata.RECORDING_METHOD_AUTOMATICALLY_RECORDED to "automatic",
            Metadata.RECORDING_METHOD_MANUAL_ENTRY to "manual",
            Metadata.RECORDING_METHOD_UNKNOWN to "unknown",
        )
    }
}
