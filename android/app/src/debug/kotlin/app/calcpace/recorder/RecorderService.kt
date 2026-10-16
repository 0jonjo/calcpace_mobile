package app.calcpace.recorder

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The recorder (G0, debug builds only): a foreground service of type
 * location, so the GPS keeps reporting with the screen off and the app in
 * the background. Every event goes to the [RecorderFiles] log before
 * anything else, and the [Track] is rebuilt from that log whenever the
 * service starts again, so a process the system killed loses only the time
 * it was dead.
 *
 * Android restarts a sticky service after a kill, but may refuse a location
 * service started from the background (no screen, no "while in use"): then
 * it stops, and the recording screen offers to resume or finish the run the
 * next time it is opened.
 */
class RecorderService : Service(), LocationListener {
    private lateinit var files: RecorderFiles
    private var track = Track()
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            notifyProgress()
            handler.postDelayed(this, NOTIFICATION_EVERY_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        files = RecorderFiles(this)
        track = Track.of(files.readCurrent())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!goForeground()) {
            // Android refused a location service started without the app on
            // screen (a restart after a kill, screen off). Say so, or the run
            // goes on without GPS until the runner happens to look.
            if (track.startedAt != null && !track.isStopped) notifyStopped()
            stopSelf()
            return START_NOT_STICKY
        }
        val now = System.currentTimeMillis()
        when (intent?.action) {
            ACTION_START -> {
                // A stopped run whose saving was cut short: save it first,
                // then begin. Without this its Stop would make every Start a
                // no-op.
                if (track.isStopped && !save()) {
                    // Still unsaved: show why and leave, rather than sit in
                    // the foreground saying "Recording" with nothing to record.
                    stop()
                    return START_NOT_STICKY
                }
                when {
                    track.startedAt == null -> record(RunEvent.Start(now))
                    track.isPaused -> record(RunEvent.Resume(now))
                }
            }
            ACTION_PAUSE -> if (track.isRunning) record(RunEvent.Pause(now))
            ACTION_RESUME -> if (track.isPaused) record(RunEvent.Resume(now))
            ACTION_STOP -> {
                if (track.startedAt != null && !track.isStopped) record(RunEvent.Stop(now))
                finish()
                return START_NOT_STICKY
            }
            // A restart after a kill, or anything unknown: carry on from the log.
            else -> if (track.startedAt == null || track.isStopped) {
                if (track.isStopped) save()
                stop()
                return START_NOT_STICKY
            }
        }
        follow()
        return START_STICKY
    }

    private fun record(event: RunEvent) {
        files.append(event)
        track.add(event)
        publish()
    }

    /** GPS on while running, off while paused; the wake lock follows. */
    @SuppressLint("MissingPermission", "WakelockTimeout")
    private fun follow() {
        val manager = getSystemService(LocationManager::class.java)
        manager.removeUpdates(this)
        if (track.isRunning && hasLocation()) {
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
            if (wakeLock?.isHeld != true) {
                wakeLock = getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "calcpace:recorder").apply { acquire() }
            }
        } else {
            releaseWakeLock()
        }
        handler.removeCallbacks(tick)
        handler.post(tick)
        publish()
    }

    override fun onLocationChanged(location: Location) {
        if (!track.isRunning) return
        record(
            RunEvent.Fix(
                // The GPS's own time: fixes delivered in a batch keep their
                // spacing, which the jump check and the GPX depend on.
                time = location.time.takeIf { it > 0 } ?: System.currentTimeMillis(),
                lat = location.latitude,
                lon = location.longitude,
                altitude = if (location.hasAltitude()) location.altitude else null,
                accuracy = if (location.hasAccuracy()) location.accuracy else NO_ACCURACY,
            )
        )
    }

    override fun onProviderDisabled(provider: String) {
        Log.w(TAG, "GPS turned off during a run")
    }

    override fun onProviderEnabled(provider: String) {}

    private fun finish() {
        getSystemService(LocationManager::class.java).removeUpdates(this)
        // A second Stop finds nothing to save: keep the "saved" screen.
        if (track.startedAt != null) save()
        stop()
    }

    /**
     * Saves the stopped [track] and starts afresh. On failure the track stays
     * stopped, as it is in the log, so the next Start tries again before
     * beginning a run: a new Start appended after the old one would be
     * ignored when the log is read back.
     */
    private fun save(): Boolean {
        val finished = try {
            files.finish(track)
        } catch (e: Exception) {
            Log.e(TAG, "Could not save the run: $e")
            state.value = Snapshot(track, RecorderFiles.Finished(name = null, download = null), ++version)
            return false
        }
        state.value = Snapshot(track, finished, ++version)
        track = Track()
        return true
    }

    private fun notifyStopped() {
        channel()
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, RecorderActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID + 1,
            NotificationCompat.Builder(this, ALERT_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("Recording stopped")
                .setContentText("The phone stopped the recorder. Tap to resume the run.")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(open)
                .build()
        )
    }

    private fun stop() {
        handler.removeCallbacks(tick)
        releaseWakeLock()
        files.close()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        getSystemService(LocationManager::class.java).removeUpdates(this)
        handler.removeCallbacks(tick)
        releaseWakeLock()
        files.close()
        if (state.value?.finished == null) state.value = null
        super.onDestroy()
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun publish() {
        state.value = Snapshot(track, null, ++version)
    }

    private fun hasLocation() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun goForeground(): Boolean = try {
        channel()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
        )
        true
    } catch (e: RuntimeException) {
        // ForegroundServiceStartNotAllowedException, or a SecurityException
        // for a location service with no "while in use" access.
        Log.w(TAG, "Could not go foreground: $e")
        false
    }

    private fun channel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Run recording", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel(ALERT_CHANNEL, "Recording stopped", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, RecorderActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val now = System.currentTimeMillis()
        val title = if (track.isPaused) "Paused" else "Recording"
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(title)
            .setContentText("${RunFormat.clock(track.movingMillis(now))} · ${RunFormat.km(track.distanceMeters)}")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
    }

    private fun notifyProgress() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
    }

    /**
     * What the screen shows: the live [track] (read on the main thread, where
     * the service writes it), or the run just saved. [version] changes with
     * every event so the flow emits although the track is the same object.
     */
    data class Snapshot(val track: Track, val finished: RecorderFiles.Finished?, val version: Long)

    companion object {
        private const val TAG = "Recorder"
        private const val CHANNEL = "recorder"
        private const val ALERT_CHANNEL = "recorder_alert"
        private const val NOTIFICATION_ID = 4101
        private const val NOTIFICATION_EVERY_MS = 5_000L
        private const val NO_ACCURACY = 999f

        const val ACTION_START = "app.calcpace.recorder.START"
        const val ACTION_PAUSE = "app.calcpace.recorder.PAUSE"
        const val ACTION_RESUME = "app.calcpace.recorder.RESUME"
        const val ACTION_STOP = "app.calcpace.recorder.STOP"

        private val state = MutableStateFlow<Snapshot?>(null)
        private var version = 0L
        val snapshots: StateFlow<Snapshot?> get() = state

        fun send(context: Context, action: String?) {
            ContextCompat.startForegroundService(context, Intent(context, RecorderService::class.java).setAction(action))
        }
    }
}
