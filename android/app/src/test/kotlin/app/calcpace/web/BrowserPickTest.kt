package app.calcpace.web

import app.calcpace.web.BrowserPick.CustomTab
import app.calcpace.web.BrowserPick.None
import app.calcpace.web.BrowserPick.Plain
import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserPickTest {
    private val own = "app.calcpace.twa"
    private val chrome = "com.android.chrome"
    private val samsung = "com.sec.android.app.sbrowser"
    private val firefoxFocus = "org.mozilla.focus"
    private val plainBrowser = "org.example.nocustomtabs"

    private fun choose(default: String?, browsers: List<String>, customTabs: Set<String>) =
        BrowserPick.choose(default, browsers, customTabs, own)

    @Test
    fun theDefaultBrowserWinsWhenItShowsCustomTabs() {
        assertEquals(CustomTab(samsung), choose(samsung, listOf(chrome, samsung), setOf(chrome, samsung)))
    }

    @Test
    fun aDefaultWithoutCustomTabsGivesWayToOneWithThem() {
        assertEquals(CustomTab(chrome), choose(plainBrowser, listOf(plainBrowser, chrome), setOf(chrome)))
    }

    // No default set: Android answers with its chooser ("android").
    @Test
    fun noDefaultTakesTheFirstBrowserWithCustomTabs() {
        assertEquals(CustomTab(chrome), choose("android", listOf(plainBrowser, chrome, samsung), setOf(chrome, samsung)))
        assertEquals(CustomTab(chrome), choose(null, listOf(chrome, samsung), setOf(chrome, samsung)))
    }

    // The bug: an unnamed Custom Tab for a calcpace.app URL came straight
    // back to the app. The app is never the browser, even if it ever showed
    // up as one or as the default.
    @Test
    fun theAppItselfIsNeverPicked() {
        assertEquals(CustomTab(chrome), choose(own, listOf(own, chrome), setOf(own, chrome)))
        assertEquals(Plain(plainBrowser), choose(own, listOf(own, plainBrowser), setOf(own)))
        assertEquals(None, choose(own, listOf(own), setOf(own)))
    }

    @Test
    fun noCustomTabsAnywhereFallsBackToAPlainBrowser() {
        assertEquals(Plain(firefoxFocus), choose(firefoxFocus, listOf(plainBrowser, firefoxFocus), emptySet()))
        assertEquals(Plain(plainBrowser), choose(null, listOf(plainBrowser, firefoxFocus), emptySet()))
    }

    // A Custom Tabs service in an app that opens no web links is no browser.
    @Test
    fun customTabsOutsideTheBrowsersDoNotCount() {
        assertEquals(Plain(plainBrowser), choose(null, listOf(plainBrowser), setOf(chrome)))
    }

    @Test
    fun noBrowserAtAll() {
        assertEquals(None, choose(null, emptyList(), emptySet()))
        assertEquals(None, choose(chrome, emptyList(), setOf(chrome)))
    }
}
