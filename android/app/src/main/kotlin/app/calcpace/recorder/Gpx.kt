package app.calcpace.recorder

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The finished run as GPX 1.1, the format the site's run form already
 * imports: one track, one segment per stretch between pauses, every fix the
 * [Track] kept with its time and, when the GPS gave one, its altitude.
 *
 * A pause is the gap between two segments. The site reads the run's time
 * from the clock of the file, so a pause still counts as running time there
 * until it learns to leave the gaps out (the same as an adidas file today).
 */
object Gpx {
    private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

    fun write(track: Track, name: String): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        append("""<gpx version="1.1" creator="Calcpace Android" xmlns="http://www.topografix.com/GPX/1/1">""").append('\n')
        track.startedAt?.let { append("  <metadata><time>").append(time(it)).append("</time></metadata>\n") }
        append("  <trk>\n")
        append("    <name>").append(escape(name)).append("</name>\n")
        append("    <type>running</type>\n")
        track.segments.forEach { segment ->
            append("    <trkseg>\n")
            segment.forEach { fix ->
                append(String.format(Locale.ROOT, "      <trkpt lat=\"%.7f\" lon=\"%.7f\">", fix.lat, fix.lon))
                fix.altitude?.let { append(String.format(Locale.ROOT, "<ele>%.1f</ele>", it)) }
                append("<time>").append(time(fix.time)).append("</time></trkpt>\n")
            }
            append("    </trkseg>\n")
        }
        append("  </trk>\n")
        append("</gpx>\n")
    }

    /** The runner's local time, which is how they remember the run. */
    fun filename(startedAt: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        "calcpace-" + DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm").withZone(zone)
            .format(Instant.ofEpochMilli(startedAt)) + ".gpx"

    private fun time(epochMillis: Long): String = TIME.format(Instant.ofEpochMilli(epochMillis))

    private fun escape(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
