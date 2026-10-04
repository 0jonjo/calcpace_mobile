package app.calcpace.push

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PushPagesTest {
    private val base = "https://calcpace.app"

    @Test
    fun theHomePageInAnyLocale() {
        listOf("", "/", "/pt-BR", "/pt-BR/", "/en", "/zh-TW/", "/no", "/?from=app", "/pt-BR?x=1", "/#top", "/de#x")
            .forEach { assertTrue(it, PushPages.isHome("$base$it", base)) }
        assertTrue(PushPages.isHome("$base/pt-BR", "$base/"))
    }

    @Test
    fun aLocalServerWorksInDebug() {
        assertTrue(PushPages.isHome("http://10.0.2.2:3001/pt-BR", "http://10.0.2.2:3001"))
        assertFalse(PushPages.isHome("http://10.0.2.2:3000/pt-BR", "http://10.0.2.2:3001"))
    }

    @Test
    fun anyOtherPageIsNot() {
        listOf(
            "/pt-BR/activities", "/activities/1", "/xx", "/pt-br", "/pt-BR//", "//", "/pt-BR/x",
            "/session/new", "/app_auth/redeem", "/%2F", "/./", "/pt-BR/.."
        ).forEach { assertFalse(it, PushPages.isHome("$base$it", base)) }
    }

    @Test
    fun otherSitesSchemesAndLookalikesAreNot() {
        listOf(
            "http://calcpace.app/", "https://calcpace.app.evil.example/", "https://calcpace.app:8443/",
            "https://calcpace.app@evil.example/", "https://evil.example/", "about:blank", "", "null",
            "https://calcpace.appx/", "https://CALCPACE.APP.evil/"
        ).forEach { assertFalse(it, PushPages.isHome(it, base)) }
    }

    @Test
    fun noLocationIsNotHome() {
        assertFalse(PushPages.isHome(null, base))
    }
}
