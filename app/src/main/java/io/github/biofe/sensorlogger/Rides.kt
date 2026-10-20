package io.github.biofe.sensorlogger

import android.content.Context
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

    fun root(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "rides").apply { mkdirs() }

    fun newRideDir(context: Context): File {
        val name = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return File(root(context), name).apply { mkdirs() }
    }

    fun list(context: Context): List<File> =
        root(context).listFiles { f -> f.isDirectory }?.sortedByDescending { it.name } ?: emptyList()

    fun describe(ride: File): String {
        val sizeKb = ride.walkTopDown().filter { it.isFile }.sumOf { it.length() } / 1024
        val gpsFixes = File(ride, GPS_FILE).takeIf { it.exists() }?.useLines { it.count() - 1 } ?: 0
        return "${ride.name}  ·  $gpsFixes GPS fixes  ·  $sizeKb KB"
    }
}
