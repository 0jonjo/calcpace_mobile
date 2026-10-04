package app.calcpace.web

import android.Manifest
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.Toolbar
import app.calcpace.R
import app.calcpace.auth.AppAuth
import app.calcpace.push.Push
import dev.hotwire.core.turbo.errors.VisitError
import dev.hotwire.navigation.destinations.HotwireDestinationDeepLink
import dev.hotwire.navigation.fragments.HotwireWebFragment

/**
 * Every screen of the site. For now the site draws its own header and tab
 * bar, exactly as in the TWA, so the native toolbar is left out instead of
 * stacking a second header on top of it.
 */
@HotwireDestinationDeepLink(uri = "hotwire://fragment/web")
class WebFragment : HotwireWebFragment() {
    private var onNotificationPermission: ((Boolean) -> Unit)? = null

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            onNotificationPermission?.invoke(granted)
            onNotificationPermission = null
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_web, container, false)

    override fun toolbarForNavigation(): Toolbar? = null

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
     * a permission granted before.
     */
    fun requestNotificationPermission(callback: (Boolean) -> Unit) {
        val context = requireContext()
        if (Push.isPermissionGranted(context)) return callback(true)
        onNotificationPermission = callback
        Push.markAsked(context)
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
