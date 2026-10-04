package app.calcpace.push

import app.calcpace.Calcpace
import app.calcpace.web.WebFragment
import dev.hotwire.core.bridge.BridgeComponent
import dev.hotwire.core.bridge.BridgeDelegate
import dev.hotwire.core.bridge.Message
import dev.hotwire.navigation.destinations.HotwireDestination
import kotlinx.serialization.Serializable

/**
 * The site's "push" bridge component (bridge/push_controller.js on the home
 * page). Both events are answered with the phone's [PushStatus] and, once
 * the athlete has turned notifications on, the FCM token, which the page
 * registers against its session:
 *
 * - "connect": the page asks where things stand. Never prompts, never
 *   creates a token unless the athlete opted in before.
 * - "enable": the athlete tapped "turn on". Shows the system prompt where
 *   there is one, then answers.
 *
 * Only the site's home page, where the card lives, may drive it
 * ([PushPages]); messages from anywhere else are ignored.
 */
class PushComponent(
    name: String,
    private val bridgeDelegate: BridgeDelegate<HotwireDestination>,
) : BridgeComponent<HotwireDestination>(name, bridgeDelegate) {

    private val fragment: WebFragment?
        get() = (bridgeDelegate.destination.fragment as? WebFragment)?.takeIf { it.isAdded }

    override fun onReceive(message: Message) {
        if (!PushPages.isHome(fragment?.currentUrl, Calcpace.baseUrl)) return
        when (message.event) {
            "connect" -> answer(message)
            "enable" -> enable(message)
        }
    }

    private fun enable(message: Message) {
        val fragment = fragment ?: return
        if (!Push.isConfigured) return answer(message)
        Push.optIn(fragment.requireContext())
        fragment.requestNotificationPermission { answer(message) }
    }

    // The token arrives asynchronously; by then the page may be gone.
    private fun answer(message: Message) {
        val fragment = fragment ?: return
        val status = Push.currentStatus(fragment)
        if (status != PushStatus.GRANTED) return reply(message, Reply(status))
        Push.fetchToken(fragment.requireContext()) { token ->
            if (this.fragment != null) reply(message, Reply(status, token))
        }
    }

    // Same id and event, new data: the page's send() callback gets it.
    // The event is passed explicitly: leaving it to replacing()'s default
    // crashes Kotlin 2.3.0's inliner on Hotwire's R8-built bytecode.
    private fun reply(message: Message, reply: Reply) {
        replyWith(message.replacing(message.event, reply))
    }

    @Serializable
    data class Reply(val status: String, val token: String? = null)
}
