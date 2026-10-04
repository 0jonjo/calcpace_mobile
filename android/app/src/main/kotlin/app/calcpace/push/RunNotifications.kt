package app.calcpace.push

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import app.calcpace.R
import app.calcpace.main.MainActivity

/** The "your run is in" notification, drawn by the app itself from a data-only message. */
object RunNotifications {
    private const val CHANNEL = "runs"

    /** The app's notifications, and the runs channel if it exists yet, are on in the phone's settings. */
    fun areEnabled(context: Context): Boolean {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false
        val channel = manager.getNotificationChannelCompat(CHANNEL) ?: return true
        return channel.importance != NotificationManagerCompat.IMPORTANCE_NONE
    }

    fun show(context: Context, notice: Notice) {
        if (Build.VERSION.SDK_INT >= PushStatus.RUNTIME_PERMISSION_SDK &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return

        val manager = NotificationManagerCompat.from(context)
        createChannel(context, manager)

        // One notification per run: the same run arriving twice replaces it.
        val id = notice.url.hashCode()
        // MainActivity takes it from here (IncomingLink, again): the start
        // location on a cold start, onNewIntent otherwise.
        val open = Intent(Intent.ACTION_VIEW, notice.url.toUri(), context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val tap = PendingIntent.getActivity(
            context, id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.calcpace_green))
            .setContentTitle(notice.title)
            .setContentText(notice.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notice.body))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()

        try {
            manager.notify(id, notification)
        } catch (_: SecurityException) {
            // Revoked between the check and now: nothing to show.
        }
    }

    // Idempotent; calling it each time keeps the name in the phone's language.
    private fun createChannel(context: Context, manager: NotificationManagerCompat) {
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(context.getString(R.string.channel_runs_name))
                .setDescription(context.getString(R.string.channel_runs_description))
                .build()
        )
    }
}
