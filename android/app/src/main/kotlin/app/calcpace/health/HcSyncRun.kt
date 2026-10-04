package app.calcpace.health

import app.calcpace.health.HcSyncPlan.After
import java.time.Duration
import java.time.Instant

/**
 * One sync, with Health Connect, the server and the phone's storage behind
 * interfaces so the whole run is unit tested on the JVM (HcSyncWorker plugs
 * in the real ones).
 *
 * - No changes token: the initial read of the last [INITIAL_DAYS] days, sent
 *   with `initial: true` so nothing buzzes. The changes token is taken
 *   before reading, so nothing written during the read falls in a gap.
 * - A changes token: what was added, changed or deleted since, plus the
 *   sessions still waiting for their distance (HcPending). A token Health
 *   Connect no longer takes (expired, or refused after Health Connect was
 *   wiped or reinstalled) means an initial read again; the server is
 *   idempotent for whatever comes twice.
 * - The changes token and the pending list only move on once every request
 *   was answered ([HcSyncPlan.after]); otherwise the same goes again.
 * - Every write names [linkToken]: a run of an old link changes nothing of a
 *   newer one ([HcState]).
 */
class HcSyncRun(
    private val source: Source,
    private val state: HcState,
    private val uploader: Uploader,
    private val linkToken: String,
    private val clock: () -> Instant = Instant::now,
) {
    /** Health Connect, as far as a sync needs it. */
    interface Source {
        suspend fun changesToken(): String
        suspend fun sessionsSince(since: Instant): List<SessionFacts>
        /** One page of changes; [TokenRefused] when Health Connect won't take [token] at all. */
        suspend fun changes(token: String): Changes
        suspend fun session(id: String): Lookup
    }

    data class Changes(
        val upserts: List<SessionFacts>,
        val deleted: List<String>,
        val next: String,
        val hasMore: Boolean,
        val expired: Boolean = false,
    )

    sealed interface Lookup {
        data class Found(val facts: SessionFacts) : Lookup
        /** Deleted, or otherwise known not to exist. */
        data object Gone : Lookup
        /** Couldn't tell this time: it stays pending. */
        data object Unreadable : Lookup
    }

    /**
     * Health Connect refused the changes token itself (IllegalArgumentException
     * or UnsupportedOperationException from getChanges, e.g. after a wipe or a
     * reinstall). Thrown only for that call: an error while reading what
     * changed is no reason to start over.
     */
    class TokenRefused(cause: Throwable) : Exception(cause)

    fun interface Uploader {
        /** The HTTP status, or [HcSyncPlan.NO_ANSWER]. */
        suspend fun send(upload: Upload): Int
    }

    enum class Outcome {
        DONE,
        RETRY,
        /** The server no longer knows the link: forget it and stop. */
        UNLINK,
    }

    suspend fun run(): Outcome = when (val step = HcSyncPlan.next(state.changesToken)) {
        HcSyncPlan.Step.Initial -> initial()
        is HcSyncPlan.Step.Changes -> changes(step.token)
    }

    private suspend fun initial(): Outcome {
        val next = source.changesToken()
        val now = clock()
        val judged = judge(source.sessionsSince(now.minus(Duration.ofDays(INITIAL_DAYS))))
        val pending = HcPending.add(emptyMap(), judged.waiting, now.epochSecond)
        return send(HcPayload.batches(judged.ready, emptyList(), initial = true), next, pending)
    }

    private suspend fun changes(from: String): Outcome {
        val upserts = LinkedHashMap<String, SessionFacts>()
        val deleted = linkedSetOf<String>()
        var next = from
        do {
            val page = try {
                source.changes(next)
            } catch (_: TokenRefused) {
                null
            }
            if (page == null || page.expired) {
                state.forgetChanges(linkToken)
                return initial()
            }
            page.upserts.forEach { upserts[it.id] = it }
            deleted += page.deleted
            next = page.next
        } while (page.hasMore)
        deleted.forEach { upserts.remove(it) }

        val now = clock().epochSecond
        var pending = HcPending.remove(HcPending.expire(state.pending, now), deleted)
        val gone = mutableListOf<String>()
        for (id in pending.keys) {
            if (id in upserts) continue
            when (val lookup = source.session(id)) {
                is Lookup.Found -> upserts[id] = lookup.facts
                Lookup.Gone -> gone += id
                Lookup.Unreadable -> Unit
            }
        }
        val judged = judge(upserts.values)
        pending = HcPending.add(HcPending.remove(pending, gone + judged.done), judged.waiting, now)
        // Deletions all go: Health Connect doesn't say what a deleted record
        // was, and the server ignores ids it doesn't know.
        return send(HcPayload.batches(judged.ready, deleted.toList(), initial = false), next, pending)
    }

    private class Judged(val ready: List<WireSession>, val waiting: List<String>, val done: List<String>)

    /** Runs to send, sessions still waiting for a distance, and the ids settled either way. */
    private fun judge(sessions: Collection<SessionFacts>): Judged {
        val ready = mutableListOf<WireSession>()
        val waiting = mutableListOf<String>()
        val done = mutableListOf<String>()
        for (facts in sessions) {
            when (val outcome = HcPayload.wire(facts)) {
                is HcPayload.Outcome.Ready -> {
                    ready += outcome.session
                    done += facts.id
                }
                HcPayload.Outcome.NoDistanceYet -> waiting += facts.id
                HcPayload.Outcome.NotARun -> done += facts.id // never sent: not ours to tell
            }
        }
        return Judged(ready, waiting, done)
    }

    private suspend fun send(uploads: List<Upload>, next: String, pending: Map<String, Long>): Outcome {
        val afters = mutableListOf<After>()
        for (upload in uploads) {
            afters += HcSyncPlan.after(uploader.send(upload))
            if (afters.last() != After.ADVANCE) break
        }
        return when (HcSyncPlan.overall(afters)) {
            After.ADVANCE -> Outcome.DONE.also { state.advance(linkToken, next, pending) }
            After.RETRY -> Outcome.RETRY
            After.UNLINK -> Outcome.UNLINK
        }
    }

    companion object {
        const val INITIAL_DAYS = 30L
    }
}
