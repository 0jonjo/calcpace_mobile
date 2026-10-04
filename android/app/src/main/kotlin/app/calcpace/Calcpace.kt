package app.calcpace

import android.net.Uri
import androidx.core.net.toUri

object Calcpace {
    val baseUrl: String = BuildConfig.BASE_URL
    val host: String? = baseUrl.toUri().host

    fun isSiteUrl(uri: Uri): Boolean =
        uri.host == host && (uri.scheme == "https" || uri.scheme == "http")
}
