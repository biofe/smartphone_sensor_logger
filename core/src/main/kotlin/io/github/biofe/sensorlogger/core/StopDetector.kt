package io.github.biofe.sensorlogger.core

/**
 * Decides when a ride is clearly over: the rider has stayed within [radiusM] of one spot for
 * [idleMs]. Waits at traffic lights or level crossings last a few minutes at most, so the
 * default of 10 minutes leaves a wide margin.
 *
 * Fixes worse than [maxAccuracyM] are ignored, so GPS drift indoors doesn't count as moving.
 * No usable fix at all (phone indoors) also counts as not moving.
 */
class StopDetector(
    startMs: Long,
    val radiusM: Double = 50.0,
    val idleMs: Long = 10 * 60 * 1000L,
    val maxAccuracyM: Double = 30.0,
) {
    private var anchor: GeoPoint? = null

    /** When the rider last left the area they are in now. */
    var stillSinceMs: Long = startMs
        private set

    fun onFix(point: GeoPoint, accuracyM: Double) {
        if (accuracyM > maxAccuracyM) return
        val a = anchor
        if (a == null || PrivacyTrim.distanceM(a, point) > radiusM) {
            anchor = point
            stillSinceMs = point.tMs
        }
    }

    fun shouldStop(nowMs: Long): Boolean = nowMs - stillSinceMs >= idleMs
}
