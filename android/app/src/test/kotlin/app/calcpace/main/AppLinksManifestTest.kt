package app.calcpace.main

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element

/**
 * The calcpace.app links the app claims, read from the manifests themselves.
 *
 * Claiming the whole site made Chrome both load an OAuth callback and hand
 * it to the app, which gave it back: the callback ran twice and the sign-in
 * failed. Only the pages a sign-in or an email lands on belong to the app;
 * anything added here opens in the app instead of the browser.
 */
class AppLinksManifestTest {
    private val android = "http://schemas.android.com/apk/res/android"

    private val paths = setOf(
        "path=/app_auth/callback",
        "path=/email_verification",
        "pathPattern=/.*/email_verification",
        "pathPrefix=/passwords/",
        "pathPattern=/.*/passwords/.*",
    )

    @Test
    fun onlyTheSignInCallbackAndTheEmailLinksOpenInTheApp() {
        assertEquals(
            setOf("scheme=https", "host=calcpace.app") + paths,
            viewData("src/main/AndroidManifest.xml"),
        )
    }

    // The debug filter for a local server claims the same paths, so a
    // sign-in against it behaves as in release.
    @Test
    fun debugBuildsClaimTheSamePathsOnTheirServer() {
        assertEquals(
            setOf("scheme=\${debugSiteScheme}", "host=\${debugSiteHost}") + paths,
            viewData("src/debug/AndroidManifest.xml"),
        )
    }

    // The one VIEW filter in [manifest], one entry per <data>, its
    // attributes in name order.
    private fun viewData(manifest: String): Set<String> {
        val document = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(File(manifest))
        val filters = document.getElementsByTagName("intent-filter")
        val views = (0 until filters.length)
            .map { filters.item(it) as Element }
            .filter { filter ->
                val actions = filter.getElementsByTagName("action")
                (0 until actions.length).any {
                    (actions.item(it) as Element).getAttributeNS(android, "name") == "android.intent.action.VIEW"
                }
            }
        assertEquals(1, views.size)

        val data = views.single().getElementsByTagName("data")
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
