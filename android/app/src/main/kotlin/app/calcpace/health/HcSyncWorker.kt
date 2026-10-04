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
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Runs one [HcSyncRun] against the real Health Connect, server and storage,
 * and turns its outcome into WorkManager's.
 *
 * - No link token: nothing to do, and nothing scheduled any more.
 * - Exercise or distance permission revoked in Health Connect: the app tells
 *   the server (DELETE /health_connect/token) and forgets the link.
 * - 401: the link is gone (sign-out, disconnected on the site); forgotten too.
 */
class HcSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = LOCK.withLock {
        val context = applicationContext
        val state = HcStore.open(context)
        val token = state.linkToken ?: return@withLock Result.success().also { HcSync.cancel(context) }
        val client = HealthConnect.client(context) ?: return@withLock Result.success()
        try {
            val granted = client.permissionController.getGrantedPermissions()
            if (!granted.containsAll(HealthConnect.REQUIRED)) return@withLock giveUp(token)
            val run = HcSyncRun(Source(client, HcReader(client, granted)), state, HcUploader(token)::send, token)
            when (run.run()) {
                HcSyncRun.Outcome.DONE -> Result.success()
                HcSyncRun.Outcome.RETRY -> Result.retry()
                HcSyncRun.Outcome.UNLINK -> Result.success().also { HcSync.unlink(context, onlyIfLinkToken = token) }
            }
        } catch (_: SecurityException) {
            // Background reads not allowed (any more): the next foreground sync catches up.
            Result.success()
        } catch (_: UnsupportedOperationException) {
            // Something this phone's Health Connect can't do: nothing to retry.
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

    /** Health Connect for [HcSyncRun]: records read and turned into [SessionFacts]. */
    private class Source(private val client: HealthConnectClient, private val reader: HcReader) : HcSyncRun.Source {
        override suspend fun changesToken(): String =
            client.getChangesToken(ChangesTokenRequest(setOf(ExerciseSessionRecord::class)))

        override suspend fun sessionsSince(since: Instant): List<SessionFacts> =
            reader.sessionsSince(since).map { reader.facts(it) }

        override suspend fun changes(token: String): HcSyncRun.Changes {
            val response = client.getChanges(token)
            if (response.changesTokenExpired) return HcSyncRun.Changes(emptyList(), emptyList(), token, false, expired = true)
            val upserts = mutableListOf<SessionFacts>()
            val deleted = mutableListOf<String>()
            response.changes.forEach { change ->
                when (change) {
                    // Only sessions are watched; other record types never come.
                    is UpsertionChange -> (change.record as? ExerciseSessionRecord)?.let { upserts += reader.facts(it) }
                    is DeletionChange -> deleted += change.recordId
                }
            }
            return HcSyncRun.Changes(upserts, deleted, response.nextChangesToken, response.hasMore)
        }

        override suspend fun session(id: String): HcSyncRun.Lookup =
            when (val record = reader.session(id)) {
                is HcReader.Read.Found -> HcSyncRun.Lookup.Found(reader.facts(record.record))
                HcReader.Read.Gone -> HcSyncRun.Lookup.Gone
                HcReader.Read.Unreadable -> HcSyncRun.Lookup.Unreadable
            }
    }

    private companion object {
        /** The periodic sync and the foreground one never run at once in this process. */
        val LOCK = Mutex()
    }
}
