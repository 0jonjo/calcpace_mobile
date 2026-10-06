package app.calcpace.share

import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The data of a "share" message, `{ filename, data }`: the run's share card
 * as PNG bytes in standard base64 (no `data:` prefix), and the name the file
 * goes out under. Anything that doesn't hold up is refused as a whole:
 *
 * - filename: only `[A-Za-z0-9._-]`, 1 to 80 characters, ending in ".png",
 *   not starting with "." (no path, nothing hidden);
 * - data: decodes as strict base64 to at most [MAX_BYTES] bytes that start
 *   with the PNG signature. The length is checked before decoding, so an
 *   oversized payload is never decoded.
 *
 * Kept free of Android types (java.util.Base64, not android.util.Base64) so
 * the rules are unit tested on the JVM.
 */
object SharePayload {
    /** A 1080×1920 card is a few MB at most; this leaves room for a photo. */
    const val MAX_BYTES = 12 * 1024 * 1024

    private val FILENAME = Regex("^[A-Za-z0-9_-][A-Za-z0-9._-]{0,75}\\.png$")
    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val json = Json { ignoreUnknownKeys = true }

    class Image(val filename: String, val bytes: ByteArray)

    /** The image in a "share" message's data, or null when anything about it is invalid. */
    fun parse(jsonData: String?, maxBytes: Int = MAX_BYTES): Image? {
        val fields = runCatching { json.parseToJsonElement(jsonData ?: return null) as? JsonObject }
            .getOrNull() ?: return null
        val filename = fields.string("filename")?.takeIf { isFilename(it) } ?: return null
        val data = fields.string("data") ?: return null
        return decodePng(data, maxBytes)?.let { Image(filename, it) }
    }

    fun isFilename(name: String): Boolean = FILENAME.matches(name)

    /** The PNG bytes [data] encodes, or null if it isn't base64, is too big or isn't a PNG. */
    fun decodePng(data: String, maxBytes: Int = MAX_BYTES): ByteArray? {
        if (data.length > (maxBytes.toLong() + 2) / 3 * 4) return null
        val bytes = try {
            Base64.getDecoder().decode(data)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (bytes.size > maxBytes || bytes.size < PNG_SIGNATURE.size) return null
        return bytes.takeIf { PNG_SIGNATURE.indices.all { i -> bytes[i] == PNG_SIGNATURE[i] } }
    }

    private fun JsonObject.string(key: String): String? =
        (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
}
