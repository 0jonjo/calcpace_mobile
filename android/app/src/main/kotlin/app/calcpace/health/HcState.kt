package app.calcpace.health

/**
 * What the import keeps on this phone: the page's link token, where the
 * Changes API left off, and the sessions waiting for their distance.
 *
 * Every write that moves a sync on names the link token it was made for and
 * is dropped when the link changed meanwhile (the page linked again, or
 * unlinked): a sync of an old link must never overwrite or clear a newer
 * one. The bridge component and the worker write from different threads,
 * so the checks and the writes hold one lock.
 *
 * Kept free of Android types (HcStore puts it on SharedPreferences) so the
 * rules are unit tested on the JVM.
 */
class HcState(private val prefs: Prefs) {

    /** The key-value store underneath; [write] applies all of it at once, null values remove. */
    interface Prefs {
        fun get(key: String): String?
        fun write(clear: Boolean, values: Map<String, String?>)
    }

    /** The page's link token (HealthConnectLink on the server); only a well-formed one counts. */
    val linkToken: String?
        get() = prefs.get(KEY_LINK_TOKEN)?.takeIf { HcPayload.isLinkToken(it) }

    /** Where the Changes API left off; null before the initial read. */
    val changesToken: String?
        get() = prefs.get(KEY_CHANGES_TOKEN)

    /** Sessions waiting for their distance (HcPending). */
    val pending: Map<String, Long>
        get() = HcPending.decode(prefs.get(KEY_PENDING))

    /** A new link: everything the old one knew goes, and the next sync is an initial read. False if malformed. */
    fun link(token: String): Boolean = synchronized(LOCK) {
        if (!HcPayload.isLinkToken(token)) return@synchronized false
        prefs.write(clear = true, values = mapOf(KEY_LINK_TOKEN to token))
        true
    }

    /** Moves the sync on, the changes token and what is still pending, in one write. */
    fun advance(forLinkToken: String, changesToken: String, pending: Map<String, Long>) = synchronized(LOCK) {
        if (linkToken != forLinkToken) return@synchronized
        prefs.write(
            clear = false,
            values = mapOf(KEY_CHANGES_TOKEN to changesToken, KEY_PENDING to HcPending.encode(pending)),
        )
    }

    /** A changes token Health Connect no longer takes: the next read is an initial one. */
    fun forgetChanges(forLinkToken: String) = synchronized(LOCK) {
        if (linkToken == forLinkToken) prefs.write(clear = false, values = mapOf(KEY_CHANGES_TOKEN to null))
    }

    /** Forgets everything; with [onlyIfLinkToken], only while that is still the link. True if it did. */
    fun clear(onlyIfLinkToken: String? = null): Boolean = synchronized(LOCK) {
        if (onlyIfLinkToken != null && linkToken != onlyIfLinkToken) return@synchronized false
        prefs.write(clear = true, values = emptyMap())
        true
    }

    private companion object {
        val LOCK = Any()
        const val KEY_LINK_TOKEN = "link_token"
        const val KEY_CHANGES_TOKEN = "changes_token"
        const val KEY_PENDING = "pending"
    }
}
