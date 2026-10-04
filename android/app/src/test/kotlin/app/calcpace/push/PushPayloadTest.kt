package app.calcpace.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PushPayloadTest {
    private val base = "https://calcpace.app"
    private val path = "/pt-BR/activities/123"

    private fun data(title: String? = "Sua corrida chegou", body: String? = "10,2 km em 52:30, a 5:09/km.", path: String? = this.path) =
        buildMap {
            title?.let { put("title", it) }
            body?.let { put("body", it) }
            path?.let { put("path", it) }
        }

    @Test
    fun aRunArrivedMessageBecomesANoticeForThatPage() {
        assertEquals(
            Notice("Sua corrida chegou", "10,2 km em 52:30, a 5:09/km.", "$base/pt-BR/activities/123"),
            PushPayload.parse(data(), base)
        )
    }

    @Test
    fun theDefaultLocaleHasNoPrefixAndATrailingSlashOnTheBaseIsFine() {
        assertEquals("$base/activities/7", PushPayload.parse(data(path = "/activities/7"), "$base/")?.url)
    }

    @Test
    fun aLocalServerWorksInDebug() {
        val local = "http://10.0.2.2:3001"
        assertEquals("$local$path", PushPayload.parse(data(), local)?.url)
    }

    // The tap opens whatever the message says, so it may only ever be a page
    // of the site itself.
    @Test
    fun anythingButAPathOfTheSiteIsRefused() {
        listOf(
            null, "", "activities/123", "//evil.example/x", "///evil.example", "https://evil.example",
            "https://calcpace.app/pt-BR", "javascript:alert(1)", "\\\\evil.example", "/\\evil.example",
            "/pt-BR\\..\\x", "/ /evil", "/\tx", "/\nx", "/x\u0000", "/x\u007f"
        ).forEach { assertNull(it.toString(), PushPayload.parse(data(path = it), base)) }
    }

    // Same rules as a link from outside: the sign-in hand-off and the
    // provider steps never open from a notification.
    @Test
    fun pathsIncomingLinkWouldNotOpenAsAPageAreRefused() {
        listOf(
            "/app_auth/redeem?ticket=t3xOHP60nCIs5golfu10UwzzY8aZSDSkcCQ7n4nnUl4&verifier=x",
            "/app_auth/callback?ticket=t3xOHP60nCIs5golfu10UwzzY8aZSDSkcCQ7n4nnUl4",
            "/en/auth/strava/callback?code=1&state=2",
            "/x#%zz"
        ).forEach { assertNull(it, PushPayload.parse(data(path = it), base)) }
    }

    @Test
    fun aNoticeNeedsATitleAndABody() {
        assertNull(PushPayload.parse(data(title = null), base))
        assertNull(PushPayload.parse(data(title = "  "), base))
        assertNull(PushPayload.parse(data(body = null), base))
        assertNull(PushPayload.parse(data(body = ""), base))
    }

    @Test
    fun longTextIsCutAndPaddingTrimmed() {
        val notice = PushPayload.parse(data(title = "  Hi  ", body = "b".repeat(500)), base)!!
        assertEquals("Hi", notice.title)
        assertEquals(200, notice.body.length)
    }

    @Test
    fun anOverlongPathIsRefused() {
        assertNull(PushPayload.parse(data(path = "/" + "a".repeat(2048)), base))
    }

    // An emoji is two UTF-16 chars; cutting between them leaves a broken
    // character in the notification.
    @Test
    fun cuttingNeverSplitsASurrogatePair() {
        val emoji = "\uD83C\uDFC3" // runner
        val notice = PushPayload.parse(data(title = "a".repeat(199) + emoji, body = emoji.repeat(150)), base)!!
        assertEquals("a".repeat(199), notice.title)
        assertEquals(emoji.repeat(100), notice.body)
        assertEquals(200, notice.body.length)
    }
}
