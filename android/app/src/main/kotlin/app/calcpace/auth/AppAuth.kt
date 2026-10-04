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
 * verifier. A callback does not spend the verifier, since a bogus one (any
 * app can send it) would wipe it before the real one arrives; the site burns
 * every ticket on first use, right or wrong. The verifier goes once a redeem
 * lands somewhere other than the sign-in page ([visitCompleted]), or when it
 * expires.
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

    // A redeem was sent and its outcome is not known yet.
    @Volatile
    private var redeemInFlight = false

    private val SIGN_IN_PAGE = Regex("^(?:/[A-Za-z]{2}(?:-[A-Za-z]{2})?)?/session(?:/new)?/?$")

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
     * [routed] is true when it will go through the router (a running app),
     * false for a cold start's first location, which never does.
     */
    fun redeemLocation(context: Context, ticket: String, routed: Boolean): String? {
        val verifier = freshVerifier(context) ?: return null

        return Calcpace.baseUrl.toUri().buildUpon()
            .path("/app_auth/redeem")
            .appendQueryParameter("ticket", ticket)
            .appendQueryParameter("verifier", verifier)
            .build()
            .toString()
            .also {
                expectedRedeem = if (routed) it else null
                redeemInFlight = true
            }
    }

    /**
     * Every finished visit passes here. The first one after a redeem tells
     * how it went: the site sends a failed redeem to the sign-in page and a
     * good one anywhere else. Only a good one spends the verifier.
     */
    fun visitCompleted(context: Context, location: String) {
        if (!redeemInFlight) return
        val path = location.toUri().path.orEmpty()
        if (path.contains("app_auth")) return

        redeemInFlight = false
        if (!SIGN_IN_PAGE.matches(path)) context.prefs().edit(commit = true) { clear() }
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
