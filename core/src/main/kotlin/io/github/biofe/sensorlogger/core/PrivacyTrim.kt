package io.github.biofe.sensorlogger.core

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

data class GeoPoint(val tMs: Long, val lat: Double, val lon: Double)

/**
 * Hides where a ride starts and ends (usually someone's home or workplace).
 * Everything before the rider first leaves [startRadiusM] around the start point, and everything
 * after the rider last enters [endRadiusM] around the end point, is dropped from every sensor.
 * Radii are drawn at random per ride (see [randomRadiusM]) and never exported: with a known
 * radius, the first kept point would place the start on a circle around it.
 */
object PrivacyTrim {

    const val MIN_RADIUS_M = 150.0
    const val MAX_RADIUS_M = 250.0

    /** A trim radius drawn uniformly from [MIN_RADIUS_M, MAX_RADIUS_M). */
    fun randomRadiusM(random: Random): Double = random.nextDouble(MIN_RADIUS_M, MAX_RADIUS_M)

    /** The time window (inclusive, UTC ms) that may be kept, or null if nothing may be kept. */
    fun keepWindow(points: List<GeoPoint>, startRadiusM: Double, endRadiusM: Double = startRadiusM): LongRange? {
        if (points.size < 2) return null
        val first = points.first()
        val last = points.last()
        val start = points.firstOrNull { distanceM(first, it) > startRadiusM }?.tMs ?: return null
        val end = points.lastOrNull { distanceM(last, it) > endRadiusM }?.tMs ?: return null
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
