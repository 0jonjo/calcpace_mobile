package app.calcpace.share

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * The card on its way out: written to cacheDir/shared_images/ (served by the
 * FileProvider declared in the manifest, res/xml/share_image_paths.xml) and
 * handed to the share sheet with a read grant for whoever is picked.
 */
object ShareImageFiles {
    private const val DIR = "shared_images"

    /** Blocking file IO: call off the main thread. */
    fun save(context: Context, image: SharePayload.Image): Uri {
        val file = write(File(context.cacheDir, DIR), image)
        return FileProvider.getUriForFile(context, "${context.packageName}.shareimages", file)
    }

    /**
     * Writes [image] to a fresh folder under [root], keeping its name as the
     * one the receiving app shows. Every look of a run has the same file
     * name, so a folder of its own gives each share its own content URI:
     * an app that cached the last one never gets it back for a new card.
     *
     * Whatever an earlier share left under [root] is deleted first, so it
     * only ever holds the last card. Not after this one: the receiving app
     * may read it any time after the sheet closes.
     *
     * Plain java.io, so it is unit tested on the JVM.
     */
    fun write(root: File, image: SharePayload.Image): File {
        root.listFiles()?.forEach { it.deleteRecursively() }
        val dir = File(root, UUID.randomUUID().toString())
        if (!dir.mkdirs()) throw IOException("can't create $dir")
        return File(dir, image.filename).apply { writeBytes(image.bytes) }
    }

    fun chooser(uri: Uri): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            // The grant travels with the ClipData; createChooser carries both
            // over to the chooser and on to the app picked.
            clipData = ClipData.newRawUri("", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, null)
    }
}
