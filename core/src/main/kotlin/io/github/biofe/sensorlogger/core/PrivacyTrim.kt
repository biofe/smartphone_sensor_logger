package io.github.biofe.sensorlogger.core

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class GeoPoint(val tMs: Long, val lat: Double, val lon: Double)

/**
 * Hides where a ride starts and ends (usually someone's home or workplace).
 * Everything before the rider first leaves [radiusM] around the start point, and everything
 * after the rider last enters [radiusM] around the end point, is dropped from every sensor.
 */
object PrivacyTrim {

    /** The time window (inclusive, UTC ms) that may be kept, or null if nothing may be kept. */
    fun keepWindow(points: List<GeoPoint>, radiusM: Double): LongRange? {
        if (points.size < 2) return null
        val first = points.first()
        val last = points.last()
        val start = points.firstOrNull { distanceM(first, it) > radiusM }?.tMs ?: return null
        val end = points.lastOrNull { distanceM(last, it) > radiusM }?.tMs ?: return null
        return if (start < end) start..end else null
    }

    /** Great-circle distance in metres. */
    fun distanceM(a: GeoPoint, b: GeoPoint): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).let { it * it }
        return 2 * r * asin(sqrt(h))
    }
}
