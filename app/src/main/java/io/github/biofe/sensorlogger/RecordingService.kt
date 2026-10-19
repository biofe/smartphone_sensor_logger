package io.github.biofe.sensorlogger

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import io.github.biofe.sensorlogger.core.ThirdOctaveAnalyzer
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.util.Locale
import java.util.UUID

/**
 * Foreground service that records accelerometer (100 Hz), GPS (1 Hz) and sound levels
 * (1/3-octave bands every 125 ms) to CSV while the phone is in a pocket with the screen off.
 * Raw audio never leaves memory: each 125 ms frame is reduced to band levels and discarded.
 */
class RecordingService : Service(), SensorEventListener, LocationListener {

    private lateinit var rideDir: File
    private lateinit var sensorThread: HandlerThread
    private lateinit var sensorHandler: Handler
    private var wakeLock: PowerManager.WakeLock? = null
    private var accelWriter: BufferedWriter? = null
    private var gpsWriter: BufferedWriter? = null
    private var audioThread: Thread? = null
    @Volatile private var audioRunning = false
    private var audioSampleRate = 0
    private var audioSourceName = "none"
    private var accelSensorName = "none"

    /** UTC ms at elapsedRealtime == 0, so sensor timestamps (elapsed-realtime based) map to wall-clock time. */
    private var bootUtcMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (RecorderState.recording) return START_NOT_STICKY

