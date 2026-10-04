package app.calcpace.health

import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HcPayloadTest {
    private val start = Instant.parse("2026-10-04T09:00:00Z")
    private val end = Instant.parse("2026-10-04T09:53:10Z")

    private fun at(minutes: Long, seconds: Long = 0): Instant = start.plusSeconds(minutes * 60 + seconds)

    private fun facts(
        type: String? = "running",
        distanceM: Double? = 10214.5,
        pauses: List<Pair<Instant, Instant>> = emptyList(),
        title: String? = "Morning Run",
        device: String? = "Garmin Forerunner 265",
        hrAvg: Long? = 152,
        hrMax: Long? = 171,
        elevation: Double? = 48.2,
    ) = SessionFacts(
        id = "6f1c-uuid", type = type, start = start, end = end, zoneOffsetSeconds = -10800, title = title,
        origin = "com.garmin.android.apps.connectmobile", device = device, distanceM = distanceM, pauses = pauses,
        hrAvg = hrAvg, hrMax = hrMax, elevationGainM = elevation,
    )

    private fun session(id: String) = WireSession(
        id = id, type = "running", start = "2026-10-04T09:00:00Z", end = "2026-10-04T09:53:10Z",
        origin = "com.strava", distanceM = 5000.0, durationS = 1500,
    )

    @Test
    fun aPauseIsTakenOffTheElapsedTime() {
        // 53:10 with a 3:10 pause.
        assertEquals(3000L, HcPayload.activeSeconds(start, end, listOf(at(20) to at(23, 10))))
    }

    @Test
    fun aPauseSpillingOutOfTheSessionIsClipped() {
        assertEquals(3190L - 120, HcPayload.activeSeconds(start, end, listOf(start.minusSeconds(600) to at(2))))
        assertEquals(3190L - 70, HcPayload.activeSeconds(start, end, listOf(at(52) to end.plusSeconds(600))))
    }

    @Test
    fun overlappingPausesCountOnce() {
        val pauses = listOf(at(10) to at(15), at(12) to at(14), at(14) to at(17))
        assertEquals(3190L - 420, HcPayload.activeSeconds(start, end, pauses))
    }

    @Test
    fun aPauseOutsideTheSessionDoesNotCount() {
        val pauses = listOf(start.minusSeconds(900) to start.minusSeconds(60), end.plusSeconds(1) to end.plusSeconds(90))
        assertEquals(3190L, HcPayload.activeSeconds(start, end, pauses))
    }

    @Test
    fun aSessionThatIsNotARunIsNotSent() {
        assertEquals(HcPayload.Outcome.NotARun, HcPayload.wire(facts(type = null)))
    }

    @Test
    fun aSessionPausedFromStartToEndIsNotSent() {
        assertEquals(HcPayload.Outcome.NotARun, HcPayload.wire(facts(pauses = listOf(start to end))))
    }

    @Test
    fun aSessionWithoutADistanceYetWaits() {
        assertEquals(HcPayload.Outcome.NoDistanceYet, HcPayload.wire(facts(distanceM = null)))
        assertEquals(HcPayload.Outcome.NoDistanceYet, HcPayload.wire(facts(distanceM = 0.0)))
    }

    @Test
    fun aRunGoesOutWithExactlyTheContractsKeys() {
        val ready = HcPayload.wire(facts(pauses = listOf(at(20) to at(23, 10)))) as HcPayload.Outcome.Ready
        val body = Json.parseToJsonElement(HcPayload.encode(Upload(false, listOf(ready.session), listOf("0a9e-uuid"))))
            .jsonObject
        assertEquals(setOf("initial", "sessions", "deleted"), body.keys)
        assertEquals("false", body["initial"].toString())
        assertEquals("[\"0a9e-uuid\"]", body["deleted"].toString())
        val sent = body["sessions"]!!.jsonArray.single().jsonObject
        assertEquals(
            setOf(
                "id", "type", "start", "end", "zone_offset", "title", "origin", "device", "distance_m",
                "duration_s", "hr_avg", "hr_max", "elevation_gain_m"
            ),
            sent.keys
        )
        assertEquals("\"2026-10-04T09:00:00Z\"", sent["start"].toString())
        assertEquals("\"2026-10-04T09:53:10Z\"", sent["end"].toString())
        assertEquals("3000", sent["duration_s"].toString())
        assertEquals("10214.5", sent["distance_m"].toString())
        assertEquals("-10800", sent["zone_offset"].toString())
    }

    @Test
    fun theOptionalFieldsAreLeftOutWhenMissing() {
        val ready = HcPayload.wire(
            facts(title = "  ", device = null, hrAvg = null, hrMax = null, elevation = null)
        ) as HcPayload.Outcome.Ready
        val sent = Json.parseToJsonElement(HcPayload.encode(Upload(true, listOf(ready.session), emptyList())))
            .jsonObject["sessions"]!!.jsonArray.single().jsonObject
        assertEquals(
            setOf("id", "type", "start", "end", "zone_offset", "origin", "distance_m", "duration_s"),
            sent.keys
        )
    }

    @Test
    fun longTitlesAndDevicesAreCut() {
        val ready = HcPayload.wire(facts(title = "x".repeat(300), device = "y".repeat(150))) as HcPayload.Outcome.Ready
        assertEquals(255, ready.session.title!!.length)
        assertEquals(100, ready.session.device!!.length)
    }

    @Test
    fun bigSyncsAreSplitIntoRequestsTheServerTakes() {
        val uploads = HcPayload.batches((1..120).map { session("s$it") }, (1..10).map { "d$it" }, initial = true)
        assertEquals(listOf(50, 50, 20), uploads.map { it.sessions.size })
        assertEquals(listOf(10, 0, 0), uploads.map { it.deleted.size })
        assertTrue(uploads.all { it.initial })
    }

    @Test
    fun manyDeletionsAreSplitToo() {
        val uploads = HcPayload.batches(listOf(session("s")), (1..450).map { "d$it" }, initial = false)
        assertEquals(listOf(1, 0, 0), uploads.map { it.sessions.size })
        assertEquals(listOf(200, 200, 50), uploads.map { it.deleted.size })
    }

    @Test
    fun nothingToSayMeansNoRequest() {
        assertEquals(emptyList<Upload>(), HcPayload.batches(emptyList(), emptyList(), initial = false))
    }

    @Test
    fun theSameSessionOrIdTwiceGoesOnceWithTheLastWord() {
        val first = session("s1")
        val again = session("s1").copy(distanceM = 6000.0)
        val upload = HcPayload.batches(listOf(first, session("s2"), again), listOf("d", "d"), initial = false).single()
        assertEquals(listOf("s1", "s2"), upload.sessions.map { it.id })
        assertEquals(6000.0, upload.sessions.first().distanceM, 0.0)
        assertEquals(listOf("d"), upload.deleted)
    }

    @Test
    fun aLinkTokenIs43Base64UrlCharacters() {
        val token = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJ-_01234"
        assertEquals(43, token.length)
        assertTrue(HcPayload.isLinkToken(token))
        assertFalse(HcPayload.isLinkToken(token.drop(1)))
        assertFalse(HcPayload.isLinkToken(token + "x"))
        listOf('+', '/', '=', ' ').forEach { assertFalse("$it", HcPayload.isLinkToken(token.dropLast(1) + it)) }
        assertFalse(HcPayload.isLinkToken(null))
        assertFalse(HcPayload.isLinkToken(""))
        assertFalse(HcPayload.isLinkToken("$token\n"))
    }

    @Test
    fun theTokenComesOutOfALinkMessage() {
        val token = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJ-_01234"
        assertEquals(token, HcPayload.linkTokenFrom("""{"token":"$token"}"""))
        assertEquals(token, HcPayload.linkTokenFrom("""{"token":"$token","other":1}"""))
        assertNull(HcPayload.linkTokenFrom("""{"token":"short"}"""))
        assertNull(HcPayload.linkTokenFrom("""{"token":null}"""))
        assertNull(HcPayload.linkTokenFrom("""{"token":12}"""))
        assertNull(HcPayload.linkTokenFrom("""{}"""))
        assertNull(HcPayload.linkTokenFrom("""["$token"]"""))
        assertNull(HcPayload.linkTokenFrom("not json"))
        assertNull(HcPayload.linkTokenFrom(null))
    }
}
