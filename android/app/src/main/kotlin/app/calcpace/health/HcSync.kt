package app.calcpace.health

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * When the import runs. A sync whenever the app comes to the foreground
 * (the only road when background reads are not allowed), and every quarter
 * of an hour or so in the background when they are — the shortest period
 * WorkManager runs, and Android may stretch it (Doze, a maker's battery
 * saver). A run read from Health Connect is a read of what changed since the
 * last token, so the short period costs little. Nothing at all without a link
 * token.
 */
object HcSync {
    private const val TAG = "HcSync"
    private const val NOW = "hc-sync-now"
    private const val PERIODIC = "hc-sync"

    /**
     * Off the main thread: asking WorkManager what is queued reads its
     * database, which can throw (SQLiteException on a full disk). A sync not
     * enqueued is caught up by the next one; it must never crash the app.
     */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> Log.w(TAG, "could not enqueue a sync", e) }
    )

    private val NETWORK = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /**
     * One sync as soon as there is a network. One waiting (say, in a retry's
     * backoff) is replaced, so coming back to the app syncs now; one already
     * running is left to finish, unless [replace] (a new link must not wait
     * for a sync of the old one).
     */
    fun now(context: Context, replace: Boolean = false) {
        if (HcStore.open(context).linkToken == null) return
        val workManager = WorkManager.getInstance(context)
        val request = OneTimeWorkRequestBuilder<HcSyncWorker>()
            .setConstraints(NETWORK)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        if (replace) {
            workManager.enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, request)
            return
        }
        // Check, then enqueue: a sync that starts running in between is
        // replaced (cancelled and run again), which the server's idempotent
        // upload makes harmless.
        scope.launch {
            val running = workManager.getWorkInfosForUniqueWorkFlow(NOW).first().any { it.state == WorkInfo.State.RUNNING }
            workManager.enqueueUniqueWork(NOW, if (running) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE, request)
        }
    }

    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<HcSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(NETWORK)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        // UPDATE, not KEEP: a phone scheduled by an older build (hourly) takes
        // the new period; its next run moves to 15 minutes after the last one
        // (often at once). Same spec again (every Account visit) changes nothing.
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun cancelPeriodic(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).apply {
            cancelUniqueWork(NOW)
            cancelUniqueWork(PERIODIC)
        }
    }

    /** The page handed over a new link token: start over with an initial read. */
    fun linked(context: Context, token: String, background: Boolean) {
        if (!HcStore.open(context).link(token)) return
        now(context, replace = true)
        if (background) schedulePeriodic(context) else cancelPeriodic(context)
    }

    /** Background reads allowed or not, as Health Connect says now. */
    fun backgroundChanged(context: Context, background: Boolean) {
        if (HcStore.open(context).linkToken == null) return
        if (background) schedulePeriodic(context) else cancelPeriodic(context)
    }

    /**
     * Forgets the link and stops. With [onlyIfLinkToken], only while that is
     * still the link: a sync of an old link must not undo a newer one.
     */
    fun unlink(context: Context, onlyIfLinkToken: String? = null) {
        if (HcStore.open(context).clear(onlyIfLinkToken)) cancel(context)
    }
}
