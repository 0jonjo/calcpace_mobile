package app.calcpace.health

import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** What HcReader learned about one session, before it is judged fit to send. */
data class SessionFacts(
    val id: String,
    /** "running" | "running_treadmill" | "walking", or null when it is not a run or a walk. */
    val type: String?,
    val start: Instant,
    val end: Instant,
    val zoneOffsetSeconds: Int?,
    val title: String?,
    val origin: String,
    val device: String?,
    val distanceM: Double?,
    val pauses: List<Pair<Instant, Instant>>,
    val hrAvg: Long?,
    val hrMax: Long?,
    val elevationGainM: Double?,
)

/** One entry of "sessions" in POST /health_connect/sessions. */
@Serializable
data class WireSession(
    val id: String,
    val type: String,
    val start: String,
    val end: String,
    @SerialName("zone_offset") val zoneOffset: Int? = null,
    val title: String? = null,
    val origin: String,
    val device: String? = null,
    @SerialName("distance_m") val distanceM: Double,
    @SerialName("duration_s") val durationS: Long,
    @SerialName("hr_avg") val hrAvg: Long? = null,
    @SerialName("hr_max") val hrMax: Long? = null,
    @SerialName("elevation_gain_m") val elevationGainM: Double? = null,
)

/** The body of one POST /health_connect/sessions. */
@Serializable
data class Upload(val initial: Boolean, val sessions: List<WireSession>, val deleted: List<String>)

/**
 * The wire format of POST /health_connect/sessions, the contract with
 * calcpace_web (HealthConnect::UploadsController), and the link token the
 * page hands over through the "health" bridge component.
 *
 * Kept free of Android types so the rules are unit tested on the JVM.
 */
object HcPayload {
    const val MAX_SESSIONS = 50
    const val MAX_DELETED = 200
    private const val MAX_TITLE = 255
    private const val MAX_DEVICE = 100

    private val json = Json { explicitNulls = false; encodeDefaults = true }
    private val lenient = Json { ignoreUnknownKeys = true }
    private val LINK_TOKEN = Regex("^[A-Za-z0-9_-]{43}$")

    sealed interface Outcome {
        data class Ready(val session: WireSession) : Outcome
        data object NotARun : Outcome
        /** The app that recorded it has not written the distance yet: try again later (HcPending). */
        data object NoDistanceYet : Outcome
    }

    fun wire(facts: SessionFacts): Outcome {
        val type = facts.type ?: return Outcome.NotARun
        val distance = facts.distanceM?.takeIf { it > 0 } ?: return Outcome.NoDistanceYet
        val active = activeSeconds(facts.start, facts.end, facts.pauses)
        if (active <= 0) return Outcome.NotARun
        return Outcome.Ready(
            WireSession(
                id = facts.id,
                type = type,
                start = facts.start.toString(),
                end = facts.end.toString(),
                zoneOffset = facts.zoneOffsetSeconds,
                title = facts.title?.trim()?.take(MAX_TITLE)?.ifEmpty { null },
                origin = facts.origin,
                device = facts.device?.trim()?.take(MAX_DEVICE)?.ifEmpty { null },
                distanceM = distance,
                durationS = active,
                hrAvg = facts.hrAvg,
                hrMax = facts.hrMax,
                elevationGainM = facts.elevationGainM,
            )
        )
    }

    /** Elapsed time minus pauses: each pause clipped to the session, overlaps counted once. */
    fun activeSeconds(start: Instant, end: Instant, pauses: List<Pair<Instant, Instant>>): Long {
        val clipped = pauses.mapNotNull { (from, to) ->
            val a = maxOf(from, start)
            val b = minOf(to, end)
            if (a < b) a to b else null
        }.sortedBy { it.first }
        var paused = 0L
        var cursor = start
        for ((a, b) in clipped) {
            val from = maxOf(a, cursor)
            if (b > from) {
                paused += b.epochSecond - from.epochSecond
                cursor = b
            }
        }
        return (end.epochSecond - start.epochSecond) - paused
    }

    /**
     * At most [MAX_SESSIONS] sessions and [MAX_DELETED] ids per request.
     * Nothing to say, no request: the changes token still moves on.
     */
    fun batches(sessions: List<WireSession>, deleted: List<String>, initial: Boolean): List<Upload> {
        // The same session twice (a pending one that also changed): the last word wins.
        val s = sessions.associateBy { it.id }.values.toList().chunked(MAX_SESSIONS)
        val d = deleted.distinct().chunked(MAX_DELETED)
        val count = maxOf(s.size, d.size)
        return (0 until count).map {
            Upload(initial, s.getOrElse(it) { emptyList() }, d.getOrElse(it) { emptyList() })
        }
    }

    fun encode(upload: Upload): String = json.encodeToString(Upload.serializer(), upload)

    /** The link token the page hands over: 43 base64url characters (HealthConnectLink on the server). */
    fun isLinkToken(token: String?): Boolean = token != null && LINK_TOKEN.matches(token)

    /** The token in a "link" message's data (`{ "token": T, "grant": G }`), or null when it is missing or malformed. */
    fun linkTokenFrom(jsonData: String?): String? = stringField(jsonData, "token")?.takeIf { isLinkToken(it) }

    /** The grant in a "link" message's data: what the "enable" reply handed out (HcLinkGate). */
    fun linkGrantFrom(jsonData: String?): String? = stringField(jsonData, "grant")

    private fun stringField(jsonData: String?, key: String): String? = runCatching {
        (lenient.parseToJsonElement(jsonData ?: return null).jsonObject[key] as? JsonPrimitive)
            ?.takeIf { it.isString }?.content
    }.getOrNull()
}
