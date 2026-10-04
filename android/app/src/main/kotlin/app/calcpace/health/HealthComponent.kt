package app.calcpace.health

import androidx.health.connect.client.HealthConnectClient
import androidx.lifecycle.lifecycleScope
import app.calcpace.Calcpace
import app.calcpace.web.WebFragment
import dev.hotwire.core.bridge.BridgeComponent
import dev.hotwire.core.bridge.BridgeDelegate
import dev.hotwire.core.bridge.Message
import dev.hotwire.navigation.destinations.HotwireDestination
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/**
 * The site's "health" bridge component (bridge/health_connect_controller.js
 * on the home card and the account page), which links this phone's Health
 * Connect to the account:
 *
 * - "connect" `{}` → `{ status, linked, background }`: where things stand.
 *   Never asks anything.
 * - "enable" `{}` → `{ status, granted, background }`, after Health
 *   Connect's permission screen (or Play's page, when Health Connect needs
 *   installing or updating). `granted` = exercise and distance allowed.
 * - "link" `{ token }` → `{ linked }`: the token the page got from
 *   POST /health_connect/link; the app keeps it and starts syncing.
 * - "unlink" `{}` → `{ linked: false }`: the server no longer has this
 *   session's link, so the app forgets its token too.
 *
 * Only the home and account pages may drive it ([HcPages]); messages from
 * anywhere else are ignored.
 */
class HealthComponent(
    name: String,
    private val bridgeDelegate: BridgeDelegate<HotwireDestination>,
) : BridgeComponent<HotwireDestination>(name, bridgeDelegate) {

    private val fragment: WebFragment?
        get() = (bridgeDelegate.destination.fragment as? WebFragment)?.takeIf { it.isAdded }

    override fun onReceive(message: Message) {
        val fragment = fragment ?: return
        if (!HcPages.isAllowed(fragment.currentUrl, Calcpace.baseUrl)) return
        when (message.event) {
            "connect" -> fragment.guarded(message) { connect(fragment, message) }
            "enable" -> fragment.guarded(message) { enable(fragment, message) }
            "link" -> fragment.guarded(message) { link(fragment, message) }
            "unlink" -> {
                HcSync.unlink(fragment.requireContext())
                reply(message, Linked(false))
            }
        }
    }

    /**
     * Runs [block] for as long as the page's fragment lives. Health Connect
     * can throw (its service restarting, a rate limit): the page then hears
     * it is unavailable instead of the app crashing.
     */
    private fun WebFragment.guarded(message: Message, block: suspend () -> Unit) {
        lifecycleScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                when (message.event) {
                    "connect" -> reply(message, State(HcStatus.UNAVAILABLE, linked = false, background = false))
                    "enable" -> reply(message, Enabled(HcStatus.UNAVAILABLE, granted = false, background = false))
                    "link" -> reply(message, Linked(false))
                }
            }
        }
    }

    private suspend fun connect(fragment: WebFragment, message: Message) {
        val context = fragment.requireContext()
        val client = HealthConnect.client(context)
        val background = client != null &&
            HealthConnect.backgroundGranted(client, client.permissionController.getGrantedPermissions())
        // The athlete may have allowed (or stopped) background reads in
        // Health Connect's settings since the link.
        HcSync.backgroundChanged(context, background)
        reply(message, State(HealthConnect.status(context), HcStore.open(context).linkToken != null, background))
    }

    private suspend fun enable(fragment: WebFragment, message: Message) {
        val context = fragment.requireContext()
        val client = HealthConnect.client(context)
        if (client == null) {
            val status = HealthConnect.status(context)
            if (status == HcStatus.INSTALL_REQUIRED) runCatching { context.startActivity(HealthConnect.installIntent(context)) }
            return reply(message, Enabled(status, granted = false, background = false))
        }
        val toRequest = HealthConnect.toRequest(client)
        val before = client.permissionController.getGrantedPermissions()
        if (before.containsAll(toRequest)) return reply(message, enabled(client, before))

        fragment.requestHealthPermissions(toRequest) {
            // Read back what Health Connect holds now, whatever the screen
            // reported: the athlete can only have changed it there.
            fragment.guarded(message) {
                reply(message, enabled(client, client.permissionController.getGrantedPermissions()))
            }
        }
    }

    private fun enabled(client: HealthConnectClient, granted: Set<String>) =
        Enabled(
            status = HcStatus.AVAILABLE,
            granted = granted.containsAll(HealthConnect.REQUIRED),
            background = HealthConnect.backgroundGranted(client, granted),
        )

    private suspend fun link(fragment: WebFragment, message: Message) {
        val token = HcPayload.linkTokenFrom(message.jsonData) ?: return reply(message, Linked(false))
        val context = fragment.requireContext()
        val client = HealthConnect.client(context)
        val background = client != null &&
            HealthConnect.backgroundGranted(client, client.permissionController.getGrantedPermissions())
        HcSync.linked(context, token, background)
        reply(message, Linked(true))
    }

    // Same id and event, new data: the page's send() callback gets it. The
    // event is passed explicitly: leaving it to replacing()'s default crashes
    // Kotlin 2.3.0's inliner on Hotwire's R8-built bytecode. The answer may
    // come after the page has gone.
    private fun reply(message: Message, data: State) {
        if (fragment != null) replyWith(message.replacing(message.event, data))
    }

    private fun reply(message: Message, data: Enabled) {
        if (fragment != null) replyWith(message.replacing(message.event, data))
    }

    private fun reply(message: Message, data: Linked) {
        if (fragment != null) replyWith(message.replacing(message.event, data))
    }

    @Serializable
    data class State(val status: String, val linked: Boolean, val background: Boolean)

    @Serializable
    data class Enabled(val status: String, val granted: Boolean, val background: Boolean)

    @Serializable
    data class Linked(val linked: Boolean)
}
