package app.calcpace.push

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.edit
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
    private const val KEY_OPTED_IN = "opted_in"
    private const val KEY_BLOCKED = "blocked"
    private const val KEY_HAS_TOKEN = "has_token"

    /**
     * Builds without google-services.json (CI) have empty options, and a
     * ".debug" package isn't the app those options belong to: both run with
     * push off.
     */
    val isConfigured: Boolean = PushStatus.isConfigured(
        BuildConfig.APPLICATION_ID,
        BuildConfig.FIREBASE_PROJECT_ID,
        BuildConfig.FIREBASE_SENDER_ID,
        BuildConfig.FIREBASE_APP_ID,
        BuildConfig.FIREBASE_API_KEY,
    )

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

    /**
     * The status for the bridge. An opted-in phone that has lost permission
     * drops its token here, once (see [PushStatus.shouldDropToken]).
     */
    fun currentStatus(fragment: Fragment): String {
        val context = fragment.requireContext()
        val prefs = prefs(context)
        val granted = Build.VERSION.SDK_INT >= PushStatus.RUNTIME_PERMISSION_SDK && isPermissionGranted(context)
        // The system grants again (say, from settings): nothing is blocked.
        if (granted && prefs.getBoolean(KEY_BLOCKED, false)) prefs.edit { putBoolean(KEY_BLOCKED, false) }

        val optedIn = prefs.getBoolean(KEY_OPTED_IN, false)
        val status = PushStatus.of(
            configured = isConfigured,
            sdkInt = Build.VERSION.SDK_INT,
            optedIn = optedIn,
            granted = granted,
            notificationsEnabled = RunNotifications.areEnabled(context),
            blocked = prefs.getBoolean(KEY_BLOCKED, false),
        )
        if (PushStatus.shouldDropToken(optedIn, status) && prefs.getBoolean(KEY_HAS_TOKEN, false)) {
            dropToken(context)
        }
        return status
    }

    /** The athlete tapped "turn on". */
    fun optIn(context: Context) = prefs(context).edit { putBoolean(KEY_OPTED_IN, true) }

    /** Records what a run of the system prompt says about asking again. */
    fun promptFinished(context: Context, outcome: PermissionPrompt.Outcome) {
        when (outcome) {
            PermissionPrompt.Outcome.BLOCKED -> prefs(context).edit { putBoolean(KEY_BLOCKED, true) }
            PermissionPrompt.Outcome.GRANTED,
            PermissionPrompt.Outcome.CAN_ASK_AGAIN -> prefs(context).edit { putBoolean(KEY_BLOCKED, false) }
            PermissionPrompt.Outcome.DISMISSED -> Unit
        }
    }

    fun isPermissionGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT < PushStatus.RUNTIME_PERMISSION_SDK ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Turns FCM on for good (it keeps the token fresh from now on) and hands
     * over the token, or null if FCM couldn't get one. Calls back on the main
     * thread.
     */
    fun fetchToken(context: Context, callback: (String?) -> Unit) {
        val appContext = context.applicationContext
        val messaging = runCatching { FirebaseMessaging.getInstance() }.getOrNull() ?: return callback(null)
        messaging.isAutoInitEnabled = true
        messaging.token.addOnCompleteListener { task ->
            val token = if (task.isSuccessful) task.result else null
            if (token != null) prefs(appContext).edit { putBoolean(KEY_HAS_TOKEN, true) }
            callback(token)
        }
    }

    // Auto-init goes off first, or FCM would mint a new token on its own.
    private fun dropToken(context: Context) {
        val messaging = runCatching { FirebaseMessaging.getInstance() }.getOrNull() ?: return
        prefs(context).edit { putBoolean(KEY_HAS_TOKEN, false) }
        messaging.isAutoInitEnabled = false
        messaging.deleteToken()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
