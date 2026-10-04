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
 * 3. After the provider, the site sends the tab to
 *    https://calcpace.app/app_auth/callback?ticket=…, a verified App Link
 *    that only this signed app receives.
 * 4. [redeemLocation] points the WebView at /app_auth/redeem with the
 *    ticket and the verifier, which signs the WebView in.
 *
 * The ticket is bound to the challenge; only this app ever held the
 * verifier. The verifier is not spent on use: a bogus callback (any app can
 * send one) would otherwise wipe it before the real one arrives. The site
 * burns each ticket on first use, right or wrong, so keeping the verifier
 * until it expires gives nothing away.
 */
object AppAuth {
    private const val PREFS = "app_auth"
    private const val KEY_VERIFIER = "verifier"
    private const val KEY_STARTED_AT = "started_at"

    // As long as the site keeps the challenge bound to the provider's state
    // (a Strava sign-up can sit on the finish screen for a while).
    private const val TTL_MS = 30 * 60 * 1000L

    val providers = setOf("google", "strava")

    // The one redeem URL the WebView may load, set just before routing to it.
    // A redeem link arriving any other way is refused by the route handler.
    @Volatile
    private var expectedRedeem: String? = null

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

    /**
     * The WebView location that completes the sign-in, or null when no
     * sign-in of ours is waiting (a stale link, or one from someone else).
     */
    fun redeemLocation(context: Context, ticket: String): String? {
        val verifier = freshVerifier(context) ?: return null

        return Calcpace.baseUrl.toUri().buildUpon()
            .path("/app_auth/redeem")
            .appendQueryParameter("ticket", ticket)
            .appendQueryParameter("verifier", verifier)
            .build()
            .toString()
            .also { expectedRedeem = it }
    }

    /** True once for the URL [redeemLocation] just built. */
    fun takeExpectedRedeem(location: String): Boolean {
        val expected = expectedRedeem
        if (expected == null || expected != location) return false
        expectedRedeem = null
        return true
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

    private fun freshVerifier(context: Context): String? {
        val prefs = context.prefs()
        val startedAt = prefs.getLong(KEY_STARTED_AT, 0L)
        val fresh = System.currentTimeMillis() - startedAt in 0..TTL_MS
        if (!fresh) {
            prefs.edit(commit = true) { clear() }
            return null
        }
        return prefs.getString(KEY_VERIFIER, null)
    }

    private fun Context.prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
