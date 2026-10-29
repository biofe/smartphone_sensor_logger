package io.github.biofe.sensorlogger.core

/**
 * Distance ridden, for the ride list and the recording screen. GPS sometimes jumps by tens of
 * metres within a second; such steps are left out, so a jump doesn't add to the distance.
 */
object RideStats {

    /** Faster than this between two fixes is a GPS jump, not riding (about 108 km/h). */
    const val MAX_SPEED_MS = 30.0

    /** Length of the track in metres, skipping implausible steps. */
    fun distanceM(points: List<GeoPoint>): Double {
        var total = 0.0
        for (i in 1 until points.size) total += stepM(points[i - 1], points[i])
        return total
    }

    /** One step's contribution: its length, or 0 if it is a GPS jump. */
    fun stepM(a: GeoPoint, b: GeoPoint): Double {
        val d = PrivacyTrim.distanceM(a, b)
        val dt = (b.tMs - a.tMs) / 1000.0
        return if (dt <= 0 || d / dt > MAX_SPEED_MS) 0.0 else d
    }
}
