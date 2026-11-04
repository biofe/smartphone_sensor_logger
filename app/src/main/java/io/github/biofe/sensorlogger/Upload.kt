package io.github.biofe.sensorlogger

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * Sends finished rides to the project server, trimmed exactly like the ZIP export.
 *
 * The phone identifies itself with a random install id and a secret it makes on first use; the
 * server keeps only a hash of the secret, so no password is needed in the app. Each ride folder
 * gets an `upload.json` with its state, so a ride is never sent twice once the server has it.
 * Poll taps added after the upload (annotating later) are sent on their own.
 */
object Upload {

    const val SERVER = "https://upload.velowind.de"

    private const val PREFS = "upload"
    private const val STATE_FILE = "upload.json"
    private const val LABELS_MTIME = "labels_mtime"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 120_000

    /** The server's limit; a valid ride stays far below it (about 10 MB per hour). */
    private const val MAX_RIDE_BYTES = 100L * 1024 * 1024

    enum class State { WAITING, UPLOADED, REJECTED, EMPTY }

    /** [appVersion]: the app version that set the state; a rejection is retried once the app is updated. */
    class Status(val state: State, val reason: String = "", val appVersion: String = "")

    /** What a run achieved; [retry] means something is left that a later run may manage. */
    class Summary(val uploaded: Int, val rejected: Int, val retry: Boolean, val message: String?)

    private val lock = ReentrantLock()
    @Volatile private var stopRequested = false

    val isRunning: Boolean get() = lock.isLocked

