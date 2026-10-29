package io.github.biofe.sensorlogger

/** Live counters the UI polls while a ride is recording. */
object RecorderState {
    @Volatile var recording = false
    @Volatile var rideDir: java.io.File? = null
    @Volatile var startedAtMs = 0L
    @Volatile var accelSamples = 0L
    @Volatile var gyroSamples = 0L
    @Volatile var pressureSamples = 0L
    @Volatile var gpsFixes = 0L
    @Volatile var annotations = 0
    /** Last GPS fix, for annotations */
    @Volatile var lastFix: DoubleArray? = null
    @Volatile var soundFrames = 0L
    @Volatile var lastGpsAccuracyM = Float.NaN
    @Volatile var lastSpeedKmh = Float.NaN
    @Volatile var lastLaeqDb = Double.NaN
    /** Distance ridden so far, for the recording screen */
    @Volatile var distanceM = 0.0
    /** Phones without a barometer still record; the screen just doesn't expect pressure data */
    @Volatile var hasBarometer = false
    @Volatile var error: String? = null
    /** The last ride ended because the rider stopped moving, not by tapping Stop. */
    @Volatile var autoStopped = false

    fun reset() {
        startedAtMs = System.currentTimeMillis()
        accelSamples = 0
        gyroSamples = 0
        pressureSamples = 0
        gpsFixes = 0
        annotations = 0
        lastFix = null
        soundFrames = 0
        lastGpsAccuracyM = Float.NaN
        lastSpeedKmh = Float.NaN
        lastLaeqDb = Double.NaN
        distanceM = 0.0
        hasBarometer = false
        error = null
        autoStopped = false
    }
}
