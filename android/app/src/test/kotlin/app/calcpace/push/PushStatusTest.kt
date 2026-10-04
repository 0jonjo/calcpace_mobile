package app.calcpace.push

import app.calcpace.push.PushStatus.DEFAULT
import app.calcpace.push.PushStatus.DENIED
import app.calcpace.push.PushStatus.GRANTED
import app.calcpace.push.PushStatus.UNAVAILABLE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PushStatusTest {
    private fun status(
        configured: Boolean = true,
        sdkInt: Int = 35,
        optedIn: Boolean = false,
        granted: Boolean = false,
        notificationsEnabled: Boolean = false,
        blocked: Boolean = false,
    ) = PushStatus.of(configured, sdkInt, optedIn, granted, notificationsEnabled, blocked)

    @Test
    fun aBuildWithoutFirebaseIsUnavailableWhateverTheSystemSays() {
        assertEquals(UNAVAILABLE, status(configured = false))
        assertEquals(UNAVAILABLE, status(configured = false, optedIn = true, granted = true, notificationsEnabled = true))
        assertEquals(UNAVAILABLE, status(configured = false, sdkInt = 28, optedIn = true, notificationsEnabled = true))
        assertEquals(UNAVAILABLE, status(configured = false, blocked = true))
    }

    @Test
    fun android13AndLater() {
        // Never asked, denied once, or the prompt dismissed: the card may offer it.
        assertEquals(DEFAULT, status())
        // Blocked for good: only settings can change it.
        assertEquals(DENIED, status(blocked = true))
        assertEquals(DENIED, status(optedIn = true, blocked = true))
        // Allowed and turned on in the app.
        assertEquals(GRANTED, status(optedIn = true, granted = true, notificationsEnabled = true))
        // Allowed, but switched off in settings (app or runs channel).
        assertEquals(DENIED, status(optedIn = true, granted = true, notificationsEnabled = false))
        assertEquals(DENIED, status(granted = true, notificationsEnabled = false))
        // Turned on, then revoked in settings: the system would ask again.
        assertEquals(DEFAULT, status(optedIn = true))
    }

    // A stale blocked flag never hides a permission the system now grants.
    @Test
    fun aGrantInSettingsWinsOverTheBlockedFlag() {
        assertEquals(GRANTED, status(optedIn = true, granted = true, notificationsEnabled = true, blocked = true))
        assertEquals(DEFAULT, status(granted = true, notificationsEnabled = true, blocked = true))
    }

    // Permission granted long ago (or by the system) is no opt-in: no token
    // until the athlete taps "turn on".
    @Test
    fun aGrantWithoutOptInStillWaitsForTheTap() {
        assertEquals(DEFAULT, status(granted = true, notificationsEnabled = true))
    }

    @Test
    fun beforeAndroid13ThereIsNoPermissionOnlyTheSettingsSwitch() {
        assertEquals(DEFAULT, status(sdkInt = 32, notificationsEnabled = true))
        assertEquals(DEFAULT, status(sdkInt = 28, notificationsEnabled = true))
        assertEquals(GRANTED, status(sdkInt = 32, optedIn = true, notificationsEnabled = true))
        assertEquals(DENIED, status(sdkInt = 32, optedIn = true, notificationsEnabled = false))
        assertEquals(DENIED, status(sdkInt = 32, notificationsEnabled = false))
        // The permission flags mean nothing there.
        assertEquals(GRANTED, status(sdkInt = 28, optedIn = true, granted = false, notificationsEnabled = true, blocked = true))
    }

    // google-services.json only knows the Play package; a ".debug" build
    // would talk to FCM as an app Firebase doesn't know.
    @Test
    fun firebaseCountsAsConfiguredOnlyForThePlayPackageWithEveryOption() {
        val options = arrayOf("calcpace-31d4f", "123", "1:123:android:abc", "key")
        assertTrue(PushStatus.isConfigured("app.calcpace.twa", *options))
        assertFalse(PushStatus.isConfigured("app.calcpace.twa.debug", *options))
        assertFalse(PushStatus.isConfigured("app.calcpace.twa", "calcpace-31d4f", "123", "1:123:android:abc", ""))
        assertFalse(PushStatus.isConfigured("app.calcpace.twa", "", "", "", ""))
    }

    // Opted in but no longer allowed: the token is dropped so the server's
    // next send comes back UNREGISTERED and the device is pruned.
    @Test
    fun theTokenIsDroppedOnlyWhenAnOptedInPhoneLosesPermission() {
        assertTrue(PushStatus.shouldDropToken(optedIn = true, status = DENIED))
        assertTrue(PushStatus.shouldDropToken(optedIn = true, status = DEFAULT))
        assertFalse(PushStatus.shouldDropToken(optedIn = true, status = GRANTED))
        assertFalse(PushStatus.shouldDropToken(optedIn = true, status = UNAVAILABLE))
        assertFalse(PushStatus.shouldDropToken(optedIn = false, status = DENIED))
        assertFalse(PushStatus.shouldDropToken(optedIn = false, status = DEFAULT))
    }
}
