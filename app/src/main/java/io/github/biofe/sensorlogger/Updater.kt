package io.github.biofe.sensorlogger

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Updates the app from the project server. A daily job (and opening the app) reads a small
 * `latest.json`; when it names a newer version, the rider gets a notification and a dialog.
 * Installing downloads the APK, checks its SHA-256, and hands it to Android's installer, which
 * asks the rider to confirm. Android only accepts it if it is signed with the same key as the
 * installed app, so a file swapped on the server can't replace the app.
 *
 * Only release builds update themselves: test builds (".dev") are signed with another key.
 */
object Updater {

    const val LATEST = "https://apk.velowind.de/latest.json"

    private const val PREFS = "update"
    private const val TIMEOUT_MS = 15_000
    /** Opening the app checks again if the last check is older than this. */
    private const val OPEN_CHECK_AFTER_MS = 6 * 60 * 60 * 1000L
    private val APK_NAME = Regex("[A-Za-z0-9._-]+\\.apk")

    /** The rider was sent to allow installs; the app carries on with the install when they come back. */
    @Volatile var awaitingPermission = false

    class Info(val versionCode: Long, val versionName: String, val apk: String, val sha256: String, val notes: String)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(context: Context) = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0

    private fun installedCode(context: Context): Long =
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode

    /** The newer version the last check found, or null. */
    fun available(context: Context): Info? {
        if (!enabled(context)) return null
        val json = prefs(context).getString("latest", null) ?: return null
        val info = runCatching { parse(JSONObject(json)) }.getOrNull() ?: return null
        return if (info.versionCode > installedCode(context)) info else null
    }

    private fun parse(json: JSONObject): Info {
        val info = Info(
            json.getLong("versionCode"), json.getString("versionName"), json.getString("apk"),
            json.getString("sha256").lowercase(), json.optString("notes", ""),
        )
        require(APK_NAME.matches(info.apk) && info.sha256.matches(Regex("[0-9a-f]{64}")))
        return info
    }

    /** Reads latest.json; returns the newer version if there is one. Network work: not on the main thread. */
    fun check(context: Context): Info? {
        if (!enabled(context)) return null
        val c = URL(LATEST).openConnection() as HttpURLConnection
        val text = try {
            c.connectTimeout = TIMEOUT_MS
            c.readTimeout = TIMEOUT_MS
            c.useCaches = false
            if (c.responseCode != 200) throw IOException("Update server answered ${c.responseCode}")
            c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
        val json = JSONObject(text)
        parse(json)
        prefs(context).edit().putString("latest", json.toString()).putLong("checked_ms", System.currentTimeMillis()).apply()
        return available(context)
    }

    fun checkDue(context: Context) =
        enabled(context) && System.currentTimeMillis() - prefs(context).getLong("checked_ms", 0) > OPEN_CHECK_AFTER_MS

    /** True once per version, so the notification isn't repeated every day. */
    fun firstNotice(context: Context, info: Info): Boolean {
        if (prefs(context).getLong("notified_code", 0) >= info.versionCode) return false
        prefs(context).edit().putLong("notified_code", info.versionCode).apply()
        return true
    }

    // --- Installing --------------------------------------------------------------------------

    /** Shows what's new and installs on request. */
    fun offer(activity: Activity, info: Info) {
        val notes = if (info.notes.isBlank()) "" else "\n\n${info.notes}"
        AlertDialog.Builder(activity)
            .setTitle("Update to ${info.versionName}")
            .setMessage("A new version of the app is available. Your rides and settings are kept.$notes")
            .setPositiveButton("Install") { _, _ -> install(activity, info) }
            .setNegativeButton("Later", null)
            .show()
    }

    fun install(activity: Activity, info: Info) {
        if (RecorderState.recording) {
            // Installing restarts the app, which would end the ride
            Toast.makeText(activity, "Finish your ride first; updating restarts the app.", Toast.LENGTH_LONG).show()
            return
        }
        if (!activity.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(activity)
                .setMessage("Android asks once whether this app may install updates. Turn on \"Allow from this source\", then come back; the update carries on.")
                .setPositiveButton("Open settings") { _, _ ->
                    awaitingPermission = true
                    activity.startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
                    )
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        val text = TextView(activity).apply {
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            this.text = "Downloading…"
        }
        val dialog = AlertDialog.Builder(activity).setTitle("Update to ${info.versionName}").setView(text)
            .setCancelable(false).show()
        val ui = Handler(Looper.getMainLooper())
        Thread {
            val result = runCatching {
                val apk = download(activity, info) { percent -> ui.post { text.text = "Downloading… $percent %" } }
                ui.post { text.text = "Starting the installer…" }
                startInstaller(activity, apk)
            }
            ui.post {
                dialog.dismiss()
                result.exceptionOrNull()?.let {
                    Toast.makeText(activity, "Update failed: ${it.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun download(context: Context, info: Info, progress: (Int) -> Unit): File {
        val dir = File(context.cacheDir, "update").apply { deleteRecursively(); mkdirs() }
        val target = File(dir, info.apk)
        val digest = MessageDigest.getInstance("SHA-256")
        val c = URL(URL(LATEST), info.apk).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = TIMEOUT_MS
            c.readTimeout = 60_000
            if (c.responseCode != 200) throw IOException("server answered ${c.responseCode}")
            val total = c.contentLengthLong
            var done = 0L
            var shown = -1
            c.inputStream.use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        done += n
                        val percent = if (total > 0) (done * 100 / total).toInt() else 0
                        if (percent != shown) {
                            shown = percent
                            progress(percent)
                        }
                    }
                }
            }
        } finally {
            c.disconnect()
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        if (hash != info.sha256) throw IOException("the download is damaged; try again")
        val archive = context.packageManager.getPackageArchiveInfo(target.path, 0)
            ?: throw IOException("the download isn't an app")
        if (archive.packageName != context.packageName || archive.longVersionCode != info.versionCode) {
            throw IOException("the download is a different app or version")
        }
        return target
    }

    private fun startInstaller(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("app.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            // The installer adds its status to this intent, so it has to be mutable
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val status = PendingIntent.getBroadcast(context, 0, Intent(context, UpdateReceiver::class.java), flags)
            session.commit(status.intentSender)
        }
    }
}

/** Hears back from Android's installer: shows its confirmation screen, or why it failed. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirm?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            PackageInstaller.STATUS_SUCCESS -> File(context.cacheDir, "update").deleteRecursively()
            PackageInstaller.STATUS_FAILURE_ABORTED -> Unit // the rider tapped Cancel
            else -> {
                val why = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown reason"
                Toast.makeText(context, "Update not installed: $why", Toast.LENGTH_LONG).show()
            }
        }
    }
}
