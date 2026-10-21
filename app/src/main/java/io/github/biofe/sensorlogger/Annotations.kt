package io.github.biofe.sensorlogger

import android.content.Context
import io.github.biofe.sensorlogger.core.GeoPoint
import io.github.biofe.sensorlogger.core.PrivacyTrim
import java.io.File

/** Reading and writing annotation CSVs: one per ride, plus one for spots picked on the map. */
object Annotations {

    class Spot(val lat: Double, val lon: Double, val fields: Map<String, String>) {
        /** Answers only, for map labels */
        val summary: String
            get() = fields.filterKeys { it !in setOf("t_utc_ms", "lat", "lon", "accuracy_m") }
                .filterValues { it.isNotBlank() }
                .entries.joinToString("\n") { "${it.key.replace('_', ' ')}: ${it.value.replace(';', ',')}" }
    }

    fun mapFile(context: Context) = File(Rides.root(context).parentFile, "map_annotations.csv")

    @Synchronized
    fun append(file: File, row: String) {
        if (!file.exists()) file.writeText(AnnotationPoll.HEADER)
        file.appendText(row + "\n")
    }

    /** Every annotation on the phone, from rides and the map; older files with other columns work too. */
    fun all(context: Context): List<Spot> =
        (Rides.list(context).map { File(it, Rides.ANNOTATIONS_FILE) } + mapFile(context)).flatMap { read(it) }

    fun read(file: File): List<Spot> {
        if (!file.exists()) return emptyList()
        val lines = file.readLines().filter { it.isNotBlank() }
        if (lines.size < 2) return emptyList()
        val header = parseCsvLine(lines[0])
        return lines.drop(1).mapNotNull { line ->
            val values = parseCsvLine(line)
            val fields = header.zip(values).toMap()
            val lat = fields["lat"]?.toDoubleOrNull() ?: return@mapNotNull null
            val lon = fields["lon"]?.toDoubleOrNull() ?: return@mapNotNull null
            Spot(lat, lon, fields)
        }
    }

    /**
     * Map annotations as exported: spots within the largest trim radius of any ride's start or end
     * on this phone are left out, so a spot near home isn't shared even though it was placed by hand.
     */
    fun exportableMapLines(context: Context): List<String> {
        val file = mapFile(context)
        if (!file.exists()) return emptyList()
        val ends = Rides.list(context).flatMap { endPoints(File(it, Rides.GPS_FILE)) }
        val lines = file.readLines().filter { it.isNotBlank() }
        if (lines.size < 2) return emptyList()
        val kept = lines.drop(1).filter { line ->
            val v = parseCsvLine(line)
            val lat = v.getOrNull(1)?.toDoubleOrNull() ?: return@filter false
            val lon = v.getOrNull(2)?.toDoubleOrNull() ?: return@filter false
            val p = GeoPoint(0, lat, lon)
            ends.none { PrivacyTrim.distanceM(it, p) <= PrivacyTrim.MAX_RADIUS_M }
        }
        return if (kept.isEmpty()) emptyList() else listOf(lines[0]) + kept
    }

    private fun endPoints(gps: File): List<GeoPoint> {
        if (!gps.exists()) return emptyList()
        val rows = gps.useLines { seq ->
            seq.drop(1).mapNotNull { line ->
                val c = line.split(',')
                val lat = c.getOrNull(2)?.toDoubleOrNull()
                val lon = c.getOrNull(3)?.toDoubleOrNull()
                if (lat == null || lon == null) null else GeoPoint(0, lat, lon)
            }.toList()
        }
        return listOfNotNull(rows.firstOrNull(), rows.lastOrNull())
    }

    fun parseCsvLine(line: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                quoted && ch == '"' && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                ch == '"' -> quoted = !quoted
                ch == ',' && !quoted -> { out.add(sb.toString()); sb.clear() }
                else -> sb.append(ch)
            }
            i++
        }
        out.add(sb.toString())
        return out
    }
}
