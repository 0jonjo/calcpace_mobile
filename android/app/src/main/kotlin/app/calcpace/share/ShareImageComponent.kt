package app.calcpace.share

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import androidx.lifecycle.lifecycleScope
import app.calcpace.Calcpace
import app.calcpace.web.WebFragment
import dev.hotwire.core.bridge.BridgeComponent
import dev.hotwire.core.bridge.BridgeDelegate
import dev.hotwire.core.bridge.Message
import dev.hotwire.navigation.destinations.HotwireDestination
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * The site's "share-image" bridge component
 * (bridge/share_image_controller.js in a run's share dialog). The WebView
 * has no Web Share API, so the page hands over the card it already has:
 *
 * - "share" `{ filename, data }` (data = the PNG in base64, [SharePayload])
 *   → `{ ok: true }` once the share sheet was started, or
 *   `{ ok: false, error: "invalid" }` when the payload doesn't hold up, or
 *   `{ ok: false, error: "failed" }` when writing the file or starting the
 *   sheet didn't work.
 *
 * Only a run's page may drive it ([SharePages]); messages from anywhere else
 * get no answer. One share at a time: a "share" that arrives while another
 * is being prepared (a double tap) is ignored and never answered; the first
 * one's answer stands for both.
 *
 * Decoding and writing happen off the main thread. If the page went away in
 * the meantime, nothing is shown and nothing is answered.
 */
class ShareImageComponent(
    name: String,
    private val bridgeDelegate: BridgeDelegate<HotwireDestination>,
) : BridgeComponent<HotwireDestination>(name, bridgeDelegate) {

    // Main thread only: set here, cleared when the share is done.
    private var preparing = false

    private val fragment: WebFragment?
        get() = (bridgeDelegate.destination.fragment as? WebFragment)?.takeIf { it.isAdded }

    override fun onReceive(message: Message) {
        if (message.event != "share") return
        val fragment = fragment ?: return
        if (!SharePages.isRunPage(fragment.currentUrl, Calcpace.baseUrl) || preparing) return
        preparing = true
        val context = fragment.requireContext().applicationContext
        fragment.lifecycleScope.launch {
            try {
                val prepared = withContext(Dispatchers.IO) { prepare(context, message.jsonData) }
                show(message, prepared)
            } finally {
                preparing = false
            }
        }
    }

    // The decoded bytes live only in here: once written, only the file has
    // them. A card too big for the heap fails like a full disk would.
    private fun prepare(context: Context, jsonData: String): Prepared =
        try {
            SharePayload.parse(jsonData)?.let { Prepared.Ready(ShareImageFiles.save(context, it)) }
                ?: Prepared.Invalid
        } catch (_: Exception) {
            Prepared.Failed
        } catch (_: OutOfMemoryError) {
            Prepared.Failed
        }

    private fun show(message: Message, prepared: Prepared) {
        // Gone, or the WebView moved on to another page: drop it quietly.
        val fragment = fragment ?: return
        if (!SharePages.isRunPage(fragment.currentUrl, Calcpace.baseUrl)) return
        when (prepared) {
            Prepared.Invalid -> reply(message, Failure(ok = false, error = "invalid"))
            Prepared.Failed -> reply(message, Failure(ok = false, error = "failed"))
            is Prepared.Ready -> try {
                fragment.requireActivity().startActivity(ShareImageFiles.chooser(prepared.uri))
                reply(message, Success(ok = true))
            } catch (_: ActivityNotFoundException) {
                reply(message, Failure(ok = false, error = "failed"))
            } catch (_: SecurityException) {
                reply(message, Failure(ok = false, error = "failed"))
            }
        }
    }

    // Same id and event, new data: the page's send() callback gets it. The
    // event is passed explicitly: leaving it to replacing()'s default crashes
    // Kotlin 2.3.0's inliner on Hotwire's R8-built bytecode.
    private fun reply(message: Message, data: Success) {
        replyWith(message.replacing(message.event, data))
    }

    private fun reply(message: Message, data: Failure) {
        replyWith(message.replacing(message.event, data))
    }

    private sealed interface Prepared {
        data class Ready(val uri: Uri) : Prepared
        data object Invalid : Prepared
        data object Failed : Prepared
    }

    // No default values: they would be left out of the JSON.
    @Serializable
    data class Success(val ok: Boolean)

    /** [error]: "invalid" or "failed". */
    @Serializable
    data class Failure(val ok: Boolean, val error: String)
}
