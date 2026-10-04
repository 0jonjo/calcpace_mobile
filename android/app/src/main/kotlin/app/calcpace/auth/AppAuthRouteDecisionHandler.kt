package app.calcpace.auth

import androidx.core.net.toUri
import app.calcpace.Calcpace
import dev.hotwire.core.turbo.visit.VisitProposal
import dev.hotwire.navigation.activities.HotwireActivity
import dev.hotwire.navigation.navigator.NavigatorConfiguration
import dev.hotwire.navigation.routing.Router

/**
 * Catches the site's in-app sign-in links (/app_auth/google,
 * /app_auth/strava, with or without a locale prefix) and runs the sign-in in a
 * browser tab instead. See [AppAuth].
 */
class AppAuthRouteDecisionHandler : Router.RouteDecisionHandler {
    override val name = "app-auth"

    override fun matches(proposal: VisitProposal, configuration: NavigatorConfiguration): Boolean =
        providerFor(proposal.location) != null

    override fun handle(
        proposal: VisitProposal,
        configuration: NavigatorConfiguration,
        activity: HotwireActivity
    ): Router.Decision {
        providerFor(proposal.location)?.let { AppAuth.begin(activity, it) }
        return Router.Decision.CANCEL
    }

    private fun providerFor(location: String): String? {
        val uri = location.toUri()
        if (!Calcpace.isSiteUrl(uri)) return null

        return PATH.matchEntire(uri.path.orEmpty())?.groupValues?.get(1)
    }

    companion object {
        val PATH = Regex("^(?:/[A-Za-z]{2}(?:-[A-Za-z]{2})?)?/app_auth/(google|strava)/?$")
    }
}
