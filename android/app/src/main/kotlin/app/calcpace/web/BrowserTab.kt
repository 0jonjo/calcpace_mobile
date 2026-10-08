package app.calcpace.web

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.browser.customtabs.CustomTabsService
import androidx.core.net.toUri

/**
 * Opens a calcpace.app page in a real browser, never back in this app. See
 * [BrowserPick] for why the browser is always named and which one it is.
 * The <queries> in the manifest let the browsers be seen from Android 11.
 */
object BrowserTab {
    // Any site but ours: what a browser, and only a browser, opens.
    private val PROBE = "https://example.com/".toUri()

    /** False when there was no browser to open it in. */
    fun open(activity: Activity, uri: Uri, showTitle: Boolean = false): Boolean {
        return try {
            when (val pick = pick(activity)) {
                is BrowserPick.CustomTab -> {
                    val tab = CustomTabsIntent.Builder().setShowTitle(showTitle).build()
                    tab.intent.setPackage(pick.packageName)
                    tab.launchUrl(activity, uri)
                    true
                }
                is BrowserPick.Plain -> {
                    activity.startActivity(
                        Intent(Intent.ACTION_VIEW, uri)
                            .addCategory(Intent.CATEGORY_BROWSABLE)
                            .setPackage(pick.packageName)
                    )
                    true
                }
                BrowserPick.None -> false
            }
        } catch (_: ActivityNotFoundException) {
            false // the browser went away meanwhile
        }
    }

    private fun pick(context: Context): BrowserPick {
        val pm = context.packageManager
        val probe = Intent(Intent.ACTION_VIEW, PROBE).addCategory(Intent.CATEGORY_BROWSABLE)

        val browsers = pm.queryIntentActivities(probe, PackageManager.MATCH_ALL)
            .map { it.activityInfo.packageName }
            .distinct()
        // The chooser itself ("android") when no default was set: not a browser.
        val default = pm.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        val customTabs = browsers.filterTo(mutableSetOf()) {
            val service = Intent(CustomTabsService.ACTION_CUSTOM_TABS_CONNECTION).setPackage(it)
            pm.resolveService(service, 0) != null
        }

        return BrowserPick.choose(default, browsers, customTabs, context.packageName)
    }
}
