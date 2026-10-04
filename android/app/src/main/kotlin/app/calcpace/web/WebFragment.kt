package app.calcpace.web

import android.Manifest
import android.content.ActivityNotFoundException
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import androidx.health.connect.client.PermissionController
import app.calcpace.R
import app.calcpace.auth.AppAuth
import app.calcpace.push.PendingCallbacks
import app.calcpace.push.PermissionPrompt
import app.calcpace.push.Push
import app.calcpace.push.PushStatus
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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

    private val healthCallbacks = PendingCallbacks<Set<String>>()
    private val healthConfirmations = PendingCallbacks<Boolean>()
    private var healthDialog: AlertDialog? = null
    private val healthPermissions =
        registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
            healthCallbacks.resolve(granted)
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

    /**
     * Health Connect's own permission screen, for the health bridge
     * component. A second call while it is up waits for the same answer
     * instead of launching it again.
     */
    fun requestHealthPermissions(permissions: Set<String>, callback: (Set<String>) -> Unit) {
        if (!healthCallbacks.enqueue(callback)) return
        try {
            healthPermissions.launch(permissions)
        } catch (_: ActivityNotFoundException) {
            healthCallbacks.resolve(emptySet()) // Health Connect went away meanwhile
        }
    }

    /**
     * Asks the athlete to confirm linking Health Connect to the signed-in
     * account, for when Health Connect has nothing left to ask (see
     * HcLinkGate). One dialog at a time; every caller gets its answer, once.
     * The dialog lives as long as this fragment's view: going away (or a
     * configuration change) dismisses it and answers no.
     */
    fun confirmHealthLink(callback: (Boolean) -> Unit) {
        if (!healthConfirmations.enqueue(callback)) return
        var answer = false
        healthDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.hc_link_confirm_title)
            .setMessage(R.string.hc_link_confirm_body)
            .setPositiveButton(R.string.hc_link_confirm_ok) { _, _ -> answer = true }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener {
                healthDialog = null
                healthConfirmations.resolve(answer)
            }
            .show()
    }

    override fun onDestroyView() {
        // Answered here and now, not from the dismiss message the dialog
        // would post after the window is gone.
        healthDialog?.let { dialog ->
            healthDialog = null
            dialog.setOnDismissListener(null)
            dialog.dismiss()
            healthConfirmations.resolve(false)
        }
        super.onDestroyView()
    }
}
