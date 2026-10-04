package app.calcpace.main

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import app.calcpace.Calcpace
import app.calcpace.R
import app.calcpace.auth.AppAuth
import dev.hotwire.navigation.activities.HotwireActivity
import dev.hotwire.navigation.navigator.NavigatorConfiguration

class MainActivity : HotwireActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        keepClearOfSystemBars(findViewById(R.id.root))
    }

    // A cold start from a link or from a finished sign-in begins there instead
    // of at the home page. Lazy because reading a sign-in callback spends it:
    // asked twice, the answer must not change.
    private val startLocation by lazy { locationFor(intent?.data) ?: Calcpace.baseUrl }

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
        locationFor(intent.data)?.let { delegate.currentNavigator?.route(it) }
    }

    /**
     * Where an incoming link should take the WebView, or null to stay put.
     *
     * The provider callbacks (/auth/…) are the exception among site links: if
     * Android hands one to the app instead of leaving it in the browser tab,
     * the OAuth state it must match lives in the tab's cookies, so it goes
     * back there.
     */
    private fun locationFor(uri: Uri?): String? = when {
        uri == null -> null
        AppAuth.isCallback(uri) -> AppAuth.redeemLocation(this, uri)
        Calcpace.isSiteUrl(uri) && uri.path.orEmpty().startsWith("/auth/") -> {
            AppAuth.openInBrowserTab(this, uri)
            null
        }
        Calcpace.isSiteUrl(uri) -> uri.toString()
        else -> null
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
