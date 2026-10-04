package app.calcpace.health

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HcPagesTest {
    private val base = "https://calcpace.app"

    @Test
    fun theHomePageInAnyLocale() {
        listOf("", "/", "/pt-BR", "/pt-BR/", "/en", "/zh-TW/", "/no", "/?from=app", "/pt-BR?x=1", "/#top", "/de#x")
            .forEach { assertTrue(it, HcPages.isAllowed("$base$it", base)) }
        assertTrue(HcPages.isAllowed("$base/pt-BR", "$base/"))
    }

    @Test
    fun theAccountPageInAnyLocale() {
        listOf("/account", "/pt-BR/account", "/account/", "/account#health-connect", "/zh-TW/account?x=1", "/en/account/")
            .forEach { assertTrue(it, HcPages.isAllowed("$base$it", base)) }
    }

    @Test
    fun aLocalServerWorksInDebug() {
        assertTrue(HcPages.isAllowed("http://10.0.2.2:3001/pt-BR/account", "http://10.0.2.2:3001"))
        assertFalse(HcPages.isAllowed("http://10.0.2.2:3000/pt-BR", "http://10.0.2.2:3001"))
    }

    @Test
    fun anyOtherPageIsNot() {
        listOf(
            "/pt-BR/activities", "/activities/1", "/xx", "/pt-br", "/pt-BR//", "//", "/pt-BR/x",
            "/session/new", "/app_auth/redeem", "/%2F", "/./", "/pt-BR/..",
            "/accounts", "/account/x", "/pt-BR/account/../activities", "//account", "/account//",
            "/account/pt-BR", "/accountx", "/%61ccount", "/ACCOUNT"
        ).forEach { assertFalse(it, HcPages.isAllowed("$base$it", base)) }
    }

    @Test
    fun otherSitesSchemesAndLookalikesAreNot() {
        listOf(
            "http://calcpace.app/", "https://calcpace.app.evil.example/", "https://calcpace.app:8443/",
            "https://calcpace.app@evil.example/account", "https://evil.example/account", "about:blank", "", "null",
            "https://calcpace.appx/", "https://CALCPACE.APP.evil/", "https://calcpace.app.evil.example/account"
        ).forEach { assertFalse(it, HcPages.isAllowed(it, base)) }
    }

    @Test
    fun noLocationIsNotAllowed() {
        assertFalse(HcPages.isAllowed(null, base))
    }
}