    /** Asks a running upload to stop after the ride in flight (Android stopped its job, or "Upload now" takes over). */
    fun requestStop() {
        stopRequested = true
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // --- Settings ----------------------------------------------------------------------------

    fun consented(context: Context) = prefs(context).getBoolean("consent", false)

    /** Whether the consent question has been answered at all (yes or "not now"). */
    fun consentAsked(context: Context) = prefs(context).getBoolean("consent_asked", false)

    fun setConsent(context: Context, agreed: Boolean) {
        prefs(context).edit().putBoolean("consent", agreed).putBoolean("consent_asked", true)
            .putBoolean("auto", agreed).apply()
        UploadJob.schedule(context)
    }

    /** Automatic upload on Wi-Fi; on by default once the rider has agreed. */
    fun auto(context: Context) = consented(context) && prefs(context).getBoolean("auto", true)

    fun setAuto(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("auto", on).apply()
        UploadJob.schedule(context)
    }

    fun installId(context: Context): String {
        ensureIdentity(context)
        return prefs(context).getString("install_id", null)!!
    }

    /** The first 8 characters, which the server's admin commands accept too. */
    fun shortInstallId(context: Context) = installId(context).take(8)

    @Synchronized
    private fun ensureIdentity(context: Context) {
        val p = prefs(context)
        if (p.getString("install_id", null) != null) return
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val secret = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        p.edit().putString("install_id", UUID.randomUUID().toString()).putString("install_secret", secret).commit()
    }

    private fun secret(context: Context): String {
        ensureIdentity(context)
        return prefs(context).getString("install_secret", null)!!
    }

    // --- Ride states -------------------------------------------------------------------------

    fun status(ride: File): Status {
        val file = File(ride, STATE_FILE)
        if (!file.exists()) return Status(State.WAITING)
        return runCatching {
            val json = JSONObject(file.readText())
            Status(State.valueOf(json.getString("state")), json.optString("reason", ""), json.optString("app_version", ""))
        }.getOrDefault(Status(State.WAITING))
    }

    private fun setStatus(context: Context, ride: File, state: State, reason: String = "", name: String? = null, labelsMtime: Long? = null) {
        val json = JSONObject().put("state", state.name).put("utc_ms", System.currentTimeMillis())
            .put("app_version", versionName(context))
        if (reason.isNotEmpty()) json.put("reason", reason)
        if (name != null) json.put("name", name)
        if (labelsMtime != null) json.put(LABELS_MTIME, labelsMtime)
        File(ride, STATE_FILE).writeText(json.toString())
    }

    /** labels.csv's time stamp when the server last got it; 0 if it had none. */
    private fun labelsSent(ride: File): Long =
        runCatching { JSONObject(File(ride, STATE_FILE).readText()).optLong(LABELS_MTIME, 0L) }.getOrDefault(0L)

    private fun markLabelsSent(ride: File, mtime: Long) {
        val file = File(ride, STATE_FILE)
        runCatching { file.writeText(JSONObject(file.readText()).put(LABELS_MTIME, mtime).toString()) }
    }

    private fun labelsMtime(ride: File): Long = File(ride, Labels.FILE).let { if (it.exists()) it.lastModified() else 0L }

    /** An uploaded ride the rider annotated afterwards. */
    fun labelsPending(ride: File): Boolean =
        status(ride).state == State.UPLOADED && labelsMtime(ride) != labelsSent(ride)

    private fun isRecording(ride: File) = RecorderState.recording && RecorderState.rideDir == ride

    /**
     * Waiting rides, plus rides refused under an older app version: an update may have fixed the
     * cause. [only] limits it to the rides the rider picked.
     */
    fun pending(context: Context, only: Set<String>? = null): List<File> {
        val version = versionName(context)
        return Rides.list(context).filter {
            val s = status(it)
            (only == null || it.name in only) &&
                !isRecording(it) && (s.state == State.WAITING || s.state == State.REJECTED && s.appVersion != version)
        }
    }

    private fun versionName(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"

    // --- Uploading ---------------------------------------------------------------------------

    /**
     * Uploads every waiting ride (or those of them named in [only]), then poll taps added to
     * uploaded rides. Runs on a background thread, one run at a time: a second call waits up to
     * [waitMs] for the first to finish.
     */
    fun uploadAll(context: Context, waitMs: Long = 0, only: Set<String>? = null, progress: (Int, Int) -> Unit = { _, _ -> }): Summary {
        if (!consented(context)) return Summary(0, 0, false, context.getString(R.string.upload_needs_consent))
        if (!lock.tryLock(waitMs, TimeUnit.MILLISECONDS)) return Summary(0, 0, true, context.getString(R.string.upload_already_running))
        stopRequested = false
        try {
            return run(context, only, progress)
        } finally {
            lock.unlock()
        }
    }

    private fun run(context: Context, only: Set<String>?, progress: (Int, Int) -> Unit): Summary {
        checkServerVersion(context)?.let { return Summary(0, 0, false, it) }
        val rides = pending(context, only)
        var uploaded = 0
        var rejected = 0
        rides.forEachIndexed { i, ride ->
            if (stopRequested) return Summary(uploaded, rejected, true, null)
            progress(i, rides.size)
            val zip = File(context.cacheDir, "upload-${ride.name}.zip")
            // Taps added while this upload runs are newer than this and get sent afterwards
            val labelsMtime = labelsMtime(ride)
            try {
                val name = runCatching { Exporter.packRide(ride, zip) }.getOrElse {
                    setStatus(context, ride, State.REJECTED, "can't be read on the phone")
                    rejected++
                    return@forEachIndexed
                }
                if (name == null) {
                    setStatus(context, ride, State.EMPTY)
                    return@forEachIndexed
                }
                if (zip.length() > MAX_RIDE_BYTES) {
                    setStatus(context, ride, State.REJECTED, "too large to upload", name)
                    rejected++
                    return@forEachIndexed
                }
                val answer = try {
                    send(context, "POST", "/v1/rides/$name", zip.readBytes(), "application/zip")
                } catch (e: IOException) {
                    return Summary(uploaded, rejected, true, context.getString(R.string.upload_no_connection))
                }
                when (answer.code) {
                    200, 201 -> {
                        setStatus(context, ride, State.UPLOADED, name = name, labelsMtime = labelsMtime)
                        uploaded++
                    }
                    400, 413 -> {
                        setStatus(context, ride, State.REJECTED, answer.reason, name)
                        rejected++
                    }
                    403 -> {
                        setStatus(context, ride, State.REJECTED, answer.reason, name)
                        return Summary(uploaded, rejected + 1, false, context.getString(R.string.upload_phone_blocked))
                    }
                    // 401, 422 (damaged transfer), 429, 5xx: the same ride can succeed later
                    else -> return Summary(uploaded, rejected, true, answer.reason.ifEmpty { context.getString(R.string.upload_server_busy) })
                }
            } finally {
                zip.delete()
            }
        }
        progress(rides.size, rides.size)
        val labelsOk = uploadLabels(context, only)
        return Summary(uploaded, rejected, !labelsOk, null)
    }

    /** Sends the poll taps of rides annotated after their upload. Returns false if some should be sent again later. */
    private fun uploadLabels(context: Context, only: Set<String>?): Boolean {
        for (ride in Rides.list(context)) {
            if (only != null && ride.name !in only || !labelsPending(ride)) continue
            if (stopRequested) return false
            val mtime = labelsMtime(ride)
            val trimmed = Exporter.trimmedLabels(ride)
            if (trimmed == null) {
                markLabelsSent(ride, mtime) // nothing inside the privacy window
                continue
            }
            val (name, body) = trimmed
            val answer = try {
                send(context, "PUT", "/v1/rides/$name/labels", body, "text/csv")
            } catch (e: IOException) {
                return false
            }
            // Stored, or refused for good (e.g. the server lost the ride): either way don't send this version again
            if (answer.code == 200 || answer.code in setOf(400, 403, 404, 413)) markLabelsSent(ride, mtime)
            else return false
        }
        return true
    }

    /** A message if the server needs a newer app, else null. Unreachable server: carry on, the upload will tell. */
    private fun checkServerVersion(context: Context): String? {
        val min = runCatching {
            val c = URL("$SERVER/v1/health").openConnection() as HttpURLConnection
            c.connectTimeout = CONNECT_TIMEOUT_MS
            c.readTimeout = CONNECT_TIMEOUT_MS
            try {
                JSONObject(c.inputStream.bufferedReader().readText()).optString("min_app_version", "")
            } finally {
                c.disconnect()
            }
        }.getOrNull() ?: return null
        val mine = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: return null
        return if (compareVersions(mine, min) < 0) context.getString(R.string.upload_update_app, min) else null
    }

    fun compareVersions(a: String, b: String): Int {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }

    // --- Deleting ----------------------------------------------------------------------------

    /** Asks the server to delete everything this phone sent; then stops uploading. Throws on failure. */
    fun deleteOnServer(context: Context): Int {
        val answer = send(context, "DELETE", "/v1/install", null, null)
        if (answer.code != 200) throw IOException(answer.reason.ifEmpty { context.getString(R.string.server_answered, answer.code) })
        prefs(context).edit().putBoolean("consent", false).putBoolean("auto", false).apply()
        UploadJob.schedule(context)
        return answer.json?.optInt("rides", 0) ?: 0
    }

    // --- HTTP --------------------------------------------------------------------------------

    private class Answer(val code: Int, val json: JSONObject?) {
        val reason: String get() = json?.optString("reason", "") ?: ""
    }

    private fun send(context: Context, method: String, path: String, body: ByteArray?, type: String?): Answer {
        val c = URL(SERVER + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = CONNECT_TIMEOUT_MS
            c.readTimeout = READ_TIMEOUT_MS
            c.setRequestProperty("X-Install-Id", installId(context))
            c.setRequestProperty("Authorization", "Bearer ${secret(context)}")
            c.setRequestProperty("X-App-Version", context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", type)
                c.setRequestProperty("X-Content-SHA256", sha256(body))
                c.setFixedLengthStreamingMode(body.size)
                c.outputStream.use { it.write(body) }
            }
            val code = c.responseCode
            val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            return Answer(code, runCatching { JSONObject(text) }.getOrNull())
        } finally {
            c.disconnect()
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
