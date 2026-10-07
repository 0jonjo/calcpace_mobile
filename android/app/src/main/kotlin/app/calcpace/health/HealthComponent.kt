package app.calcpace.health

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
import kotlinx.serialization.json.JsonPrimitive

/**
 * The site's "health" bridge component (bridge/health_connect_controller.js
 * on the home card and the account page), which links this phone's Health
 * Connect to the account:
 *
 * - "connect" `{}` → `{ status, linked, background }`: where things stand.
 *   Never asks anything. `background` is [HcBackground]'s true, false or
 *   null (no background reads on this phone), in every reply that has it.
 * - "enable" `{}` → `{ status, granted, background, grant? }`, after Health
 *   Connect's permission screen (or Play's page, when Health Connect needs
 *   installing or updating). `granted` = exercise and distance allowed and,
 *   when they had been allowed before, the athlete confirmed in the app;
 *   only then comes `grant`, a one-time value for "link" ([HcLinkGate]).
 * - "link" `{ token, grant }` → `{ linked }`: the token the page got from
 *   POST /health_connect/link, with the grant of its "enable"; the app
 *   keeps the token and starts syncing.
 * - "unlink" `{}` → `{ linked: false }`: the server no longer has this
 *   session's link, so the app forgets its token too.
 * - "background" `{}` → `{ status, background }`: asks Health Connect for
 *   background reads alone, when the phone has them and they aren't allowed
 *   yet. No link gate: it hands the page nothing to link with, and leaves
 *   the link, its token and the sync state as they are; only the periodic
 *   sync follows the answer.
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
            "background" -> fragment.guarded(message) { background(fragment, message) }
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
                    "connect" -> reply(message, State(HcStatus.UNAVAILABLE, linked = linked, background = null))
                    "enable" -> reply(message, Enabled(HcStatus.UNAVAILABLE, granted = false, background = null))
                    "link" -> reply(message, Linked(false))
                    "background" -> reply(message, Background(HcStatus.UNAVAILABLE, background = null))
                }
            }
        }
    }

    private suspend fun connect(fragment: WebFragment, message: Message) {
        val context = fragment.requireContext()
        val linked = HcStore.open(context).linkToken != null
        val client = HealthConnect.client(context)
        val background = client?.let { HealthConnect.background(it, it.permissionController.getGrantedPermissions()) }
        // The athlete may have allowed (or stopped) background reads in
        // Health Connect's settings since the link.
        HcSync.backgroundChanged(context, background == true)
        reply(message, State(HealthConnect.status(context), linked, background))
    }

    private suspend fun enable(fragment: WebFragment, message: Message) {
        val context = fragment.requireContext()
        val client = HealthConnect.client(context)
        if (client == null) {
            val status = HealthConnect.status(context)
            if (status == HcStatus.INSTALL_REQUIRED) runCatching { context.startActivity(HealthConnect.installIntent(context)) }
            return reply(message, Enabled(status, granted = false, background = null))
        }
        val toRequest = HealthConnect.toRequest(client)
        val before = client.permissionController.getGrantedPermissions()
        // Read back what Health Connect holds after its screen, whatever the
        // screen reported: the athlete can only have changed it there.
        val after = if (before.containsAll(toRequest)) before else {
            suspendCancellableCoroutine { done -> fragment.requestHealthPermissions(toRequest) { done.resume(Unit) } }
            client.permissionController.getGrantedPermissions()
        }
        val background = HealthConnect.background(client, after)
        // Background reads may have just been allowed (or not) on that
        // screen, whatever comes of the link below: a linked phone keeps its
        // link when the confirmation is cancelled, and its periodic sync must
        // follow what Health Connect holds now.
        HcSync.backgroundChanged(context, background == true)
        val answer = Enabled(HcStatus.AVAILABLE, granted = after.containsAll(HealthConnect.REQUIRED), background = background)
        if (!answer.granted) return reply(message, answer)

        // Allowed before: Health Connect may have shown nothing at all, so the
        // yes has to come from the athlete here (HcLinkGate).
        val confirmed = if (before.containsAll(HealthConnect.REQUIRED)) {
            suspendCancellableCoroutine { done -> fragment.confirmHealthLink { done.resume(it) } }
        } else {
            true
        }
        reply(message, answer.copy(granted = confirmed, grant = if (confirmed) linkGate.open() else null))
    }

    private suspend fun background(fragment: WebFragment, message: Message) {
        val context = fragment.requireContext()
        val client = HealthConnect.client(context)
        val background = when {
            client == null -> null
            !HealthConnect.backgroundAvailable(client) -> null
            else -> {
                val before = client.permissionController.getGrantedPermissions()
                // Only the one permission: the screen lists nothing else, and
                // the rest stays as the athlete left it. Read back afterwards,
                // as in "enable". A second request while the screen is up
                // waits for the first one's (WebFragment).
                val after = if (HealthConnect.BACKGROUND in before) before else {
                    suspendCancellableCoroutine { done ->
                        fragment.requestHealthPermissions(setOf(HealthConnect.BACKGROUND)) { done.resume(Unit) }
                    }
                    client.permissionController.getGrantedPermissions()
                }
                HealthConnect.background(client, after)
            }
        }
        // A no-op without a link token.
        HcSync.backgroundChanged(context, background == true)
        reply(message, Background(if (client == null) HealthConnect.status(context) else HcStatus.AVAILABLE, background))
    }

    private suspend fun link(fragment: WebFragment, message: Message) {
        val context = fragment.requireContext()
        // Only with the grant of this page's last granted "enable", once:
        // any link closes the gate, right or wrong.
        if (!linkGate.consume(HcPayload.linkGrantFrom(message.jsonData))) return reply(message, Linked(false))
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

    private fun reply(message: Message, data: Background) {
        if (fragment != null) replyWith(message.replacing(message.event, data))
    }

    // `background` is held as a JsonPrimitive so that null goes out as JSON
    // null: Hotwire's Json leaves null properties out (explicitNulls =
    // false), and the site tells "not on this phone" (null) from "not
    // allowed" (false) by the value. Built from [HcBackground]'s Boolean?.

    @Serializable
    data class State(val status: String, val linked: Boolean, val background: JsonPrimitive) {
        constructor(status: String, linked: Boolean, background: Boolean?) : this(status, linked, JsonPrimitive(background))
    }

    /** [grant] only with granted = true: "link" must carry it back (HcLinkGate). */
    @Serializable
    data class Enabled(val status: String, val granted: Boolean, val background: JsonPrimitive, val grant: String? = null) {
        constructor(status: String, granted: Boolean, background: Boolean?, grant: String? = null) :
            this(status, granted, JsonPrimitive(background), grant)
    }

    @Serializable
    data class Background(val status: String, val background: JsonPrimitive) {
        constructor(status: String, background: Boolean?) : this(status, JsonPrimitive(background))
    }

    @Serializable
    data class Linked(val linked: Boolean)
}
