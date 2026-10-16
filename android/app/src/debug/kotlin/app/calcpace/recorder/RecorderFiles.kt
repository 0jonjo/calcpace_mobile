package app.calcpace.recorder

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

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
        val stream = out ?: open()
        stream.write((RunLog.line(event) + "\n").toByteArray())
        stream.flush()
        stream.fd.sync()
    }

    // A power cut can leave the last line half written. The next event must
    // start a line of its own, or it would be glued to the torn one and lost
    // with it on the next read.
    private fun open(): FileOutputStream {
        val torn = current.length() > 0 && current.readBytes().last() != '\n'.code.toByte()
        return FileOutputStream(current, true).also {
            if (torn) it.write('\n'.code)
            out = it
        }
    }

    fun close() {
        out?.close()
        out = null
    }

    /**
     * Saves the stopped run and starts a clean log. The log moves only once
     * the GPX is written: if writing fails, current.log keeps the stopped run
     * and the next Start saves it before beginning another. Downloads is a
     * copy, and failing to make it loses nothing.
     */
    fun finish(track: Track): Finished {
        close()
        val name = Gpx.filename(track.startedAt ?: System.currentTimeMillis())
        val gpx = Gpx.write(track, "Run")
        File(runs, name).writeText(gpx)
        // Unmoved, the old log would take the next run's events after its Stop.
        if (!current.renameTo(File(runs, name.removeSuffix(".gpx") + ".log"))) {
            throw IOException("Could not move ${current.name} to runs/")
        }
        return Finished(name, toDownloads(name, gpx))
    }

    private fun toDownloads(name: String, gpx: String): Uri? = try {
        copyToDownloads(name, gpx)
    } catch (e: Exception) {
        Log.w("Recorder", "No copy in Downloads: $e")
        null
    }

    private fun copyToDownloads(name: String, gpx: String): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/gpx+xml")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Calcpace")
        }
        val resolver = app.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        try {
            resolver.openOutputStream(uri)?.use { it.write(gpx.toByteArray()) } ?: throw IOException("No stream")
        } catch (e: Exception) {
            // An empty file in Downloads is one the run form could pick.
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }

    /** [name] null: the run could not be saved and is still in current.log. */
    data class Finished(val name: String?, val download: Uri?)
}
