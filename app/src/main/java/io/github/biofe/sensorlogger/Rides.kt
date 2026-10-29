package io.github.biofe.sensorlogger

import android.content.Context
import io.github.biofe.sensorlogger.core.GeoPoint
import io.github.biofe.sensorlogger.core.RideStats
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Ride folders live in the app's private external storage: one folder per ride, one CSV per sensor. */
object Rides {
    const val ACCEL_FILE = "accel.csv"
    const val GPS_FILE = "gps.csv"
    const val SOUND_FILE = "sound.csv"
    const val GYRO_FILE = "gyro.csv"
    const val PRESSURE_FILE = "pressure.csv"
    const val ANNOTATIONS_FILE = "annotations.csv"
    const val META_FILE = "meta.json"

    /** What only this phone needs: the bike's colour and the cached distance. Never exported or uploaded. */
    const val PHONE_FILE = "phone.json"

    private const val FOLDER_FORMAT = "yyyyMMdd-HHmmss"

    fun root(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "rides").apply { mkdirs() }

    fun newRideDir(context: Context): File {
        val name = SimpleDateFormat(FOLDER_FORMAT, Locale.US).format(Date())
        return File(root(context), name).apply { mkdirs() }
    }

    fun list(context: Context): List<File> =
        root(context).listFiles { f -> f.isDirectory }?.sortedByDescending { it.name } ?: emptyList()

    /** One ride as the ride list shows it. Null values aren't known (yet). */
    class Info(
        val dir: File,
        val startedAt: Date?,
        val distanceM: Double?,
        val durationS: Long?,
        val sizeBytes: Long,
        val bikeType: String,
        /** Index into the bike colours; null if the ride's bike is unknown */
        val color: Int?,
        val upload: Upload.Status,
        val recording: Boolean,
    )

    /** Reads (and caches) what the ride list shows; call off the main thread, as it may read the GPS file. */
    fun info(context: Context, ride: File): Info {
        val recording = RecorderState.recording && RecorderState.rideDir == ride
        val meta = readJson(File(ride, META_FILE))
        val phoneFile = File(ride, PHONE_FILE)
        val phone = readJson(phoneFile) ?: JSONObject()
        val bikeType = meta?.optString("bike_type", "").orEmpty()

        var distance = phone.optDouble("distance_m").takeUnless { it.isNaN() }
        if (distance == null && !recording) {
            distance = RideStats.distanceM(track(ride))
            runCatching { phoneFile.writeText(phone.put("distance_m", distance).toString()) }
        }
        val started = meta?.optLong("started_utc_ms", 0L)?.takeIf { it > 0 }
        val end = meta?.optLong("still_since_utc_ms", 0L)?.takeIf { it > 0 }
            ?: meta?.optLong("stopped_utc_ms", 0L)?.takeIf { it > 0 }
        val duration = if (started != null && end != null) (end - started) / 1000
        else if (recording) (System.currentTimeMillis() - RecorderState.startedAtMs) / 1000 else null

        return Info(
            dir = ride,
            startedAt = started?.let { Date(it) } ?: runCatching { SimpleDateFormat(FOLDER_FORMAT, Locale.US).parse(ride.name) }.getOrNull(),
            distanceM = if (recording) RecorderState.distanceM else distance,
            durationS = duration,
            sizeBytes = ride.walkTopDown().filter { it.isFile }.sumOf { it.length() },
            bikeType = bikeType,
            color = if (phone.has("bike_color")) phone.optInt("bike_color") else colorForType(context, bikeType),
            upload = Upload.status(ride),
            recording = recording,
        )
    }

    /** Remembers which bike a new ride is on, for its colour in the list. */
    fun writeBike(ride: File, bike: Bikes.Bike?) {
        if (bike == null) return
        runCatching { File(ride, PHONE_FILE).writeText(JSONObject().put("bike_id", bike.id).put("bike_color", bike.color).toString()) }
    }

    /** Rides from before 0.10.0 don't know their bike: use the colour of a bike of the same type, if there is one. */
    private fun colorForType(context: Context, type: String): Int? =
        Bikes.list(context).firstOrNull { it.type == type }?.color

    private fun track(ride: File): List<GeoPoint> {
        val gps = File(ride, GPS_FILE)
        if (!gps.exists()) return emptyList()
        return gps.useLines { lines ->
            lines.drop(1).mapNotNull { line ->
                val c = line.split(',')
                val t = c.getOrNull(0)?.toLongOrNull()
                val lat = c.getOrNull(2)?.toDoubleOrNull()
                val lon = c.getOrNull(3)?.toDoubleOrNull()
                if (t == null || lat == null || lon == null) null else GeoPoint(t, lat, lon)
            }.toList()
        }
    }

    private fun readJson(file: File): JSONObject? =
        if (file.exists()) runCatching { JSONObject(file.readText()) }.getOrNull() else null
}
