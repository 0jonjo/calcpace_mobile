package app.calcpace.health

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseSegment
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Length
import androidx.lifecycle.lifecycleScope
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.coroutines.launch

/**
 * Debug builds only: writes made-up sessions to Health Connect from adb, to
 * test the import on an emulator. The first run asks for the write
 * permissions (run it again after allowing). Every action logs under the
 * "HcSeed" tag, with the session's Health Connect id.
 *
 * ```
 * P=app.calcpace.twa   # or app.calcpace.twa.debug
 * adb shell am start -n $P/app.calcpace.health.HcSeedActivity --es action insert \
 *     --es type running --ef km 10.2 --ei minutes 52 --ei pause_minutes 3 --ei hours_ago 2 --ei hr 150
 * adb shell am start -n $P/app.calcpace.health.HcSeedActivity --es action insert --ei days_ago 10
 * adb shell am start -n $P/app.calcpace.health.HcSeedActivity --es action insert --es type biking
 * adb shell am start -n $P/app.calcpace.health.HcSeedActivity --es action update --es id <id> --ef km 10.5
 * adb shell am start -n $P/app.calcpace.health.HcSeedActivity --es action delete --es id <id>
 * ```
 *
 * Types: running, running_treadmill, walking, biking, hiking. Defaults:
 * running, 10 km, 50 minutes, no pause, 2 hours ago, heart rate 150 (0 for
 * none), 30 m climb (`--ef climb`), `--es title`. `--ei no_distance 1`
 * writes the session alone, as an app that writes the distance later.
 * Records carry client record ids ("hcseed-…"), so an update is an upsert
 * of the same records and keeps the session's id.
 */
class HcSeedActivity : ComponentActivity() {
    private val permissions = setOf(
        HealthPermission.getWritePermission(ExerciseSessionRecord::class),
        HealthPermission.getWritePermission(DistanceRecord::class),
        HealthPermission.getWritePermission(HeartRateRecord::class),
        HealthPermission.getWritePermission(ElevationGainedRecord::class),
    ) + HealthConnect.REQUIRED

