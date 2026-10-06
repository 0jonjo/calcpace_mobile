package app.calcpace.share

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException

/**
 * The card on its way out: written to cacheDir/shared_images/ (served by the
 * FileProvider declared in the manifest, res/xml/share_image_paths.xml) and
 * handed to the share sheet with a read grant for whoever is picked.
 *
 * The folder only ever holds the last card: the receiving app may read it
 * any time after the sheet closes, so it is cleared before the next one
 * rather than after this one.
 */
object ShareImageFiles {
    private const val DIR = "shared_images"

    /** Blocking file IO: call off the main thread. */
    fun save(context: Context, image: SharePayload.Image): Uri {
        val dir = File(context.cacheDir, DIR)
        dir.listFiles()?.forEach { it.delete() }
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("can't create $dir")
        val file = File(dir, image.filename)
        file.writeBytes(image.bytes)
        return FileProvider.getUriForFile(context, "${context.packageName}.shareimages", file)
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
