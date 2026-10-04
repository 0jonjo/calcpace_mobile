package app.calcpace

import android.app.Application
import app.calcpace.auth.AppAuthRouteDecisionHandler
import app.calcpace.web.WebFragment
import dev.hotwire.core.config.Hotwire
import dev.hotwire.core.logging.HotwireLogLevel
import dev.hotwire.core.turbo.config.PathConfiguration
import dev.hotwire.navigation.config.defaultFragmentDestination
import dev.hotwire.navigation.config.registerFragmentDestinations
import dev.hotwire.navigation.config.registerRouteDecisionHandlers
import dev.hotwire.navigation.routing.AppNavigationRouteDecisionHandler
import dev.hotwire.navigation.routing.BrowserTabRouteDecisionHandler
import dev.hotwire.navigation.routing.SystemNavigationRouteDecisionHandler

class CalcpaceApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        configureHotwire()
    }

    private fun configureHotwire() {
        Hotwire.defaultFragmentDestination = WebFragment::class
        Hotwire.registerFragmentDestinations(WebFragment::class)

        // Order matters: the first handler that matches decides. The sign-in
        // links must be caught before in-app navigation claims every
        // calcpace.app URL.
        Hotwire.registerRouteDecisionHandlers(
            AppAuthRouteDecisionHandler(),
            AppNavigationRouteDecisionHandler(),
            BrowserTabRouteDecisionHandler(),
            SystemNavigationRouteDecisionHandler()
        )

        // The site looks for "Hotwire Native" in the user agent to know it is
        // inside the app; this prefix tells it which one.
        Hotwire.config.applicationUserAgentPrefix = "Calcpace Android;"
        Hotwire.config.webViewDebuggingEnabled = BuildConfig.DEBUG
        Hotwire.config.logger.logLevel =
            if (BuildConfig.DEBUG) HotwireLogLevel.DEBUG else HotwireLogLevel.NONE

        // The bundled copy works offline and on first launch; the site's copy,
        // once fetched, wins and lets routing change without a store release.
        Hotwire.loadPathConfiguration(
            context = this,
            location = PathConfiguration.Location(
                assetFilePath = "json/path-configuration.json",
                remoteFileUrl = "${Calcpace.baseUrl}/configurations/android_v1.json"
            )
        )
    }
}
