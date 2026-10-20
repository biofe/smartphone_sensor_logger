package io.github.biofe.sensorlogger

/** Live counters the UI polls while a ride is recording. */
object RecorderState {
    @Volatile var recording = false
    @Volatile var startedAtMs = 0L
    @Volatile var accelSamples = 0L
    @Volatile var gpsFixes = 0L
    @Volatile var soundFrames = 0L
    @Volatile var lastGpsAccuracyM = Float.NaN
    @Volatile var lastSpeedKmh = Float.NaN
    @Volatile var lastLaeqDb = Double.NaN
    @Volatile var error: String? = null
    /** The last ride ended because the rider stopped moving, not by tapping Stop. */
    @Volatile var autoStopped = false

    fun reset() {
        startedAtMs = System.currentTimeMillis()
        accelSamples = 0
        gpsFixes = 0
        soundFrames = 0
        lastGpsAccuracyM = Float.NaN
        lastSpeedKmh = Float.NaN
        lastLaeqDb = Double.NaN
        error = null
        autoStopped = false
    }
}
