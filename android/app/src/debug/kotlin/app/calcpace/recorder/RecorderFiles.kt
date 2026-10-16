package app.calcpace.recorder

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream

/**
 * Where a run lives on the phone. While it is on: `files/recorder/current.log`
 * (the [RunLog], appended and synced line by line). Once stopped, the log and
 * its GPX move to `files/recorder/runs/`, and the GPX is copied to
 * Downloads/Calcpace, where the site's run form can pick it.
 */
class RecorderFiles(context: Context) {
    private val app = context.applicationContext
    private val dir = File(app.filesDir, "recorder").apply { mkdirs() }
    private val runs = File(dir, "runs").apply { mkdirs() }
    val current = File(dir, "current.log")

    private var out: FileOutputStream? = null

    fun hasUnfinishedRun(): Boolean {
        if (!current.exists()) return false
        val track = Track.of(readCurrent())
        return track.startedAt != null && !track.isStopped
    }

    fun readCurrent(): List<RunEvent> = if (current.exists()) RunLog.parse(current.readText()) else emptyList()

    /** One line, on disk before this returns: a kill a moment later loses nothing. */
    fun append(event: RunEvent) {
        val stream = out ?: FileOutputStream(current, true).also { out = it }
        stream.write((RunLog.line(event) + "\n").toByteArray())
        stream.flush()
        stream.fd.sync()
    }

    fun close() {
        out?.close()
        out = null
    }

    /** Saves the finished run and starts a clean log. Returns where Downloads has it. */
    fun finish(track: Track): Finished {
        close()
        val name = Gpx.filename(track.startedAt ?: System.currentTimeMillis())
        val gpx = Gpx.write(track, "Run")
        File(runs, name).writeText(gpx)
        current.renameTo(File(runs, name.removeSuffix(".gpx") + ".log"))
        return Finished(name, toDownloads(name, gpx))
    }

    private fun toDownloads(name: String, gpx: String): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/gpx+xml")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Calcpace")
        }
        val resolver = app.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { it.write(gpx.toByteArray()) } ?: return null
        return uri
    }

    data class Finished(val name: String, val download: Uri?)
}
