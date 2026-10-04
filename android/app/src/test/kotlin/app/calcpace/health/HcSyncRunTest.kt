package app.calcpace.health

import app.calcpace.health.HcSyncRun.Changes
import app.calcpace.health.HcSyncRun.Lookup
import app.calcpace.health.HcSyncRun.Outcome
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HcSyncRunTest {
    private val token = "a".repeat(43)
    private val now = Instant.parse("2026-10-04T12:00:00Z")
    private val state = HcState(FakePrefs()).also { it.link(token) }
    private val source = FakeSource()
    private val uploader = FakeUploader()

    private fun run(state: HcState = this.state) = runBlocking { HcSyncRun(source, state, uploader, token) { now }.run() }

    private fun run(id: String, type: String? = "running", distanceM: Double? = 5000.0, hoursAgo: Long = 3) =
        SessionFacts(
            id = id, type = type, start = now.minus(Duration.ofHours(hoursAgo)),
            end = now.minus(Duration.ofHours(hoursAgo)).plusSeconds(1800), zoneOffsetSeconds = null, title = null,
            origin = "com.garmin.android.apps.connectmobile", device = null, distanceM = distanceM,
            pauses = emptyList(), hrAvg = null, hrMax = null, elevationGainM = null,
        )

    private fun changesFrom(changes: String, pending: Map<String, Long> = emptyMap()) =
        state.advance(token, changes, pending)

    private class FakeSource : HcSyncRun.Source {
        val calls = mutableListOf<String>()
        var token = "changes-0"
        var window = listOf<SessionFacts>()
        val pages = mutableMapOf<String, Changes>()
        val refused = mutableMapOf<String, Exception>()
        val lookups = mutableMapOf<String, Lookup>()
        var duringRead: () -> Unit = {}

        override suspend fun changesToken(): String = token.also { calls += "token" }

        override suspend fun sessionsSince(since: Instant): List<SessionFacts> {
            calls += "read since $since"
            duringRead()
            return window
        }

        override suspend fun changes(token: String): Changes {
            calls += "changes $token"
            refused[token]?.let { throw it }
            return pages.getValue(token)
        }

        override suspend fun session(id: String): Lookup = lookups[id].also { calls += "session $id" } ?: Lookup.Gone
    }

    private class FakeUploader : HcSyncRun.Uploader {
        val sent = mutableListOf<Upload>()
        val answers = ArrayDeque<Int>()

        override suspend fun send(upload: Upload): Int {
            sent += upload
            return answers.removeFirstOrNull() ?: 200
        }
    }

    @Test
    fun theInitialReadTakesTheChangesTokenFirstAndSendsThirtyDaysQuietly() {
        source.window = listOf(run("r1"), run("bike", type = null), run("nodist", distanceM = null))
        assertEquals(Outcome.DONE, run())
        assertEquals(listOf("token", "read since ${now.minus(Duration.ofDays(30))}"), source.calls)
        val upload = uploader.sent.single()
        assertTrue(upload.initial)
        assertEquals(listOf("r1"), upload.sessions.map { it.id })
        assertEquals("changes-0", state.changesToken)
        assertEquals(mapOf("nodist" to now.epochSecond), state.pending)
    }

    @Test
    fun nothingToSendStillMovesOn() {
        assertEquals(Outcome.DONE, run())
        assertEquals(emptyList<Upload>(), uploader.sent)
        assertEquals("changes-0", state.changesToken)
    }

    @Test
    fun aServerErrorKeepsEverythingForTheRetry() {
        source.window = listOf(run("r1"))
        uploader.answers += 503
        assertEquals(Outcome.RETRY, run())
        assertNull(state.changesToken)
    }

    @Test
    fun anyTwoHundredMovesOn() {
        source.window = listOf(run("r1"))
        uploader.answers += 204
        assertEquals(Outcome.DONE, run())
        assertEquals("changes-0", state.changesToken)
    }

    @Test
    fun aRefusedPayloadMovesOnRatherThanLoop() {
        source.window = listOf(run("r1"))
        uploader.answers += 422
        assertEquals(Outcome.DONE, run())
        assertEquals("changes-0", state.changesToken)
    }

    @Test
    fun a401UnlinksAndStoresNothing() {
        source.window = listOf(run("r1"))
        uploader.answers += 401
        assertEquals(Outcome.UNLINK, run())
        assertNull(state.changesToken)
        assertEquals(token, state.linkToken) // the worker forgets it, only while it is still this one
    }

    @Test
    fun aLaterBatchFailingKeepsTheTokenAndStopsSending() {
        source.window = (1..120).map { run("r$it") }
        uploader.answers += listOf(200, 500, 200)
        assertEquals(Outcome.RETRY, run())
        assertEquals(2, uploader.sent.size)
        assertNull(state.changesToken)
    }

    @Test
    fun aRelinkDuringTheSyncIsNotOverwritten() {
        val newer = "b".repeat(43)
        source.window = listOf(run("r1"))
        source.duringRead = { state.link(newer) }
        assertEquals(Outcome.DONE, run())
        assertEquals(newer, state.linkToken)
        assertNull(state.changesToken)
    }

    @Test
    fun changesSendUpsertsAndDeletionsAndFollowEveryPage() {
        changesFrom("c1")
        source.pages["c1"] = Changes(listOf(run("r1"), run("r2")), listOf("old"), "c2", hasMore = true)
        source.pages["c2"] = Changes(listOf(run("r3"), run("bike", type = null)), listOf("r2"), "c3", hasMore = false)
        assertEquals(Outcome.DONE, run())
        val upload = uploader.sent.single()
        assertEquals(false, upload.initial)
        assertEquals(listOf("r1", "r3"), upload.sessions.map { it.id })
        assertEquals(listOf("old", "r2"), upload.deleted)
        assertEquals("c3", state.changesToken)
    }

    @Test
    fun pendingSessionsAreReadAgainById() {
        val seen = now.epochSecond - 3600
        changesFrom("c1", mapOf("ready" to seen, "still" to seen, "gone" to seen, "flaky" to seen, "deleted" to seen,
            "stale" to now.epochSecond - 48 * 3600 - 1))
        source.pages["c1"] = Changes(emptyList(), listOf("deleted"), "c2", hasMore = false)
        source.lookups["ready"] = Lookup.Found(run("ready"))
        source.lookups["still"] = Lookup.Found(run("still", distanceM = null))
        source.lookups["gone"] = Lookup.Gone
        source.lookups["flaky"] = Lookup.Unreadable
        assertEquals(Outcome.DONE, run())
        assertEquals(listOf("ready"), uploader.sent.single().sessions.map { it.id })
        assertEquals(mapOf("still" to seen, "flaky" to seen), state.pending)
        assertTrue("an expired one isn't read", "session stale" !in source.calls)
        assertTrue("a deleted one isn't read", "session deleted" !in source.calls)
    }

    @Test
    fun aPendingSessionThatChangedIsNotReadTwice() {
        changesFrom("c1", mapOf("r1" to now.epochSecond))
        source.pages["c1"] = Changes(listOf(run("r1")), emptyList(), "c2", hasMore = false)
        assertEquals(Outcome.DONE, run())
        assertTrue("session r1" !in source.calls)
        assertEquals(emptyMap<String, Long>(), state.pending)
    }

    @Test
    fun aFailedChangesSyncKeepsThePendingList() {
        changesFrom("c1", mapOf("ready" to now.epochSecond))
        source.pages["c1"] = Changes(emptyList(), emptyList(), "c2", hasMore = false)
        source.lookups["ready"] = Lookup.Found(run("ready"))
        uploader.answers += 500
        assertEquals(Outcome.RETRY, run())
        assertEquals("c1", state.changesToken)
        assertEquals(mapOf("ready" to now.epochSecond), state.pending)
    }

    @Test
    fun anExpiredChangesTokenMeansTheInitialReadAgain() {
        changesFrom("c1")
        source.pages["c1"] = Changes(emptyList(), emptyList(), "c1", hasMore = false, expired = true)
        source.window = listOf(run("r1"))
        assertEquals(Outcome.DONE, run())
        assertTrue(uploader.sent.single().initial)
        assertEquals("changes-0", state.changesToken)
    }

    @Test
    fun aChangesTokenHealthConnectRefusesMeansTheInitialReadAgain() {
        changesFrom("c1")
        source.refused["c1"] = HcSyncRun.TokenRefused(IllegalArgumentException("bad token"))
        assertEquals(Outcome.DONE, run())
        assertTrue(source.calls.contains("token"))
        assertEquals("changes-0", state.changesToken)
    }

    @Test
    fun anyOtherErrorReadingTheChangesIsNotARefusedToken() {
        changesFrom("c1")
        source.refused["c1"] = IllegalArgumentException("from an aggregate")
        assertThrows(IllegalArgumentException::class.java) { run() }
        assertEquals("c1", state.changesToken)
        assertTrue("no 30-day re-read", "token" !in source.calls)
    }

    @Test
    fun anInitialReadAfterARefusedTokenThatFailsStartsFromScratchNextTime() {
        changesFrom("c1")
        source.refused["c1"] = HcSyncRun.TokenRefused(UnsupportedOperationException())
        source.window = listOf(run("r1"))
        uploader.answers += 500
        assertEquals(Outcome.RETRY, run())
        assertNull(state.changesToken)
    }
}
