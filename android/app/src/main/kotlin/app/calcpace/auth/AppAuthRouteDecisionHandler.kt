package app.calcpace.auth

import androidx.core.net.toUri
import app.calcpace.Calcpace
import dev.hotwire.core.turbo.visit.VisitProposal
import dev.hotwire.navigation.activities.HotwireActivity
import dev.hotwire.navigation.navigator.NavigatorConfiguration
import dev.hotwire.navigation.routing.Router

/**
 * Guards every in-app navigation that touches app_auth, however the path is
 * spelled (see [AppAuthPaths]):
 *
 * - /app_auth/google and /app_auth/strava, the site's in-app sign-in
 *   buttons, run the sign-in in a browser tab instead. See [AppAuth].
 * - /app_auth/redeem loads only when [AppAuth] built that exact URL. A redeem
 *   link anywhere else (a bio, a run name, a page the user was sent to)
 *   would sign the WebView into someone else's account.
 * - Everything else there is refused.
 *
 * It matches on the host alone, as broadly as the in-app navigation handler
 * after it, so nothing it refuses can slip through on another scheme or port.
 */
class AppAuthRouteDecisionHandler : Router.RouteDecisionHandler {
    override val name = "app-auth"

    override fun matches(proposal: VisitProposal, configuration: NavigatorConfiguration): Boolean =
        AppAuthPaths.isGuarded(proposal.location, configuration.startLocation.toUri().host)

    override fun handle(
        proposal: VisitProposal,
        configuration: NavigatorConfiguration,
        activity: HotwireActivity
    ): Router.Decision {
        val location = proposal.location
        if (!Calcpace.isSiteUrl(location.toUri())) return Router.Decision.CANCEL

        AppAuthPaths.providerOf(location)?.let {
            AppAuth.begin(activity, it)
            return Router.Decision.CANCEL
        }

        if (AppAuthPaths.isRedeem(location) && AppAuth.takeExpectedRedeem(location)) {
            return Router.Decision.NAVIGATE
        }

        return Router.Decision.CANCEL
    }
}
