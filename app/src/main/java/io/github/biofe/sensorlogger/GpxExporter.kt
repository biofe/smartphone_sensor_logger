package io.github.biofe.sensorlogger

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Writes a ride's full, untrimmed GPS track as GPX for the rider's own use (Strava, Komoot, ...).
 * This is separate from the project export and is never privacy-trimmed.
 */
object GpxExporter {

    class Result(val uri: Uri, val fileName: String)

    fun export(context: Context, ride: File): Result? {
        val gps = File(ride, Rides.GPS_FILE)
        if (!gps.exists()) return null
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val fileName = "track_${ride.name}.gpx"
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "application/gpx+xml")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/BikeSensorLogger")
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Could not create $fileName in Downloads")
        var points = 0
        resolver.openOutputStream(uri)!!.bufferedWriter().use { w ->
            w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            w.write("<gpx version=\"1.1\" creator=\"Bike Sensor Logger\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
            w.write("<trk><name>Ride ${ride.name}</name><type>cycling</type><trkseg>\n")
            gps.useLines { lines ->
                lines.drop(1).forEach { line ->
                    // t_utc_ms,gps_time_ms,lat,lon,alt_m,...
                    val c = line.split(',')
                    if (c.size < 5) return@forEach
                    w.write("<trkpt lat=\"${c[2]}\" lon=\"${c[3]}\"><ele>${c[4]}</ele><time>${iso.format(Date(c[1].toLong()))}</time></trkpt>\n")
                    points++
                }
            }
            w.write("</trkseg></trk>\n</gpx>\n")
        }
        if (points == 0) {
            resolver.delete(uri, null, null)
            return null
        }
        return Result(uri, fileName)
    }
}
