package app.calcpace.health

import android.app.Activity
import android.content.ActivityNotFoundException
import android.os.Bundle
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import app.calcpace.Calcpace
import java.util.Locale

/**
 * What Health Connect opens from its "privacy policy" link (and Android 14's
 * permission usage screen): the site's privacy policy, at its Health Connect
 * section, in the phone's language. The page the Play listing links to, so
 * there is one policy. Nothing is drawn here; it hands over and goes.
 */
class PermissionsRationaleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = HcPrivacy.url(Calcpace.baseUrl, Locale.getDefault().toLanguageTag())
        try {
            CustomTabsIntent.Builder().build().launchUrl(this, url.toUri())
        } catch (_: ActivityNotFoundException) {
            // No browser at all: nothing to open it in.
        }
        finish()
    }
}
