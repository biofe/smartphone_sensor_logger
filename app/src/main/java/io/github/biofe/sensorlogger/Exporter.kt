package io.github.biofe.sensorlogger

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import io.github.biofe.sensorlogger.core.GeoPoint
import io.github.biofe.sensorlogger.core.PrivacyTrim
import io.github.biofe.sensorlogger.core.RideName
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Packs rides into one ZIP in Downloads/BikeSensorLogger, after cutting the start and end of
 * every ride (see [PrivacyTrim]). Folders inside the ZIP are named like `20261021-1627Z-k3f9` (see
 * [RideName]); rides recorded before 0.7.0 keep their random 8-character id.
 */
object Exporter {

    /** Rides recorded by 0.1.0 have no random radii stored */
    private const val LEGACY_RADIUS_M = 200.0

    class Result(val uri: Uri?, val fileName: String, val exported: Int, val skipped: Int)

    /** [progress] is called with (rides done, rides total) from the exporting thread. */
    fun export(context: Context, rides: List<File>, progress: (Int, Int) -> Unit = { _, _ -> }): Result {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val fileName = "rides_$stamp.zip"
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/BikeSensorLogger")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Could not create $fileName in Downloads")

        var exported = 0
        var skipped = 0
        try {
            resolver.openOutputStream(uri)!!.use { out ->
                ZipOutputStream(out).use { zip ->
                    rides.forEachIndexed { i, ride ->
                        progress(i, rides.size)
                        // One unreadable ride (e.g. cut off by a crash) must not sink the whole export
                        val ok = runCatching { writeRide(ride, zip) }.getOrElse { runCatching { zip.closeEntry() }; false }
                        if (ok) exported++ else skipped++
                    }
                    progress(rides.size, rides.size)
                    val mapLines = Annotations.exportableMapLines(context)
                    if (exported > 0 && mapLines.isNotEmpty()) {
                        zip.putNextEntry(ZipEntry("map_annotations.csv"))
                        zip.write(mapLines.joinToString("\n", postfix = "\n").toByteArray())
                        zip.closeEntry()
                    }
                }
            }
        } catch (e: Exception) {
            resolver.delete(uri, null, null) // don't leave a hidden, half-written file behind
            throw e
        }
        if (exported == 0) {
            resolver.delete(uri, null, null)
            return Result(null, fileName, 0, skipped)
        }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return Result(uri, fileName, exported, skipped)
    }

    /** Returns false if the ride has nothing left after privacy trimming. */
    private fun writeRide(ride: File, zip: ZipOutputStream): Boolean {
        val metaFile = File(ride, Rides.META_FILE)
        val gpsFile = File(ride, Rides.GPS_FILE)
        if (!metaFile.exists() || !gpsFile.exists()) return false

        val meta = JSONObject(metaFile.readText())
        // An auto-stopped ride ends where the rider stopped moving, not ten minutes later
        val endMs = meta.optLong("still_since_utc_ms", Long.MAX_VALUE)
        val points = gpsFile.useLines { lines ->
            lines.drop(1).mapNotNull { line ->
                val c = line.split(',')
                if (c.size < 4) return@mapNotNull null
                val t = c[0].toLongOrNull()
                val lat = c[2].toDoubleOrNull()
                val lon = c[3].toDoubleOrNull()
                if (t == null || lat == null || lon == null) null else GeoPoint(t, lat, lon)
            }.filter { it.tMs <= endMs }.toList()
        }
        val window = PrivacyTrim.keepWindow(
            points,
            meta.optDouble("trim_start_m", LEGACY_RADIUS_M),
            meta.optDouble("trim_end_m", LEGACY_RADIUS_M),
        ) ?: return false

        // Rides recorded before 0.7.0 have no suffix: keep their old id so earlier exports still match
        val suffix = meta.optString("name_suffix", "")
        val rideId = if (suffix.isEmpty()) meta.getString("ride_id") else RideName.of(window.first, suffix)
        meta.put("ride_id", rideId)
        meta.put("privacy_trim", "random ${PrivacyTrim.MIN_RADIUS_M.toInt()}-${PrivacyTrim.MAX_RADIUS_M.toInt()} m at each end")
        meta.put("kept_from_utc_ms", window.first)
        meta.put("kept_to_utc_ms", window.last)
        // Exact start/stop times and the trim radii would undo part of the trimming
        meta.remove("started_utc_ms")
        meta.remove("stopped_utc_ms")
        meta.remove("still_since_utc_ms")
        meta.remove("trim_start_m")
        meta.remove("trim_end_m")
        meta.remove("name_suffix")

        zip.putNextEntry(ZipEntry("$rideId/${Rides.META_FILE}"))
        zip.write(meta.toString(2).toByteArray())
        zip.closeEntry()

        for (name in listOf(Rides.ACCEL_FILE, Rides.GYRO_FILE, Rides.PRESSURE_FILE, Rides.GPS_FILE, Rides.SOUND_FILE, Rides.ANNOTATIONS_FILE)) {
            val file = File(ride, name)
            if (!file.exists()) continue
            zip.putNextEntry(ZipEntry("$rideId/$name"))
            val writer = zip.bufferedWriter()
            file.useLines { lines ->
                val it = lines.iterator()
                if (it.hasNext()) writer.write(it.next() + "\n")
                while (it.hasNext()) {
                    val line = it.next()
                    val t = line.substringBefore(',').toDoubleOrNull() ?: continue
                    if (t >= window.first && t <= window.last) writer.write(line + "\n")
                }
            }
            writer.flush() // don't close: that would close the whole ZIP stream
            zip.closeEntry()
        }
        return true
    }
}
