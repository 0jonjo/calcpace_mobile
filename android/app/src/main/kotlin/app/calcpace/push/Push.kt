package app.calcpace.push

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import app.calcpace.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging

/**
 * Firebase set-up and the push status of this phone. Nothing here runs
 * before the athlete taps "turn on": FCM's auto-init is off in the manifest,
 * so no token exists until [fetchToken].
 */
object Push {
    private const val PREFS = "push"
    private const val KEY_ASKED = "asked"
    private const val KEY_OPTED_IN = "opted_in"

    /** Builds without google-services.json (CI) have empty options. */
    val isConfigured: Boolean = listOf(
        BuildConfig.FIREBASE_PROJECT_ID,
        BuildConfig.FIREBASE_SENDER_ID,
        BuildConfig.FIREBASE_APP_ID,
        BuildConfig.FIREBASE_API_KEY,
    ).all { it.isNotEmpty() }

    /** From Application.onCreate. There is no google-services plugin, so the options are built by hand. */
    fun init(context: Context) {
        if (!isConfigured || FirebaseApp.getApps(context).isNotEmpty()) return
        FirebaseApp.initializeApp(
            context,
            FirebaseOptions.Builder()
                .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
                .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                .setApiKey(BuildConfig.FIREBASE_API_KEY)
                .build()
        )
    }

    fun currentStatus(fragment: Fragment): String {
        val context = fragment.requireContext()
        val runtimePermission = Build.VERSION.SDK_INT >= PushStatus.RUNTIME_PERMISSION_SDK
        return PushStatus.of(
            configured = isConfigured,
            sdkInt = Build.VERSION.SDK_INT,
            optedIn = prefs(context).getBoolean(KEY_OPTED_IN, false),
            granted = runtimePermission && isPermissionGranted(context),
            notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled(),
            askedBefore = prefs(context).getBoolean(KEY_ASKED, false),
            canAskAgain = runtimePermission &&
                fragment.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS),
        )
    }

    /** The athlete tapped "turn on". */
    fun optIn(context: Context) = prefs(context).edit().putBoolean(KEY_OPTED_IN, true).apply()

    /** The system prompt is about to show. */
    fun markAsked(context: Context) = prefs(context).edit().putBoolean(KEY_ASKED, true).apply()

    fun isPermissionGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT < PushStatus.RUNTIME_PERMISSION_SDK ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Turns FCM on for good (it keeps the token fresh from now on) and hands
     * over the token, or null if FCM couldn't get one. Calls back on the main
     * thread.
     */
    fun fetchToken(callback: (String?) -> Unit) {
        val messaging = runCatching { FirebaseMessaging.getInstance() }.getOrNull() ?: return callback(null)
        messaging.isAutoInitEnabled = true
        messaging.token.addOnCompleteListener { task ->
            callback(if (task.isSuccessful) task.result else null)
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
