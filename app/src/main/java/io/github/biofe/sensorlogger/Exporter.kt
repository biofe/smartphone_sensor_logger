package io.github.biofe.sensorlogger

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import io.github.biofe.sensorlogger.core.GeoPoint
import io.github.biofe.sensorlogger.core.PrivacyTrim
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Packs rides into one ZIP in Downloads/BikeSensorLogger, after cutting the start and end of
 * every ride (see [PrivacyTrim]). Folders inside the ZIP are named by the random ride id only.
 */
object Exporter {

    /** Rides recorded by 0.1.0 have no random radii stored */
    private const val LEGACY_RADIUS_M = 200.0

    class Result(val uri: Uri?, val fileName: String, val exported: Int, val skipped: Int)

    fun export(context: Context, rides: List<File>): Result {
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
        resolver.openOutputStream(uri)!!.use { out ->
            ZipOutputStream(out).use { zip ->
                for (ride in rides) {
                    if (writeRide(ride, zip)) exported++ else skipped++
                }
            }
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
                if (c.size < 4) null else GeoPoint(c[0].toLong(), c[2].toDouble(), c[3].toDouble())
            }.filter { it.tMs <= endMs }.toList()
        }
        val window = PrivacyTrim.keepWindow(
            points,
            meta.optDouble("trim_start_m", LEGACY_RADIUS_M),
            meta.optDouble("trim_end_m", LEGACY_RADIUS_M),
        ) ?: return false

        val rideId = meta.getString("ride_id")
        meta.put("privacy_trim", "random ${PrivacyTrim.MIN_RADIUS_M.toInt()}-${PrivacyTrim.MAX_RADIUS_M.toInt()} m at each end")
        meta.put("kept_from_utc_ms", window.first)
        meta.put("kept_to_utc_ms", window.last)
        // Exact start/stop times and the trim radii would undo part of the trimming
        meta.remove("started_utc_ms")
        meta.remove("stopped_utc_ms")
        meta.remove("still_since_utc_ms")
        meta.remove("trim_start_m")
        meta.remove("trim_end_m")

        zip.putNextEntry(ZipEntry("$rideId/${Rides.META_FILE}"))
        zip.write(meta.toString(2).toByteArray())
        zip.closeEntry()

        for (name in listOf(Rides.ACCEL_FILE, Rides.GPS_FILE, Rides.SOUND_FILE)) {
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
