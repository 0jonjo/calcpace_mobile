package app.calcpace.share

import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharePayloadTest {
    private val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val png = signature + byteArrayOf(0, 0, 0, 13, 0x49, 0x48, 0x44, 0x52)

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun pngOf(size: Int) = ByteArray(size).also { signature.copyInto(it) }

    private fun json(filename: String = "calcpace-run-12.png", data: String = encode(png)) =
        """{"filename":"$filename","data":"$data"}"""

    @Test
    fun aGoodPayload() {
        val image = SharePayload.parse(json())
        assertNotNull(image)
        assertEquals("calcpace-run-12.png", image!!.filename)
        assertArrayEquals(png, image.bytes)
    }

    @Test
    fun extraFieldsAreIgnored() {
        assertNotNull(SharePayload.parse("""{"filename":"a.png","data":"${encode(png)}","variant":"photo"}"""))
    }

    @Test
    fun goodFilenames() {
        listOf(
            "a.png", "calcpace_2026-10-06.png", "run.12.png", "-a.png", "_.png", "A".repeat(76) + ".png"
        ).forEach { assertTrue(it, SharePayload.isFilename(it)) }
        assertEquals(80, ("A".repeat(76) + ".png").length)
    }

    @Test
    fun badFilenames() {
        listOf(
            "", ".png", "png", "a.PNG", "a.jpg", "a.png.txt", "a.png ", " a.png", ".hidden.png", "../x.png", "..png",
            "a/b.png", "a\\b.png", "/a.png", "a b.png", "ação.png", "a%2Fb.png", "a:b.png", "a.png\n", "a\u0000.png",
            "A".repeat(77) + ".png"
        ).forEach { assertFalse(it, SharePayload.isFilename(it)) }
        assertEquals(81, ("A".repeat(77) + ".png").length)
    }

    @Test
    fun aBadFilenameRefusesThePayload() {
        listOf("../x.png", "a/b.png", "x.jpg", "A".repeat(77) + ".png").forEach {
            assertNull(it, SharePayload.parse(json(filename = it)))
        }
    }

    @Test
    fun malformedOrIncompleteDataIsRefused() {
        listOf(
            null, "", "null", "[]", "\"x\"", "{", "{}", """{"filename":"a.png"}""", """{"data":"${encode(png)}"}""",
            """{"filename":"a.png","data":null}""", """{"filename":"a.png","data":123}""",
            """{"filename":["a.png"],"data":"${encode(png)}"}""", """{"filename":"a.png","data":{"x":1}}"""
        ).forEach { assertNull(it, SharePayload.parse(it)) }
    }

    @Test
    fun base64GarbageIsRefused() {
        val good = encode(png)
        listOf(
            "", "!!!!", "abc", "a", good.dropLast(1), "$good=", "data:image/png;base64,$good", " $good",
            good.chunked(8).joinToString("\n"), "%%%%" + good
        ).forEach { assertNull(it, SharePayload.decodePng(it)) }
    }

    @Test
    fun urlSafeBase64IsRefusedWhenItDiffersFromStandard() {
        // Bytes whose standard base64 contains "+" and "/".
        val bytes = signature + byteArrayOf(0xFB.toByte(), 0xFF.toByte(), 0xBF.toByte())
        val standard = encode(bytes)
        assertTrue(standard.contains('+') || standard.contains('/'))
        assertNotNull(SharePayload.decodePng(standard))
        assertNull(SharePayload.decodePng(Base64.getUrlEncoder().encodeToString(bytes)))
    }

    @Test
    fun nonPngBytesAreRefused() {
        listOf(
            byteArrayOf(), signature.copyOf(7), "GIF89a..".toByteArray(), byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0, 0, 0),
            byteArrayOf(0x88.toByte()) + signature.copyOfRange(1, 8), "<svg xmlns='http://www.w3.org/2000/svg'/>".toByteArray()
        ).forEach { assertNull(it.contentToString(), SharePayload.decodePng(encode(it))) }
        assertNotNull(SharePayload.decodePng(encode(signature)))
    }

    @Test
    fun theSizeCapIsOnTheDecodedBytes() {
        assertNotNull(SharePayload.decodePng(encode(pngOf(300)), maxBytes = 300))
        assertNull(SharePayload.decodePng(encode(pngOf(301)), maxBytes = 300))
        assertNotNull(SharePayload.decodePng(encode(pngOf(299)), maxBytes = 300))
        assertNull(SharePayload.parse(json(data = encode(pngOf(301))), maxBytes = 300))
    }

    @Test
    fun anOversizedPayloadIsRefusedBeforeDecoding() {
        // Well-formed base64 of 303 bytes: refused on its length alone.
        assertNull(SharePayload.decodePng("A".repeat(404), maxBytes = 300))
    }

    @Test
    fun theRealCapIsEightMegabytes() {
        assertEquals(8 * 1024 * 1024, SharePayload.MAX_BYTES)
        assertNotNull(SharePayload.decodePng(encode(pngOf(SharePayload.MAX_BYTES))))
        assertNull(SharePayload.decodePng(encode(pngOf(SharePayload.MAX_BYTES + 1))))
    }
}
