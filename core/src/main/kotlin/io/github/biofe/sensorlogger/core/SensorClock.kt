package io.github.biofe.sensorlogger.core

import kotlin.math.abs

/**
 * Turns `SensorEvent.timestamp` into UTC milliseconds.
 *
 * Android documents sensor timestamps as elapsed-realtime nanoseconds, but on some phones they
 * drift away from that clock (e.g. they stop while the phone sleeps). Then every accelerometer,
 * gyroscope and barometer row lands outside the GPS time window and the privacy trim removes
 * them all. So each event is compared with the time it arrived: when the two disagree by more than
 * [MAX_LAG_MS], the difference becomes the sensor's correction, kept until it drifts again.
 */
class SensorClock(private val bootUtcMs: Long) {

    private var offsetMs = 0.0

    /** The largest correction applied so far, 0 if the sensor clock was fine. */
    var correctionMs = 0.0
        private set

    /** [eventNs] is `SensorEvent.timestamp`, [arrivalElapsedNs] `SystemClock.elapsedRealtimeNanos()` on delivery. */
    fun utcMs(eventNs: Long, arrivalElapsedNs: Long): Double {
        val lagMs = (arrivalElapsedNs - eventNs) / 1_000_000.0
        if (abs(lagMs - offsetMs) > MAX_LAG_MS) {
            offsetMs = if (abs(lagMs) > MAX_LAG_MS) lagMs else 0.0
            if (abs(offsetMs) > abs(correctionMs)) correctionMs = offsetMs
        }
        return bootUtcMs + eventNs / 1_000_000.0 + offsetMs
    }

    companion object {
        /** Sensor events normally arrive within milliseconds; a second apart means a different clock. */
        const val MAX_LAG_MS = 1000.0

        /**
         * For rides recorded before the fix: how far to move a sensor file's timestamps so its first
         * row lines up with the ride start. 0 if they already agree within [MAX_START_GAP_MS].
         * The first event arrives a few milliseconds after the start, so this is accurate to about that.
         */
        const val MAX_START_GAP_MS = 5000.0

        fun realignShiftMs(firstRowUtcMs: Double, startedUtcMs: Long): Double {
            val gap = firstRowUtcMs - startedUtcMs
            return if (abs(gap) > MAX_START_GAP_MS) -gap else 0.0
        }
    }
}
