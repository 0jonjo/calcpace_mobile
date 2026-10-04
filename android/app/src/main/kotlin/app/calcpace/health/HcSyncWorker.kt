package app.calcpace.health

import android.content.Context
import android.os.RemoteException
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.calcpace.health.HcSyncPlan.After
import java.io.IOException
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One sync: what Health Connect holds that the server hasn't seen yet goes
 * to POST /health_connect/sessions.
 *
 * - First, the initial read: the last [INITIAL_DAYS] days (all Health
 *   Connect lets an app read without the history permission), sent with
 *   `initial: true` so nothing buzzes. The changes token is asked for
 *   before reading, so nothing written during the read falls in a gap.
 * - Then the Changes API: sessions added, changed or deleted since, plus
 *   the sessions that were still waiting for their distance (HcPending).
 * - The changes token only moves on once every request was answered
 *   ([HcSyncPlan.after]); otherwise the same changes go again next time.
 * - 401: the link is gone (sign-out, disconnected on the site). The app
 *   forgets its token and stops.
 * - Exercise or distance permission revoked in Health Connect: the app
 *   tells the server (DELETE /health_connect/token) and forgets.
 */
class HcSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = LOCK.withLock {
        val context = applicationContext
        val token = HcStore(context).linkToken ?: return@withLock Result.success().also { HcSync.cancel(context) }
        val client = HealthConnect.client(context) ?: return@withLock Result.success()
        try {
            val granted = client.permissionController.getGrantedPermissions()
            if (!granted.containsAll(HealthConnect.REQUIRED)) return@withLock giveUp(token)
            sync(client, HcReader(client, granted), token)
        } catch (_: SecurityException) {
            // Background reads not allowed (any more): the next foreground sync catches up.
            Result.success()
        } catch (_: IllegalStateException) {
            Result.retry() // Health Connect's rate limit: back off
        } catch (_: RemoteException) {
            Result.retry()
        } catch (_: IOException) {
            Result.retry()
        }
    }

    private suspend fun giveUp(token: String): Result {
        if (!HcSyncPlan.forgotten(HcUploader(token).forget())) return Result.retry()
        HcSync.unlink(applicationContext, onlyIfLinkToken = token)
        return Result.success()
    }

    private suspend fun sync(client: HealthConnectClient, reader: HcReader, token: String): Result =
        when (val step = HcSyncPlan.next(HcStore(applicationContext).changesToken)) {
            HcSyncPlan.Step.Initial -> initial(client, reader, token)
            is HcSyncPlan.Step.Changes -> changes(client, reader, token, step.token)
        }

    private suspend fun initial(client: HealthConnectClient, reader: HcReader, token: String): Result {
        val next = client.getChangesToken(ChangesTokenRequest(setOf(ExerciseSessionRecord::class)))
        val now = Instant.now()
        val judged = judge(reader, reader.sessionsSince(now.minus(Duration.ofDays(INITIAL_DAYS))))
        val pending = HcPending.add(emptyMap(), judged.waiting, now.epochSecond)
        return send(token, HcPayload.batches(judged.ready, emptyList(), initial = true), next, pending)
    }

    private suspend fun changes(client: HealthConnectClient, reader: HcReader, token: String, from: String): Result {
        val upserts = LinkedHashMap<String, ExerciseSessionRecord>()
        val deleted = linkedSetOf<String>()
        var next = from
        do {
            val response = client.getChanges(next)
            if (response.changesTokenExpired) {
                // Unused for 30 days: read the window again (the server is idempotent).
                HcStore(applicationContext).forgetChanges(token)
                return initial(client, reader, token)
            }
            response.changes.forEach { change ->
                when (change) {
                    // Only sessions are watched; other record types never come.
                    is UpsertionChange -> (change.record as? ExerciseSessionRecord)?.let { upserts[it.metadata.id] = it }
                    is DeletionChange -> deleted += change.recordId
                }
            }
            next = response.nextChangesToken
        } while (response.hasMore)
        deleted.forEach { upserts.remove(it) }

        val now = Instant.now().epochSecond
        var pending = HcPending.remove(HcPending.expire(HcStore(applicationContext).pending, now), deleted)
        val gone = mutableListOf<String>()
        for (id in pending.keys) {
            if (id in upserts) continue
            val record = reader.session(id)
            if (record == null) gone += id else upserts[id] = record
        }
        val judged = judge(reader, upserts.values)
        pending = HcPending.add(HcPending.remove(pending, gone + judged.done), judged.waiting, now)
        // Deletions all go: Health Connect doesn't say what a deleted record
        // was, and the server ignores ids it doesn't know.
        return send(token, HcPayload.batches(judged.ready, deleted.toList(), initial = false), next, pending)
    }

    private class Judged(val ready: List<WireSession>, val waiting: List<String>, val done: List<String>)

    /** Runs to send, sessions still waiting for a distance, and the ids settled either way. */
    private suspend fun judge(reader: HcReader, records: Collection<ExerciseSessionRecord>): Judged {
        val ready = mutableListOf<WireSession>()
        val waiting = mutableListOf<String>()
        val done = mutableListOf<String>()
        for (record in records) {
            when (val outcome = HcPayload.wire(reader.facts(record))) {
                is HcPayload.Outcome.Ready -> ready += outcome.session.also { done += it.id }
                HcPayload.Outcome.NoDistanceYet -> waiting += record.metadata.id
                HcPayload.Outcome.NotARun -> done += record.metadata.id // never sent: not ours to tell
            }
        }
        return Judged(ready, waiting, done)
    }

    private suspend fun send(token: String, uploads: List<Upload>, next: String, pending: Map<String, Long>): Result {
        val uploader = HcUploader(token)
        val afters = mutableListOf<After>()
        for (upload in uploads) {
            afters += HcSyncPlan.after(uploader.send(upload))
            if (afters.last() != After.ADVANCE) break
        }
        return when (HcSyncPlan.overall(afters)) {
            After.ADVANCE -> Result.success().also { HcStore(applicationContext).advance(token, next, pending) }
            After.RETRY -> Result.retry()
            After.UNLINK -> Result.success().also { HcSync.unlink(applicationContext, onlyIfLinkToken = token) }
        }
    }

    companion object {
        const val INITIAL_DAYS = 30L

        /** The periodic sync and the foreground one never run at once in this process. */
        private val LOCK = Mutex()
    }
}
