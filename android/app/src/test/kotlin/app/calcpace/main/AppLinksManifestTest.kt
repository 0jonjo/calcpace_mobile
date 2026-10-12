package app.calcpace.main

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element

/**
 * The calcpace.app links the app claims, read from the manifest itself.
 *
 * Claiming the whole site made Chrome both load an OAuth callback and hand
 * it to the app, which gave it back: the callback ran twice and the sign-in
 * failed. Only the pages a sign-in or an email lands on belong to the app;
 * anything added here opens in the app instead of the browser.
 */
class AppLinksManifestTest {
    private val android = "http://schemas.android.com/apk/res/android"

    @Test
    fun onlyTheSignInCallbackAndTheEmailLinksOpenInTheApp() {
        assertEquals(
            setOf(
                "scheme=https",
                "host=calcpace.app",
                "path=/app_auth/callback",
                "path=/email_verification",
                "pathPattern=/.*/email_verification",
                "pathPrefix=/passwords/",
                "pathPattern=/.*/passwords/.*",
            ),
            appLinkData(),
        )
    }

    // One entry per <data>, its attributes in name order.
    private fun appLinkData(): Set<String> {
        val manifest = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(File("src/main/AndroidManifest.xml"))
        val filters = manifest.getElementsByTagName("intent-filter")
        val appLinks = (0 until filters.length)
            .map { filters.item(it) as Element }
            .filter { it.getAttributeNS(android, "autoVerify") == "true" }
        assertEquals(1, appLinks.size)

        val data = appLinks.single().getElementsByTagName("data")
        return (0 until data.length).map { i ->
            val attributes = (data.item(i) as Element).attributes
            (0 until attributes.length).map { attributes.item(it) }
                .filter { it.namespaceURI == android }
                .map { "${it.localName}=${it.nodeValue}" }
                .sorted()
                .joinToString(" ")
        }.toSet()
    }
}
