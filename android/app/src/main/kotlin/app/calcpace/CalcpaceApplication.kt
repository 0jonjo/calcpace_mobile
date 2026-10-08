package app.calcpace

import android.app.Application
import app.calcpace.auth.AppAuthRouteDecisionHandler
import app.calcpace.health.HealthComponent
import app.calcpace.push.Push
import app.calcpace.push.PushComponent
import app.calcpace.share.ShareImageComponent
import app.calcpace.web.WebFragment
import dev.hotwire.core.bridge.BridgeComponentFactory
import dev.hotwire.core.bridge.KotlinXJsonConverter
import dev.hotwire.core.config.Hotwire
import dev.hotwire.core.logging.HotwireLogLevel
import dev.hotwire.core.turbo.config.PathConfiguration
import dev.hotwire.navigation.config.defaultFragmentDestination
import dev.hotwire.navigation.config.registerBridgeComponents
import dev.hotwire.navigation.config.registerFragmentDestinations
import dev.hotwire.navigation.config.registerRouteDecisionHandlers
import dev.hotwire.navigation.routing.AppNavigationRouteDecisionHandler
import dev.hotwire.navigation.routing.BrowserTabRouteDecisionHandler
import dev.hotwire.navigation.routing.SystemNavigationRouteDecisionHandler

class CalcpaceApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Push.init(this)
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

        // Native pieces the site can drive. "push" asks for notifications
        // from the home page's card; "health" links Health Connect from the
        // home card and the account page; "share-image" hands a run's share
        // card to the share sheet. The page's user agent lists them,
        // so the site only talks to components this build has.
        Hotwire.config.jsonConverter = KotlinXJsonConverter()
        Hotwire.registerBridgeComponents(
            BridgeComponentFactory("push", ::PushComponent),
            BridgeComponentFactory("health", ::HealthComponent),
            BridgeComponentFactory("share-image", ::ShareImageComponent),
        )

        // The site looks for "Hotwire Native" in the user agent to know it is
        // inside the app; this prefix tells it which one, and which build: the
        // account page shows it, and names the app's sessions with it
        // ("Calcpace Android/2.0.0 (3);").
        Hotwire.config.applicationUserAgentPrefix =
            "Calcpace Android/${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE});"
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
