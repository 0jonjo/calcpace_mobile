package app.calcpace.web

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.Toolbar
import app.calcpace.R
import dev.hotwire.navigation.destinations.HotwireDestinationDeepLink
import dev.hotwire.navigation.fragments.HotwireWebFragment

/**
 * Every screen of the site. For now the site draws its own header and tab
 * bar, exactly as in the TWA, so the native toolbar is left out instead of
 * stacking a second header on top of it.
 */
@HotwireDestinationDeepLink(uri = "hotwire://fragment/web")
class WebFragment : HotwireWebFragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_web, container, false)

    override fun toolbarForNavigation(): Toolbar? = null
}
