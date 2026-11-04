package io.github.biofe.sensorlogger

import android.app.job.JobScheduler
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A short diary of the background upload: when Android scheduled, started and stopped the jobs,
 * on which network, and what came of it. Shown under Settings → Upload log, so a rider can tell
 * (and copy) why a ride is still waiting. Stays on the phone; kept to the last [MAX_LINES] lines.
 */
object UploadLog {

    private const val FILE = "upload_log.txt"
    private const val MAX_LINES = 150

    private val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    @Synchronized
    fun add(context: Context, line: String) {
        val file = File(context.filesDir, FILE)
        val lines = runCatching { file.readLines() }.getOrDefault(emptyList()) + "${time.format(Date())}  $line"
        runCatching { file.writeText(lines.takeLast(MAX_LINES).joinToString("\n", postfix = "\n")) }
    }

    /** Newest first, with the current state of the jobs and of the phone on top. */
    fun text(context: Context): String = buildString {
        appendLine(state(context))
        appendLine()
        val lines = runCatching { File(context.filesDir, FILE).readLines() }.getOrDefault(emptyList())
        if (lines.isEmpty()) appendLine("(nothing logged yet)") else lines.asReversed().forEach { appendLine(it) }
    }

    private fun state(context: Context): String = buildString {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        append("Now: ").append(network(context, null))
        val power = context.getSystemService(PowerManager::class.java)
        append(if (power.isIgnoringBatteryOptimizations(context.packageName)) ", battery unrestricted" else ", battery optimized")
        if (power.isDeviceIdleMode) append(", phone in Doze")
        for ((id, name) in UploadJob.AUTOMATIC_JOBS) {
            append("\n").append(name).append(": ")
            if (scheduler.getPendingJob(id) == null) {
                append("not scheduled")
            } else {
                append("waiting")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    append(" (").append(pendingReason(scheduler.getPendingJobReason(id))).append(")")
                }
            }
        }
    }

    /** "Wi-Fi, unmetered, internet ok"; [network] null means the phone's default network. */
    fun network(context: Context, network: Network?): String {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val n = network ?: cm.activeNetwork ?: return "no network"
        val caps = cm.getNetworkCapabilities(n) ?: return "no network"
        val kind = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "other network"
        }
        val metered = if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) "unmetered" else "metered"
        val internet = if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) "internet ok" else "internet not confirmed"
        return "$kind, $metered, $internet"
    }

    private fun pendingReason(reason: Int): String = when (reason) {
        JobScheduler.PENDING_JOB_REASON_CONSTRAINT_CONNECTIVITY -> "needs Wi-Fi"
        JobScheduler.PENDING_JOB_REASON_DEVICE_STATE -> "phone asleep or busy"
        JobScheduler.PENDING_JOB_REASON_APP_STANDBY -> "app not used recently"
        JobScheduler.PENDING_JOB_REASON_BACKGROUND_RESTRICTION -> "background use restricted"
        JobScheduler.PENDING_JOB_REASON_QUOTA -> "Android's job quota used up"
        JobScheduler.PENDING_JOB_REASON_JOB_SCHEDULER_OPTIMIZATION -> "Android is batching jobs"
        JobScheduler.PENDING_JOB_REASON_CONSTRAINT_MINIMUM_LATENCY -> "waiting to retry"
        JobScheduler.PENDING_JOB_REASON_EXECUTING -> "running"
        JobScheduler.PENDING_JOB_REASON_USER -> "stopped by the user"
        JobScheduler.PENDING_JOB_REASON_UNDEFINED -> "no reason given"
        else -> "reason $reason"
    }

    fun stopReason(reason: Int): String = when (reason) {
        android.app.job.JobParameters.STOP_REASON_CONSTRAINT_CONNECTIVITY -> "Wi-Fi lost"
        android.app.job.JobParameters.STOP_REASON_DEVICE_STATE -> "phone state changed"
        android.app.job.JobParameters.STOP_REASON_APP_STANDBY -> "app standby"
        android.app.job.JobParameters.STOP_REASON_BACKGROUND_RESTRICTION -> "background use restricted"
        android.app.job.JobParameters.STOP_REASON_QUOTA -> "job quota used up"
        android.app.job.JobParameters.STOP_REASON_TIMEOUT -> "ran too long"
        android.app.job.JobParameters.STOP_REASON_CANCELLED_BY_APP -> "cancelled by the app"
        android.app.job.JobParameters.STOP_REASON_PREEMPT -> "made way for another job"
        android.app.job.JobParameters.STOP_REASON_SYSTEM_PROCESSING -> "system busy"
        android.app.job.JobParameters.STOP_REASON_USER -> "stopped by the user"
        else -> "reason $reason"
    }
}
