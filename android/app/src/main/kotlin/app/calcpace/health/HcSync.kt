package app.calcpace.health

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * When the import runs. A sync whenever the app comes to the foreground
 * (the only road when background reads are not allowed), and about hourly
 * in the background when they are. Nothing at all without a link token.
 */
object HcSync {
    private const val NOW = "hc-sync-now"
    private const val PERIODIC = "hc-sync"

    private val NETWORK = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /**
     * One sync as soon as there is a network. [replace] cancels one already
     * under way (a new link must not wait for a sync of the old one).
     */
    fun now(context: Context, replace: Boolean = false) {
        if (HcStore.open(context).linkToken == null) return
        val request = OneTimeWorkRequestBuilder<HcSyncWorker>()
            .setConstraints(NETWORK)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            NOW, if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request
        )
    }

    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<HcSyncWorker>(1, TimeUnit.HOURS)
            .setConstraints(NETWORK)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
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
