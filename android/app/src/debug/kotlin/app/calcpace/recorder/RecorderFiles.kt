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
import java.io.OutputStream
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Where a run lives on the phone. While it is on: `files/recorder/current.log`
 * (the [RunLog], appended and synced line by line) and `current.diag.csv`
 * (the [DiagLog]). Once stopped, both and the GPX move to
 * `files/recorder/runs/`, and Downloads/Calcpace gets the GPX, which the
 * site's run form can pick, and a zip of everything (GPX, log, readings,
 * summary.txt) to study the run.
 */
class RecorderFiles(context: Context) {
    private val app = context.applicationContext
    private val dir = File(app.filesDir, "recorder").apply { mkdirs() }
    private val runs = File(dir, "runs").apply { mkdirs() }
    val current = File(dir, "current.log")
    val diag = File(dir, "current.diag.csv")

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
        val download = toDownloads(name, "application/gpx+xml") { it.write(gpx.toByteArray()) }
        return Finished(name, download, bundle(name, track, gpx))
    }

    /**
     * The zip to study the run: everything the recorder wrote, plus the
     * summary. Built after the run is safe in runs/, so failing here loses
     * nothing but the zip.
     */
    private fun bundle(name: String, track: Track, gpx: String): Uri? = try {
        val base = name.removeSuffix(".gpx")
        val readings = File(runs, "$base.diag.csv")
        if (diag.exists() && !diag.renameTo(readings)) throw IOException("Could not move ${diag.name} to runs/")
        val diagText = if (readings.exists()) readings.readText() else ""
        val summary = DiagReport.build(DeviceFacts.of(app), track, diagText, ZoneId.systemDefault())
        File(runs, "$base.summary.txt").writeText(summary)
        toDownloads("$base.zip", "application/zip") { stream ->
            ZipOutputStream(stream).use { zip ->
                fun entry(entryName: String, bytes: ByteArray) {
                    zip.putNextEntry(ZipEntry(entryName))
                    zip.write(bytes)
                    zip.closeEntry()
                }
                entry(name, gpx.toByteArray())
                File(runs, "$base.log").takeIf { it.exists() }?.let { entry("$base.log", it.readBytes()) }
                entry("$base.diag.csv", diagText.toByteArray())
                entry("$base.summary.txt", summary.toByteArray())
            }
        }
    } catch (e: Exception) {
        Log.w("Recorder", "No study zip: $e")
        null
    }

    private fun toDownloads(name: String, mime: String, write: (OutputStream) -> Unit): Uri? = try {
        copyToDownloads(name, mime, write)
    } catch (e: Exception) {
        Log.w("Recorder", "No copy of $name in Downloads: $e")
        null
    }

    private fun copyToDownloads(name: String, mime: String, write: (OutputStream) -> Unit): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Calcpace")
        }
        val resolver = app.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        try {
            resolver.openOutputStream(uri)?.use(write) ?: throw IOException("No stream")
        } catch (e: Exception) {
            // An empty file in Downloads is one the run form could pick.
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }

    /**
     * [name] null: the run could not be saved and is still in current.log.
     * [bundle]: the study zip in Downloads, if it could be made.
     */
    data class Finished(val name: String?, val download: Uri?, val bundle: Uri? = null)
}
