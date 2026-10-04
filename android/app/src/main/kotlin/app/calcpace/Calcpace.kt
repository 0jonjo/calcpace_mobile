package app.calcpace

import android.net.Uri
import androidx.core.net.toUri

object Calcpace {
    val baseUrl: String = BuildConfig.BASE_URL
    private val base: Uri = baseUrl.toUri()

    // Scheme, host and port all have to match: a release build must not let
    // an http:// link to calcpace.app into the WebView.
    fun isSiteUrl(uri: Uri): Boolean =
        uri.scheme.equals(base.scheme, ignoreCase = true) &&
            uri.host.equals(base.host, ignoreCase = true) &&
            uri.port == base.port
}
