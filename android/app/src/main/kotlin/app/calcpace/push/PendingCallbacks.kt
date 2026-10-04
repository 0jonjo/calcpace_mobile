package app.calcpace.push

/**
 * Callbacks waiting on one in-flight request. A second "enable" while the
 * system prompt is up must not launch it again: Android would answer the
 * second launch at once with an empty "denied" and park the real result.
 * So only the first caller launches, and the one result reaches everybody.
 *
 * Main thread only. Kept free of Android types so it is unit tested on the JVM.
 */
class PendingCallbacks<T> {
    private val waiting = mutableListOf<(T) -> Unit>()

    /** Adds [callback]; true when the caller should launch the request. */
    fun enqueue(callback: (T) -> Unit): Boolean {
        waiting += callback
        return waiting.size == 1
    }

    fun resolve(result: T) {
        val callbacks = waiting.toList()
        waiting.clear()
        callbacks.forEach { it(result) }
    }
}
