package app.calcpace.share

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ShareImageFilesTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun image(bytes: ByteArray = byteArrayOf(1, 2, 3)) = SharePayload.Image("calcpace-run-12.png", bytes)

    @Test
    fun eachShareGetsAFolderOfItsOwnUnderTheRootWithTheSameName() {
        val root = File(temp.root, "shared_images")
        val first = ShareImageFiles.write(root, image())
        val second = ShareImageFiles.write(root, image(byteArrayOf(4, 5)))

        assertEquals("calcpace-run-12.png", first.name)
        assertEquals("calcpace-run-12.png", second.name)
        assertEquals(root, first.parentFile!!.parentFile)
        assertEquals(root, second.parentFile!!.parentFile)
        assertNotEquals(first.parentFile, second.parentFile)
        assertArrayEquals(byteArrayOf(4, 5), second.readBytes())
    }

    @Test
    fun onlyTheLastCardIsKept() {
        val root = File(temp.root, "shared_images")
        val first = ShareImageFiles.write(root, image())
        File(root, "stray.png").writeBytes(byteArrayOf(9))
        val second = ShareImageFiles.write(root, image())

        assertFalse(first.exists())
        assertFalse(first.parentFile!!.exists())
        assertEquals(listOf(second.parentFile), root.listFiles()!!.toList())
        assertTrue(second.isFile)
    }

    @Test
    fun nothingOutsideTheRootIsTouched() {
        val neighbour = temp.newFile("other.png")
        ShareImageFiles.write(File(temp.root, "shared_images"), image())
        assertTrue(neighbour.exists())
    }
}
