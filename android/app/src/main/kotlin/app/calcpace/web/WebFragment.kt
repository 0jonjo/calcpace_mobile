package app.calcpace.web

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.Toolbar
import app.calcpace.R
import app.calcpace.auth.AppAuth
import app.calcpace.push.PendingCallbacks
import app.calcpace.push.PermissionPrompt
import app.calcpace.push.Push
import app.calcpace.push.PushStatus
import dev.hotwire.core.turbo.errors.VisitError
import dev.hotwire.core.turbo.webview.HotwireWebView
import dev.hotwire.navigation.destinations.HotwireDestinationDeepLink
import dev.hotwire.navigation.fragments.HotwireWebFragment

/**
 * Every screen of the site. For now the site draws its own header and tab
 * bar, exactly as in the TWA, so the native toolbar is left out instead of
 * stacking a second header on top of it.
 */
@HotwireDestinationDeepLink(uri = "hotwire://fragment/web")
class WebFragment : HotwireWebFragment() {
    private var webView: HotwireWebView? = null

    /** The page the WebView shows right now, as far as the WebView knows. */
    val currentUrl: String? get() = webView?.url

    private val notificationCallbacks = PendingCallbacks<Boolean>()
    private var rationaleBeforePrompt = false
    private var promptStartedAt = 0L

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // Only launched on Android 13+, where the permission exists.
            val context = context
            if (context != null && Build.VERSION.SDK_INT >= PushStatus.RUNTIME_PERMISSION_SDK) {
                val outcome = PermissionPrompt.outcome(
                    granted = granted,
                    rationaleBefore = rationaleBeforePrompt,
                    rationaleAfter = shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS),
                    elapsedMs = SystemClock.elapsedRealtime() - promptStartedAt,
                )
                Push.promptFinished(context, outcome)
            }
            notificationCallbacks.resolve(granted)
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_web, container, false)

    override fun toolbarForNavigation(): Toolbar? = null

    override fun onWebViewAttached(webView: HotwireWebView) {
        super.onWebViewAttached(webView)
        this.webView = webView
    }

    override fun onWebViewDetached(webView: HotwireWebView) {
        super.onWebViewDetached(webView)
        if (this.webView === webView) this.webView = null
    }

    override fun onVisitCompleted(location: String, completedOffline: Boolean) {
        super.onVisitCompleted(location, completedOffline)
        AppAuth.visitCompleted(requireContext(), location)
    }

    override fun onVisitErrorReceived(location: String, error: VisitError) {
        super.onVisitErrorReceived(location, error)
        AppAuth.visitFailed()
    }

    /**
     * Shows the system's notification prompt for the push bridge component.
     * Answers at once where there is nothing to ask: Android 12 and older, or
     * a permission granted before. A second call while the prompt is up waits
     * for the same answer instead of launching again.
     */
    fun requestNotificationPermission(callback: (Boolean) -> Unit) {
        if (Push.isPermissionGranted(requireContext())) return callback(true)
        if (!notificationCallbacks.enqueue(callback)) return
        rationaleBeforePrompt = shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
        promptStartedAt = SystemClock.elapsedRealtime()
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
