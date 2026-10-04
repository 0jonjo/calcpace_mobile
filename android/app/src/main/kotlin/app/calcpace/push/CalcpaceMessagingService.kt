package app.calcpace.push

import app.calcpace.Calcpace
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Receives the server's data-only messages, with the app open or not, and
 * turns the valid ones into a notification. Anything [PushPayload] refuses
 * is dropped silently.
 */
class CalcpaceMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        PushPayload.parse(message.data, Calcpace.baseUrl)?.let { RunNotifications.show(this, it) }
    }

    override fun onNewToken(token: String) {
        // Nothing to do here: the app has no session of its own to send it
        // with. The home page's push component asks for the token on every
        // visit and registers the new one against the WebView's session.
    }
}
