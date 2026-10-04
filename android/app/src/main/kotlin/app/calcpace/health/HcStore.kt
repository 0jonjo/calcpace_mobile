package app.calcpace.health

import android.content.Context
import androidx.core.content.edit

/**
 * What the import keeps on this phone, in the "health_connect" shared
 * preferences (left out of backups: the link token belongs to the WebView
 * session on this phone, the changes token to this phone's Health Connect).
 */
class HcStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The page's link token (HealthConnectLink); only a well-formed one is kept. */
    var linkToken: String?
        get() = prefs.getString(KEY_LINK_TOKEN, null)?.takeIf { HcPayload.isLinkToken(it) }
        set(value) = prefs.edit { putString(KEY_LINK_TOKEN, value?.takeIf { HcPayload.isLinkToken(it) }) }

    /** Where the Changes API left off; null before the initial read. */
    var changesToken: String?
        get() = prefs.getString(KEY_CHANGES_TOKEN, null)
        set(value) = prefs.edit { putString(KEY_CHANGES_TOKEN, value) }

    /** Sessions waiting for their distance (HcPending). */
    var pending: Map<String, Long>
        get() = HcPending.decode(prefs.getString(KEY_PENDING, null))
        set(value) = prefs.edit { putString(KEY_PENDING, HcPending.encode(value)) }

    /** Moves the sync on in one write: the changes token and what is still pending. */
    fun advance(changesToken: String, pending: Map<String, Long>) = prefs.edit {
        putString(KEY_CHANGES_TOKEN, changesToken)
        putString(KEY_PENDING, HcPending.encode(pending))
    }

    fun clear() = prefs.edit { clear() }

    private companion object {
        const val PREFS = "health_connect"
        const val KEY_LINK_TOKEN = "link_token"
        const val KEY_CHANGES_TOKEN = "changes_token"
        const val KEY_PENDING = "pending"
    }
}
