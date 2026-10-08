package app.calcpace.main

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import app.calcpace.Calcpace
import app.calcpace.R
import app.calcpace.auth.AppAuth
import app.calcpace.health.HcSync
import app.calcpace.web.BrowserTab
import dev.hotwire.navigation.activities.HotwireActivity
import dev.hotwire.navigation.navigator.Navigator
import dev.hotwire.navigation.navigator.NavigatorConfiguration

class MainActivity : HotwireActivity() {
    companion object {
        // Process-wide: a bounce can land in a new activity.
        private val browserForwards = BrowserForwards()
    }

    private var restored = false
    private var runToRoute: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        restored = savedInstanceState != null
        runToRoute = notificationLaunch?.then
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        keepClearOfSystemBars(findViewById(R.id.root))
    }

    // Health Connect runs arrive whenever the app comes to the foreground,
    // the only way they do when background reads aren't allowed; background
    // reads allowed in the system's settings meanwhile start the periodic
    // sync. Nothing happens on a phone that hasn't linked Health Connect.
    override fun onStart() {
        super.onStart()
        HcSync.now(this)
        HcSync.refreshBackground(this)
    }

    // A cold start from a link or from a finished sign-in begins there instead
    // of at the home page. A start location never passes through the router,
    // so this is the only place a cold-start redeem is let through. Lazy so
    // the answer can't change between the two times Hotwire asks. A cold
    // start from the run notification begins at home instead, and the run
    // is routed on top of it once the navigator is ready (NotificationLaunch).
    // A restored activity, or one reopened from Recents, gets the same
    // launch intent again: its sign-in and browser hand-off are not rerun
    // (IncomingLink.classifyLaunch).
    private val startLocation by lazy {
        notificationLaunch?.start
            ?: locationFor(intent?.data, routed = false, restored = restored, fromHistory = launchedFromHistory)
            ?: Calcpace.baseUrl
    }

    private val launchedFromHistory: Boolean
        get() = (intent?.flags ?: 0) and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0

    private val notificationLaunch by lazy {
        val intent = intent ?: return@lazy null
        NotificationLaunch.plan(
            url = intent.data?.toString(),
            fromNotification = intent.getBooleanExtra(NotificationLaunch.EXTRA, false),
            fromHistory = launchedFromHistory,
            restored = restored,
            baseUrl = Calcpace.baseUrl,
        )
    }

    // Called as the start destination is attached, in the middle of the
    // fragment transaction: the run is routed once that is done. Once per
    // activity: a recreated one (rotation) has its back stack restored.
    override fun onNavigatorReady(navigator: Navigator) {
        super.onNavigatorReady(navigator)
        val run = runToRoute ?: return
        runToRoute = null
        window.decorView.post {
            if (!isFinishing && !isDestroyed) navigator.route(run)
        }
    }

    override fun navigatorConfigurations() = listOf(
        NavigatorConfiguration(
            name = "main",
            startLocation = startLocation,
            navigatorHostId = R.id.main_nav_host
        )
    )

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val navigator = delegate.currentNavigator ?: return
        locationFor(intent.data, routed = true)?.let { navigator.route(it) }
    }

    /**
     * Where an incoming link should take the WebView, or null to stay put.
     * See [IncomingLink] for which links may do what. [restored] and
     * [fromHistory] mark a launch intent read again, whose side effects
     * already ran.
     */
    private fun locationFor(
        uri: Uri?,
        routed: Boolean,
        restored: Boolean = false,
        fromHistory: Boolean = false,
    ): String? {
        if (uri == null) return null

        val url = uri.toString()
        return when (val link = IncomingLink.classifyLaunch(url, Calcpace.baseUrl, restored, fromHistory)) {
            is IncomingLink.SignIn -> AppAuth.redeemLocation(this, link.ticket, routed)
            is IncomingLink.BrowserTab -> {
                // The OAuth state this step must match lives in the tab's
                // cookies, so it goes back there, in a named browser. A
                // browser that gives it straight back gets it only once.
                if (browserForwards.take(url, SystemClock.elapsedRealtime())) {
                    BrowserTab.open(this, uri, showTitle = true)
                }
                null
            }
            is IncomingLink.Web -> link.url
            IncomingLink.Ignore -> null
        }
    }

    // Edge-to-edge is mandatory from Android 15. The site has no notion of
    // the status bar, the gesture bar or the keyboard, so the page is kept
    // inside them.
    private fun keepClearOfSystemBars(root: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }
}