    private val request = registerForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        run()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val client = HealthConnect.client(this) ?: return done("Health Connect is ${HealthConnect.status(this)}")
        lifecycleScope.launch {
            if (client.permissionController.getGrantedPermissions().containsAll(permissions)) run()
            else request.launch(permissions)
        }
    }

    private fun run() {
        val client = HealthConnect.client(this) ?: return done("Health Connect is ${HealthConnect.status(this)}")
        lifecycleScope.launch {
            val message = try {
                if (!client.permissionController.getGrantedPermissions().containsAll(permissions)) {
                    "write permissions not granted"
                } else {
                    when (val action = intent.getStringExtra("action") ?: "insert") {
                        "insert" -> insert(client)
                        "update" -> update(client)
                        "delete" -> delete(client)
                        else -> "unknown action $action"
                    }
                }
            } catch (e: Exception) {
                "failed: $e"
            }
            done(message)
        }
    }

    private suspend fun insert(client: HealthConnectClient): String {
        val minutes = intent.getIntExtra("minutes", 50).toLong()
        val daysAgo = intent.getIntExtra("days_ago", 0).toLong()
        val ago = if (daysAgo > 0) Duration.ofDays(daysAgo) else Duration.ofHours(intent.getIntExtra("hours_ago", 2).toLong())
        val end = Instant.now().minus(ago).truncatedTo(ChronoUnit.SECONDS)
        val start = end.minus(Duration.ofMinutes(minutes))
        val type = TYPES[intent.getStringExtra("type") ?: "running"] ?: return "unknown type"
        val key = "hcseed-" + UUID.randomUUID()
        val pause = intent.getIntExtra("pause_minutes", 0).toLong()
        val segments = if (pause > 0) {
            val from = start.plus(Duration.ofMinutes((minutes - pause) / 2))
            listOf(ExerciseSegment(from, from.plus(Duration.ofMinutes(pause)), ExerciseSegment.EXERCISE_SEGMENT_TYPE_PAUSE))
        } else {
            emptyList()
        }
        val session = ExerciseSessionRecord(
            startTime = start, startZoneOffset = offset(start), endTime = end, endZoneOffset = offset(end),
            metadata = metadata(key), exerciseType = type,
            title = intent.getStringExtra("title") ?: "Seeded ${intent.getStringExtra("type") ?: "running"}",
            segments = segments,
        )
        val records = mutableListOf<Record>(session)
        if (intent.getIntExtra("no_distance", 0) == 0) records += distance(key, start, end, intent.getFloatExtra("km", 10f))
        val hr = intent.getIntExtra("hr", 150).toLong()
        if (hr > 0) {
            val samples = generateSequence(start) { it.plusSeconds(60) }.takeWhile { it < end }
                .mapIndexed { i, time -> HeartRateRecord.Sample(time, hr + (i % 7) - 3) }.toList()
            records += HeartRateRecord(start, offset(start), end, offset(end), samples, metadata("$key-hr"))
        }
        val climb = intent.getFloatExtra("climb", 30f).toDouble()
        if (climb > 0) {
            records += ElevationGainedRecord(start, offset(start), end, offset(end), Length.meters(climb), metadata("$key-climb"))
        }
        val ids = client.insertRecords(records).recordIdsList
        return "inserted ${ids.first()}"
    }

    // Same client record ids, newer version: Health Connect updates in place.
    private suspend fun update(client: HealthConnectClient): String {
        val id = intent.getStringExtra("id") ?: return "update needs --es id"
        val old = client.readRecord(ExerciseSessionRecord::class, id).record
        val key = old.metadata.clientRecordId ?: return "$id was not seeded here"
        val session = ExerciseSessionRecord(
            startTime = old.startTime, startZoneOffset = old.startZoneOffset, endTime = old.endTime,
            endZoneOffset = old.endZoneOffset, metadata = metadata(key), exerciseType = old.exerciseType,
            title = old.title, segments = old.segments,
        )
        // The distance first, so a sync between the two writes still sees it.
        client.insertRecords(listOf(distance(key, old.startTime, old.endTime, intent.getFloatExtra("km", 10f))))
        client.insertRecords(listOf(session))
        return "updated $id"
    }

    private suspend fun delete(client: HealthConnectClient): String {
        val id = intent.getStringExtra("id") ?: return "delete needs --es id"
        val key = client.readRecord(ExerciseSessionRecord::class, id).record.metadata.clientRecordId
        client.deleteRecords(ExerciseSessionRecord::class, listOf(id), emptyList())
        if (key != null) {
            client.deleteRecords(DistanceRecord::class, emptyList(), listOf("$key-distance"))
            client.deleteRecords(HeartRateRecord::class, emptyList(), listOf("$key-hr"))
            client.deleteRecords(ElevationGainedRecord::class, emptyList(), listOf("$key-climb"))
        }
        return "deleted $id"
    }

    private fun distance(key: String, start: Instant, end: Instant, km: Float) =
        DistanceRecord(start, offset(start), end, offset(end), Length.kilometers(km.toDouble()), metadata("$key-distance"))

    private fun metadata(clientRecordId: String) =
        Metadata.activelyRecorded(DEVICE, clientRecordId, System.currentTimeMillis())

    private fun offset(at: Instant) = ZoneId.systemDefault().rules.getOffset(at)

    private fun done(message: String) {
        Log.i(TAG, message)
        finish()
    }

    private companion object {
        const val TAG = "HcSeed"
        val DEVICE = Device(type = Device.TYPE_WATCH, manufacturer = "Calcpace", model = "Seed Watch")
        val TYPES = mapOf(
            "running" to ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
            "running_treadmill" to ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL,
            "walking" to ExerciseSessionRecord.EXERCISE_TYPE_WALKING,
            "biking" to ExerciseSessionRecord.EXERCISE_TYPE_BIKING,
            "hiking" to ExerciseSessionRecord.EXERCISE_TYPE_HIKING,
        )
    }
}
