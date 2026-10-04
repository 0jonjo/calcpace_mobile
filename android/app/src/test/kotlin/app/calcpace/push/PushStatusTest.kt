package app.calcpace.push

import app.calcpace.push.PushStatus.DEFAULT
import app.calcpace.push.PushStatus.DENIED
import app.calcpace.push.PushStatus.GRANTED
import app.calcpace.push.PushStatus.UNAVAILABLE
import org.junit.Assert.assertEquals
import org.junit.Test

class PushStatusTest {
    private fun status(
        configured: Boolean = true,
        sdkInt: Int = 35,
        optedIn: Boolean = false,
        granted: Boolean = false,
        notificationsEnabled: Boolean = false,
        askedBefore: Boolean = false,
        canAskAgain: Boolean = false,
    ) = PushStatus.of(configured, sdkInt, optedIn, granted, notificationsEnabled, askedBefore, canAskAgain)

    @Test
    fun aBuildWithoutFirebaseIsUnavailableWhateverTheSystemSays() {
        assertEquals(UNAVAILABLE, status(configured = false))
        assertEquals(UNAVAILABLE, status(configured = false, optedIn = true, granted = true, notificationsEnabled = true))
        assertEquals(UNAVAILABLE, status(configured = false, sdkInt = 28, optedIn = true, notificationsEnabled = true))
    }

    @Test
    fun android13AndLater() {
        // Never asked: the card may offer it.
        assertEquals(DEFAULT, status())
        // Denied once: the system still shows its prompt.
        assertEquals(DEFAULT, status(askedBefore = true, canAskAgain = true))
        // Denied for good (or dismissed): only settings can change it.
        assertEquals(DENIED, status(askedBefore = true))
        // Allowed and turned on in the app.
        assertEquals(GRANTED, status(optedIn = true, granted = true, notificationsEnabled = true, askedBefore = true))
        // Allowed, but switched off in settings.
        assertEquals(DENIED, status(optedIn = true, granted = true, notificationsEnabled = false, askedBefore = true))
        // Turned on, then revoked in settings.
        assertEquals(DENIED, status(optedIn = true, askedBefore = true))
        assertEquals(DEFAULT, status(optedIn = true, askedBefore = true, canAskAgain = true))
    }

    // Permission granted long ago (or by the system) is no opt-in: no token
    // until the athlete taps "turn on".
    @Test
    fun aGrantWithoutOptInStillWaitsForTheTap() {
        assertEquals(DEFAULT, status(granted = true, notificationsEnabled = true))
        assertEquals(DEFAULT, status(granted = true, notificationsEnabled = true, askedBefore = true))
    }

    @Test
    fun beforeAndroid13ThereIsNoPermissionOnlyTheSettingsSwitch() {
        assertEquals(DEFAULT, status(sdkInt = 32, notificationsEnabled = true))
        assertEquals(DEFAULT, status(sdkInt = 28, notificationsEnabled = true))
        assertEquals(GRANTED, status(sdkInt = 32, optedIn = true, notificationsEnabled = true))
        assertEquals(DENIED, status(sdkInt = 32, optedIn = true, notificationsEnabled = false))
        assertEquals(DENIED, status(sdkInt = 32, notificationsEnabled = false))
        // The permission flags mean nothing there.
        assertEquals(GRANTED, status(sdkInt = 28, optedIn = true, granted = false, notificationsEnabled = true, askedBefore = true))
    }
}
