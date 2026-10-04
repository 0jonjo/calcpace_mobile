package app.calcpace.health

import android.util.Log
import app.calcpace.BuildConfig
import app.calcpace.Calcpace
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Talks to the site with the link token as a bearer (no cookie, no CSRF):
 * POST /health_connect/sessions and DELETE /health_connect/token. Answers
 * with the HTTP status, or [HcSyncPlan.NO_ANSWER] when the network failed.
 * Never logs the token or what is sent.
 */
class HcUploader(private val linkToken: String) {

    suspend fun send(upload: Upload): Int = call(
        request("/health_connect/sessions").post(HcPayload.encode(upload).toRequestBody(JSON)).build()
    )

    /** Gives the link up: Health Connect's permission was taken away on this phone. */
    suspend fun forget(): Int = call(request("/health_connect/token").delete().build())

    private fun request(path: String): Request.Builder =
        Request.Builder()
            .url(Calcpace.baseUrl.trimEnd('/') + path)
            .header("Authorization", "Bearer $linkToken")
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)

    private suspend fun call(request: Request): Int = withContext(Dispatchers.IO) {
        try {
            CLIENT.newCall(request).execute().use { response ->
                // Debug only: the per-id results ("imported", "duplicate"…), never the request.
                if (BuildConfig.DEBUG) Log.d(TAG, "${request.method} ${request.url.encodedPath} → ${response.code} ${response.peekBody(4096).string()}")
                response.code
            }
        } catch (_: IOException) {
            HcSyncPlan.NO_ANSWER
        }
    }

    private companion object {
        const val TAG = "HcSync"
        const val USER_AGENT = "Calcpace Android/${BuildConfig.VERSION_NAME} (Health Connect)"
        val JSON = "application/json".toMediaType()
        val CLIENT: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()
    }
}
