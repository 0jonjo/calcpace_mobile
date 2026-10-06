package app.calcpace.share

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharePagesTest {
    private val base = "https://calcpace.app"

    @Test
    fun aRunPageInAnyLocale() {
        listOf(
            "/activities/12", "/activities/12/", "/pt-BR/activities/12", "/en/activities/1", "/zh-TW/activities/9",
            "/no/activities/123456789", "/activities/12?share=1", "/pt-BR/activities/12#share", "/activities/0012"
        ).forEach { assertTrue(it, SharePages.isRunPage("$base$it", base)) }
        assertTrue(SharePages.isRunPage("$base/pt-BR/activities/12", "$base/"))
    }

    @Test
    fun aLocalServerWorksInDebug() {
        assertTrue(SharePages.isRunPage("http://10.0.2.2:3001/pt-BR/activities/7", "http://10.0.2.2:3001"))
        assertFalse(SharePages.isRunPage("http://10.0.2.2:3000/pt-BR/activities/7", "http://10.0.2.2:3001"))
    }

    @Test
    fun otherRoutesUnderOrAroundARunAreNot() {
        listOf(
            "/activities/12/edit", "/activities/12/share_card", "/activities/abc", "/activities/", "/activities",
            "/activities/-1", "/activities/1.png", "/activities/12//", "/pt-BR/activities/new", "/", "/pt-BR",
            "/xx/activities/12", "/pt-br/activities/12", "/pt-BR/pt-BR/activities/12", "/account/activities/12",
            "/activities/１２", "/activities/12 ", "/activities/ 12"
        ).forEach { assertFalse(it, SharePages.isRunPage("$base$it", base)) }
    }

    @Test
    fun oddSpellingsFailClosed() {
        listOf(
            "//evil/activities/1", "//activities/1", "/./activities/1", "/x/../activities/1", "/activities/%31",
            "/activities%2F1", "/%61ctivities/1", "/activities/1%2Fedit", "/activities/1/%2e%2e/2", "/activities/1;x",
            "/Activities/1", "/activities\\1", "/activities/1\n"
        ).forEach { assertFalse(it, SharePages.isRunPage("$base$it", base)) }
    }

    @Test
    fun otherSitesSchemesAndLookalikesAreNot() {
        listOf(
            "http://calcpace.app/activities/1", "https://calcpace.app.evil.example/activities/1",
            "https://calcpace.app:8443/activities/1", "https://calcpace.app@evil.example/activities/1",
            "https://evil.example/activities/1", "https://evil.example/?https://calcpace.app/activities/1",
            "https://calcpace.appx/activities/1", "about:blank", "", "null", "javascript:alert(1)//activities/1"
        ).forEach { assertFalse(it, SharePages.isRunPage(it, base)) }
    }

    @Test
    fun noLocationIsNotARunPage() {
        assertFalse(SharePages.isRunPage(null, base))
    }
}
