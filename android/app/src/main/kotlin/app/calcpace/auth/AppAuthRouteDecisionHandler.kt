package app.calcpace.auth

import androidx.core.net.toUri
import app.calcpace.Calcpace
import dev.hotwire.core.turbo.visit.VisitProposal
import dev.hotwire.navigation.activities.HotwireActivity
import dev.hotwire.navigation.navigator.NavigatorConfiguration
import dev.hotwire.navigation.routing.Router

/**
 * Guards every in-app navigation under /app_auth (with or without a locale
 * prefix):
 *
 * - /app_auth/google and /app_auth/strava, the site's in-app sign-in
 *   buttons, run the sign-in in a browser tab instead. See [AppAuth].
 * - /app_auth/redeem loads only when [AppAuth] built that exact URL. A redeem
 *   link anywhere else (a bio, a run name, a page the user was sent to)
 *   would sign the WebView into someone else's account.
 * - Anything else there belongs to the browser tab, not the WebView.
 */
class AppAuthRouteDecisionHandler : Router.RouteDecisionHandler {
    override val name = "app-auth"

    override fun matches(proposal: VisitProposal, configuration: NavigatorConfiguration): Boolean {
        val uri = proposal.location.toUri()
        return Calcpace.isSiteUrl(uri) && APP_AUTH.matches(uri.path.orEmpty())
    }

    override fun handle(
        proposal: VisitProposal,
        configuration: NavigatorConfiguration,
        activity: HotwireActivity
    ): Router.Decision {
        val path = proposal.location.toUri().path.orEmpty()

        PROVIDER.matchEntire(path)?.let {
            AppAuth.begin(activity, it.groupValues[1])
            return Router.Decision.CANCEL
        }

        if (REDEEM.matches(path) && AppAuth.takeExpectedRedeem(proposal.location)) {
            return Router.Decision.NAVIGATE
        }

        return Router.Decision.CANCEL
    }

    companion object {
        private const val LOCALE = "(?:/[A-Za-z]{2}(?:-[A-Za-z]{2})?)?"
        val APP_AUTH = Regex("^$LOCALE/app_auth(?:/.*)?$")
        val PROVIDER = Regex("^$LOCALE/app_auth/(google|strava)/?$")
        val REDEEM = Regex("^$LOCALE/app_auth/redeem/?$")
    }
}