        startInForeground()
        RecorderState.reset()
        RecorderState.recording = true
        bootUtcMs = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        rideDir = Rides.newRideDir(this)

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sensorlogger:ride").apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L)
        }

        sensorThread = HandlerThread("sensors").apply { start() }
        sensorHandler = Handler(sensorThread.looper)

        startAccelerometer()
        startGps()
        startAudio()
        writeMeta(stoppedAtMs = null)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (RecorderState.recording) {
            (getSystemService(Context.SENSOR_SERVICE) as SensorManager).unregisterListener(this)
            (getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(this)
            audioRunning = false
            audioThread?.join(2000)
            // Close writers on the thread that writes to them
            sensorHandler.post {
                accelWriter?.close()
                gpsWriter?.close()
                accelWriter = null
                gpsWriter = null
            }
            sensorThread.quitSafely()
            sensorThread.join(2000)
            writeMeta(stoppedAtMs = System.currentTimeMillis())
            wakeLock?.release()
            RecorderState.recording = false
        }
        super.onDestroy()
    }

    // --- Accelerometer ---------------------------------------------------------------------

    private fun startAccelerometer() {
        val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: run {
            RecorderState.error = "No accelerometer"
            return
        }
        accelSensorName = "${accel.vendor} ${accel.name}"
        accelWriter = File(rideDir, Rides.ACCEL_FILE).bufferedWriter().apply {
            write("t_utc_ms,ax,ay,az\n")
        }
        sm.registerListener(this, accel, ACCEL_PERIOD_US, sensorHandler)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val w = accelWriter ?: return
        val t = bootUtcMs + event.timestamp / 1_000_000.0
        w.write(String.format(Locale.US, "%.1f,%.4f,%.4f,%.4f\n", t, event.values[0], event.values[1], event.values[2]))
        RecorderState.accelSamples++
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    // --- GPS ---------------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun startGps() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            RecorderState.error = "Location permission missing"
            return
        }
        gpsWriter = File(rideDir, Rides.GPS_FILE).bufferedWriter().apply {
            write("t_utc_ms,gps_time_ms,lat,lon,alt_m,speed_mps,bearing_deg,accuracy_m\n")
        }
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, GPS_INTERVAL_MS, 0f, this, sensorThread.looper)
    }

    override fun onLocationChanged(location: Location) {
        val w = gpsWriter ?: return
        val t = bootUtcMs + location.elapsedRealtimeNanos / 1_000_000
        w.write(
            String.format(
                Locale.US, "%d,%d,%.7f,%.7f,%.1f,%.2f,%.1f,%.1f\n",
                t, location.time, location.latitude, location.longitude, location.altitude,
                location.speed, location.bearing, location.accuracy,
            )
        )
        w.flush()
        RecorderState.gpsFixes++
        RecorderState.lastGpsAccuracyM = location.accuracy
        RecorderState.lastSpeedKmh = location.speed * 3.6f
    }

    // --- Sound -------------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun startAudio() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            RecorderState.error = "Microphone permission missing"
            return
        }
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        // UNPROCESSED skips noise suppression and gain control, which would distort levels.
        // VOICE_RECOGNITION is the closest fallback on phones that don't support it.
        val unprocessed = am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val source = if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
        audioSourceName = if (unprocessed) "UNPROCESSED" else "VOICE_RECOGNITION"

        var record: AudioRecord? = null
        for (rate in intArrayOf(48_000, 44_100)) {
            val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuf <= 0) continue
            val candidate = AudioRecord(
                source, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, rate / 2 * 2), // ~0.5 s buffer
            )
            if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                record = candidate
                audioSampleRate = rate
                break
            }
            candidate.release()
        }
        val rec = record ?: run {
            RecorderState.error = "Microphone unavailable (another app may be using it)"
            return
        }

        val analyzer = ThirdOctaveAnalyzer(audioSampleRate)
        val writer = File(rideDir, Rides.SOUND_FILE).bufferedWriter().apply {
            write("t_utc_ms,laeq_dbfs," + analyzer.bandLabels.joinToString(",") { "band_${it}_dbfs" } + "\n")
        }
        audioRunning = true
        audioThread = Thread({
            val frame = ShortArray(analyzer.frameSamples)
            val frameMs = (analyzer.frameSamples * 1000L) / audioSampleRate
            rec.startRecording()
            try {
                while (audioRunning) {
                    var filled = 0
                    while (filled < frame.size && audioRunning) {
                        val n = rec.read(frame, filled, frame.size - filled)
                        if (n < 0) throw IllegalStateException("AudioRecord.read error $n")
                        filled += n
                    }
                    if (filled < frame.size) break
                    // Timestamp of the frame start (approximate, within the input buffer latency)
                    val t = bootUtcMs + SystemClock.elapsedRealtime() - frameMs
                    val result = analyzer.process(frame)
                    val sb = StringBuilder(256).append(t).append(',').append(fmt(result.laeqDb))
                    for (db in result.bandDb) sb.append(',').append(fmt(db))
                    writer.write(sb.append('\n').toString())
                    RecorderState.soundFrames++
                    RecorderState.lastLaeqDb = result.laeqDb
                }
            } catch (e: Exception) {
                RecorderState.error = "Sound recording stopped: ${e.message}"
            } finally {
                rec.stop()
                rec.release()
                writer.close()
            }
        }, "audio").apply { start() }
    }

    private fun fmt(db: Double) = String.format(Locale.US, "%.1f", db)

    // --- Metadata & notification -------------------------------------------------------------

    private fun writeMeta(stoppedAtMs: Long?) {
        val metaFile = File(rideDir, Rides.META_FILE)
        val rideId = if (metaFile.exists()) JSONObject(metaFile.readText()).getString("ride_id")
        else UUID.randomUUID().toString().take(8)
        val meta = JSONObject()
            .put("ride_id", rideId)
            .put("app_version", BuildConfigCompat.versionName(this))
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("android_sdk", Build.VERSION.SDK_INT)
            .put("started_utc_ms", RecorderState.startedAtMs)
            .put("stopped_utc_ms", stoppedAtMs ?: JSONObject.NULL)
            .put("accel_sensor", accelSensorName)
            .put("accel_period_us", ACCEL_PERIOD_US)
            .put("gps_interval_ms", GPS_INTERVAL_MS)
            .put("audio_source", audioSourceName)
            .put("audio_sample_rate", audioSampleRate)
            .put("sound_frame_ms", 125)
            .put("sound_level_unit", "dBFS, uncalibrated; 0 dB = mean square of a full-scale signal")
            .put("sound_bands_hz", JSONArray(ThirdOctaveAnalyzer(48_000).bandLabels))
        metaFile.writeText(meta.toString(2))
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Ride recording", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Recording ride")
            .setContentText("GPS, accelerometer and sound levels")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        startForeground(NOTIFICATION_ID, notification, type)
    }

    companion object {
        const val ACTION_STOP = "io.github.biofe.sensorlogger.STOP"
        private const val CHANNEL_ID = "ride"
        private const val NOTIFICATION_ID = 1
        private const val ACCEL_PERIOD_US = 10_000 // 100 Hz
        private const val GPS_INTERVAL_MS = 1000L
    }
}

private object BuildConfigCompat {
    fun versionName(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
}
