package app.calcpace.health

import android.os.RemoteException
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseSegment
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant

/**
 * Reads exercise sessions and their totals from Health Connect. Only what
 * [granted] allows is asked for: heart rate and climb are optional, and
 * asking for an ungranted type would throw.
 */
class HcReader(private val client: HealthConnectClient, private val granted: Set<String>) {

    suspend fun sessionsSince(since: Instant): List<ExerciseSessionRecord> {
        val all = mutableListOf<ExerciseSessionRecord>()
        var page: String? = null
        do {
            val response = client.readRecords(
                ReadRecordsRequest(ExerciseSessionRecord::class, TimeRangeFilter.after(since), pageToken = page)
            )
            all += response.records
            page = response.pageToken
        } while (!page.isNullOrEmpty()) // the docs warn the last page may come back with ""
        return all
    }

    sealed interface Read {
        data class Found(val record: ExerciseSessionRecord) : Read
        data object Gone : Read
        data object Unreadable : Read
    }

    /**
     * The session with this id. Gone only when Health Connect says there is
     * no such record (HcSyncPlan.isNotFound); any other IPC failure leaves it
     * for the next sync. Rate limits and permission errors still throw: they
     * stop the whole sync.
     */
    suspend fun session(id: String): Read =
        try {
            Read.Found(client.readRecord(ExerciseSessionRecord::class, id).record)
        } catch (e: RemoteException) {
            if (HcSyncPlan.isNotFound(e.message)) Read.Gone else Read.Unreadable
        } catch (_: IllegalArgumentException) {
            Read.Unreadable // the provider app (Android 9–13) may refuse an id this way: try again later
        }

    /** Distance, heart rate and climb of this session, from the app that recorded it only. */
    suspend fun facts(record: ExerciseSessionRecord): SessionFacts {
        val type = wireType(record.exerciseType)
        val hr = HealthConnect.HEART_RATE in granted
        val climb = HealthConnect.ELEVATION in granted
        // Not a run: no totals to read, it goes no further.
        val result = if (type == null) null else client.aggregate(
            AggregateRequest(
                metrics = buildSet {
                    add(DistanceRecord.DISTANCE_TOTAL)
                    if (hr) {
                        add(HeartRateRecord.BPM_AVG)
                        add(HeartRateRecord.BPM_MAX)
                    }
                    if (climb) add(ElevationGainedRecord.ELEVATION_GAINED_TOTAL)
                },
                timeRangeFilter = TimeRangeFilter.between(record.startTime, record.endTime),
                // A watch and a phone both writing distance would add up.
                dataOriginFilter = setOf(record.metadata.dataOrigin),
            )
        )
        return SessionFacts(
            id = record.metadata.id,
            type = type,
            start = record.startTime,
            end = record.endTime,
            zoneOffsetSeconds = record.startZoneOffset?.totalSeconds,
            title = record.title,
            origin = record.metadata.dataOrigin.packageName,
            device = record.metadata.device?.let { device ->
                listOfNotNull(device.manufacturer, device.model).joinToString(" ").ifBlank { null }
            },
            distanceM = result?.get(DistanceRecord.DISTANCE_TOTAL)?.inMeters,
            pauses = record.segments.filter { it.segmentType in PAUSES }.map { it.startTime to it.endTime },
            hrAvg = result?.get(HeartRateRecord.BPM_AVG),
            hrMax = result?.get(HeartRateRecord.BPM_MAX),
            elevationGainM = result?.get(ElevationGainedRecord.ELEVATION_GAINED_TOTAL)?.inMeters,
        )
    }

    private fun wireType(type: Int): String? = when (type) {
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING -> "running"
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> "running_treadmill"
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "walking"
        else -> null // hiking included: left out on purpose for now
    }

    private companion object {
        val PAUSES = setOf(ExerciseSegment.EXERCISE_SEGMENT_TYPE_PAUSE, ExerciseSegment.EXERCISE_SEGMENT_TYPE_REST)
    }
}
