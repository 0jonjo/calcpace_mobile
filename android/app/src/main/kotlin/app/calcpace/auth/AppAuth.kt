package app.calcpace.auth

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.edit
import androidx.core.net.toUri
import app.calcpace.Calcpace

/**
 * Signing in with Google or Strava from inside the app.
 *
 * Google refuses OAuth inside a WebView, and a browser tab keeps its own
 * cookies, so the sign-in runs in a Custom Tab and is handed back:
 *
 * 1. The site's in-app button links to /app_auth/google (or /strava);
 *    [AppAuthRouteDecisionHandler] catches it and calls [begin].
 * 2. [begin] keeps a fresh verifier and opens
 *    /app_auth/start?provider=…&challenge=… in a Custom Tab.
 * 3. After the provider, the site redirects the tab to
 *    calcpace://auth?ticket=…, which Android delivers to MainActivity.
 * 4. [redeemLocation] spends the verifier once and points the WebView at
 *    /app_auth/redeem, which signs the WebView in.
 *
 * Any app can claim the calcpace:// scheme and read a ticket; none of them
 * holds the verifier, and the site redeems a ticket only with it.
 */
object AppAuth {
    private const val PREFS = "app_auth"
    private const val KEY_VERIFIER = "verifier"
    private const val KEY_STARTED_AT = "started_at"

    // As long as the site keeps the challenge waiting in the tab.
    private const val TTL_MS = 10 * 60 * 1000L

    val providers = setOf("google", "strava")

    fun begin(activity: Activity, provider: String) {
        require(provider in providers)

        val verifier = Pkce.newVerifier()
        // Kept on disk: Android may kill the app while the tab is in front.
        activity.prefs().edit(commit = true) {
            putString(KEY_VERIFIER, verifier)
            putLong(KEY_STARTED_AT, System.currentTimeMillis())
        }

        val start = Calcpace.baseUrl.toUri().buildUpon()
            .path("/app_auth/start")
            .appendQueryParameter("provider", provider)
            .appendQueryParameter("challenge", Pkce.challengeFor(verifier))
            .build()

        openInBrowserTab(activity, start)
    }

    fun isCallback(uri: Uri?): Boolean =
        uri?.scheme == "calcpace" && uri.host == "auth"

    /**
     * The WebView location that completes the sign-in, or null when there is
     * no sign-in of ours waiting (a stale link, a link from someone else, a
     * second delivery of the same one). The verifier is spent either way.
     */
    fun redeemLocation(context: Context, callback: Uri): String? {
        val ticket = callback.getQueryParameter("ticket")?.takeIf { it.isNotBlank() } ?: return null
        val verifier = takeVerifier(context) ?: return null

        return Calcpace.baseUrl.toUri().buildUpon()
            .path("/app_auth/redeem")
            .appendQueryParameter("ticket", ticket)
            .appendQueryParameter("verifier", verifier)
            .build()
            .toString()
    }

    fun openInBrowserTab(activity: Activity, uri: Uri) {
        try {
            CustomTabsIntent.Builder()
                .setShowTitle(true)
                .build()
                .launchUrl(activity, uri)
        } catch (e: ActivityNotFoundException) {
            // No browser at all: nothing to sign in with.
        }
    }

    private fun takeVerifier(context: Context): String? {
        val prefs = context.prefs()
        val verifier = prefs.getString(KEY_VERIFIER, null)
        val startedAt = prefs.getLong(KEY_STARTED_AT, 0L)
        prefs.edit(commit = true) { clear() }

        val fresh = System.currentTimeMillis() - startedAt in 0..TTL_MS
        return verifier.takeIf { fresh }
    }

    private fun Context.prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
