package app.calcpace.recorder

import android.location.Location
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * Everything the phone said during a test run, for studying a run afterwards
 * (G0, debug builds only): `files/recorder/current.diag.csv`, one reading a
 * line, `<epoch ms>,<KIND>,<fields>`:
 *
 * - `GPS` / `FUSED`: every position the GPS or Google's fused provider gave,
 *   kept by the [Track] or not: fix time, lat, lon, altitude, accuracy,
 *   speed, speed accuracy, bearing, vertical accuracy, age in ms;
 * - `GNSS`: satellites visible, used in the fix, mean and max C/N0 (dB-Hz)
 *   of the used ones;
 * - `STEP`: the phone's step counter as it reads (counts since the phone
 *   booted: [DiagReport] takes the differences);
 * - `PRES`: air pressure, hPa (only on phones with a barometer);
 * - `ACT`: what Google's activity recognition thinks the runner is doing,
 *   and how sure (0–100);
 * - `SCREEN` on / off / unlocked, `APP` fg / bg (the recording screen),
 *   `POWER` save_on / save_off / idle_on / idle_off, `BATT` level, charging,
 *   thermal status;
 * - `RUN` start / pause / resume / stop, as the runner pressed them;
 * - `NOTE` anything else worth knowing (the GPS switched off, the service
 *   restarted after a kill, a sensor the phone lacks).
 *
 * The [RunLog] stays what rebuilds the run; this file only describes it. It
 * is flushed per line and synced every few seconds: a kill loses at most the
 * last seconds of readings, never the run. A failing disk turns the log
 * off, never throws: the recording calls in here. Main thread only.
 */
object DiagLog {
    private const val SYNC_EVERY_MS = 5_000L
    private const val TAG = "Recorder"

    private var out: FileOutputStream? = null
    private var lastSync = 0L

    val isOpen: Boolean get() = out != null

    fun open(file: File) {
        if (out != null) return
        val fresh = !file.exists() || file.length() == 0L
        try {
            // A kill can leave the last line half written: start a line of our own.
            val torn = !fresh && file.readBytes().last() != '\n'.code.toByte()
            out = FileOutputStream(file, true).also { if (torn) it.write('\n'.code) }
        } catch (e: Exception) {
            fail(e)
            return
        }
        if (fresh) {
            write("NOTE", "log v1 · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        }
    }

    fun close() {
        val stream = out ?: return
        out = null
        try {
            stream.flush()
            stream.fd.sync()
        } catch (e: Exception) {
            Log.w(TAG, "Readings not synced: $e")
        } finally {
            runCatching { stream.close() }
        }
    }

    /** A no-op when no run is on, so callers need not ask. */
    fun write(kind: String, vararg fields: Any?) {
        val stream = out ?: return
        val now = System.currentTimeMillis()
        val line = buildString {
            append(now).append(',').append(kind)
            fields.forEach { append(',').append(field(it)) }
            append('\n')
        }
        try {
            stream.write(line.toByteArray())
            if (now - lastSync >= SYNC_EVERY_MS) {
                stream.fd.sync()
                lastSync = now
            }
        } catch (e: Exception) {
            fail(e)
        }
    }

    private fun fail(e: Exception) {
        Log.w(TAG, "Readings off for this run: $e")
        out?.let { runCatching { it.close() } }
        out = null
    }

    fun location(kind: String, location: Location) {
        val ageMs = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000
        write(
            kind,
            location.time,
            "%.7f".format(Locale.ROOT, location.latitude),
            "%.7f".format(Locale.ROOT, location.longitude),
            location.takeIf { it.hasAltitude() }?.altitude,
            location.takeIf { it.hasAccuracy() }?.accuracy,
            location.takeIf { it.hasSpeed() }?.speed,
            location.takeIf { it.hasSpeedAccuracy() }?.speedAccuracyMetersPerSecond,
            location.takeIf { it.hasBearing() }?.bearing,
            location.takeIf { it.hasVerticalAccuracy() }?.verticalAccuracyMeters,
            ageMs,
        )
    }

    // Commas would split a field: notes are free text.
    private fun field(value: Any?): String = when (value) {
        null -> ""
        is Float -> "%.2f".format(Locale.ROOT, value)
        is Double -> "%.2f".format(Locale.ROOT, value)
        else -> value.toString().replace(',', ';').replace('\n', ' ')
    }
}
