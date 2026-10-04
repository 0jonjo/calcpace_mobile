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
    val linkToken: String?
        get() = prefs.getString(KEY_LINK_TOKEN, null)?.takeIf { HcPayload.isLinkToken(it) }

    /** Where the Changes API left off; null before the initial read. */
    val changesToken: String?
        get() = prefs.getString(KEY_CHANGES_TOKEN, null)

    /** Sessions waiting for their distance (HcPending). */
    val pending: Map<String, Long>
        get() = HcPending.decode(prefs.getString(KEY_PENDING, null))

    /**
     * Moves the sync on in one write, the changes token and what is still
     * pending, unless the link changed while the sync ran (the page linked
     * again, or unlinked): a new link starts over with its own initial read.
     */
    fun advance(forLinkToken: String, changesToken: String, pending: Map<String, Long>) = synchronized(LOCK) {
        if (linkToken != forLinkToken) return@synchronized
        prefs.edit {
            putString(KEY_CHANGES_TOKEN, changesToken)
            putString(KEY_PENDING, HcPending.encode(pending))
        }
    }

    /** A changes token Health Connect no longer knows: the next sync is an initial read. */
    fun forgetChanges(forLinkToken: String) = synchronized(LOCK) {
        if (linkToken == forLinkToken) prefs.edit { remove(KEY_CHANGES_TOKEN) }
    }

    /** A new link: everything the old one knew goes, and the next sync is an initial read. */
    fun link(token: String) = synchronized(LOCK) {
        prefs.edit {
            clear()
            putString(KEY_LINK_TOKEN, token.takeIf { HcPayload.isLinkToken(it) })
        }
    }

    /** Forgets everything; with [onlyIfLinkToken], only while that is still the link. True if it did. */
    fun clear(onlyIfLinkToken: String? = null): Boolean = synchronized(LOCK) {
        if (onlyIfLinkToken != null && linkToken != onlyIfLinkToken) return@synchronized false
        prefs.edit { clear() }
        true
    }

    private companion object {
        /** The worker and the bridge component write from different threads. */
        val LOCK = Any()
        const val PREFS = "health_connect"
        const val KEY_LINK_TOKEN = "link_token"
        const val KEY_CHANGES_TOKEN = "changes_token"
        const val KEY_PENDING = "pending"
    }
}
