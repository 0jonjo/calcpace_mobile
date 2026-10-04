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
import kotlin.coroutines.resume
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
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
 *   installing or updating). `granted` = exercise and distance allowed and,
 *   when they had been allowed before, the athlete confirmed in the app.
 * - "link" `{ token }` → `{ linked }`: the token the page got from
 *   POST /health_connect/link; the app keeps it and starts syncing. Only
 *   right after a granted "enable" on this page ([HcLinkGate]).
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

    private val linkGate = HcLinkGate()

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
        val context = requireContext().applicationContext
        lifecycleScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                val linked = HcStore.open(context).linkToken != null
                when (message.event) {
                    "connect" -> reply(message, State(HcStatus.UNAVAILABLE, linked = linked, background = false))
                    "enable" -> reply(message, Enabled(HcStatus.UNAVAILABLE, granted = false, background = false))
                    "link" -> reply(message, Linked(false))
                }
            }
        }
    }

    private suspend fun connect(fragment: WebFragment, message: Message) {
        val context = fragment.requireContext()
        val linked = HcStore.open(context).linkToken != null
        val client = HealthConnect.client(context)
        val background = client != null &&
            HealthConnect.backgroundGranted(client, client.permissionController.getGrantedPermissions())
        // The athlete may have allowed (or stopped) background reads in
        // Health Connect's settings since the link.
        HcSync.backgroundChanged(context, background)
        reply(message, State(HealthConnect.status(context), linked, background))
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
        // Read back what Health Connect holds after its screen, whatever the
        // screen reported: the athlete can only have changed it there.
        val after = if (before.containsAll(toRequest)) before else {
            suspendCancellableCoroutine { done -> fragment.requestHealthPermissions(toRequest) { done.resume(Unit) } }
            client.permissionController.getGrantedPermissions()
        }
        val answer = enabled(client, after)
        if (!answer.granted) return reply(message, answer)

        // Allowed before: Health Connect may have shown nothing at all, so the
        // yes has to come from the athlete here (HcLinkGate).
        val confirmed = if (before.containsAll(HealthConnect.REQUIRED)) {
            suspendCancellableCoroutine { done -> fragment.confirmHealthLink { done.resume(it) } }
        } else {
            true
        }
        if (confirmed) linkGate.open()
        reply(message, answer.copy(granted = confirmed))
    }

    private fun enabled(client: HealthConnectClient, granted: Set<String>) =
        Enabled(
            status = HcStatus.AVAILABLE,
            granted = granted.containsAll(HealthConnect.REQUIRED),
            background = HealthConnect.backgroundGranted(client, granted),
        )

    private suspend fun link(fragment: WebFragment, message: Message) {
        val context = fragment.requireContext()
        // One link per yes, whatever the token turns out to be.
        if (!linkGate.consume()) return reply(message, Linked(false))
        val token = HcPayload.linkTokenFrom(message.jsonData) ?: return reply(message, Linked(false))
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
