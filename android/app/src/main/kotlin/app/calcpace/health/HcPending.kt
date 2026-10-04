package app.calcpace.health

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Sessions seen before the app that recorded them wrote their distance
 * (HcPayload.Outcome.NoDistanceYet), by id, with the epoch second they were
 * first seen. The Changes API only watches the sessions themselves, so a
 * distance written later would never come back on its own: these are read
 * again by id on every sync, and given up on after [MAX_AGE_SECONDS].
 *
 * Pure functions over an immutable map, so they are unit tested on the JVM.
 */
object HcPending {
    const val MAX_AGE_SECONDS = 48L * 3600
    const val MAX_ENTRIES = 100

    private val serializer = MapSerializer(String.serializer(), Long.serializer())
    private val json = Json { ignoreUnknownKeys = true }

    /** Adds [ids] seen at [now]; one already waiting keeps its first sighting. Keeps the newest [MAX_ENTRIES]. */
    fun add(pending: Map<String, Long>, ids: Collection<String>, now: Long): Map<String, Long> {
        val merged = pending + ids.filterNot { it in pending }.associateWith { now }
        if (merged.size <= MAX_ENTRIES) return merged
        return merged.entries.sortedByDescending { it.value }.take(MAX_ENTRIES).associate { it.key to it.value }
    }

    fun remove(pending: Map<String, Long>, ids: Collection<String>): Map<String, Long> = pending - ids.toSet()

    /** Drops what has waited longer than [maxAgeSeconds]. */
    fun expire(pending: Map<String, Long>, now: Long, maxAgeSeconds: Long = MAX_AGE_SECONDS): Map<String, Long> =
        pending.filterValues { now - it <= maxAgeSeconds }

    fun encode(pending: Map<String, Long>): String = json.encodeToString(serializer, pending)

    /** Anything unreadable reads as nothing pending. */
    fun decode(text: String?): Map<String, Long> =
        if (text.isNullOrEmpty()) emptyMap() else runCatching { json.decodeFromString(serializer, text) }.getOrDefault(emptyMap())
}
