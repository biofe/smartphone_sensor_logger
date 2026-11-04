package io.github.biofe.sensorlogger

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle

/**
 * Uploads in the background, so they carry on with the app closed.
 *
 * - Automatic upload on Wi-Fi: Android runs the job when an unmetered network is there, retries it
 *   with back-off when the server couldn't be reached, and keeps it scheduled across reboots.
 * - "Upload now": started from the app on any network, with a progress notification. On Android 14+
 *   it is a user-initiated data transfer, which Android lets run as long as it needs.
 */
class UploadJob : JobService() {

    @Volatile private var worker: Thread? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val now = params.jobId == NOW
        val job = jobName(params.jobId)
        if (if (now) !Upload.consented(this) else !Upload.auto(this)) {
            UploadLog.add(this, "$job: started, but upload is off")
            return false
        }
        UploadLog.add(this, "$job: started on ${UploadLog.network(this, params.network)}")
        if (now) {
            notify(params, getString(R.string.preparing), 0, 0)
            // An automatic upload may be running (or stuck in a frozen app): let it stop after its ride, then take over
            Upload.requestStop()
        }
        val only = params.extras.getStringArray(EXTRA_ONLY)?.toSet()
        worker = Thread {
            val summary = runCatching {
                Upload.uploadAll(this, waitMs = if (now) TAKE_OVER_WAIT_MS else 0, only = only) { done, total ->
                    if (now && done < total) notify(params, getString(R.string.upload_progress, done + 1, total), done, total)
                }
            }.onFailure {
                UploadLog.add(this, "$job: failed: ${it.javaClass.simpleName} ${it.message.orEmpty()}")
            }.getOrNull()
            if (summary != null) {
                val parts = buildList {
                    add("${summary.uploaded} uploaded")
                    if (summary.rejected > 0) add("${summary.rejected} not accepted")
                    summary.message?.let { add(it) }
                    if (summary.retry) add("will retry")
                }
                UploadLog.add(this, "$job: done: ${parts.joinToString(", ")}")
            }
            if (now) notifyDone(params, summary)
            jobFinished(params, summary?.retry ?: true)
        }.apply { start() }
        return true
    }

    /** Android stopped the job (e.g. the network went away): finish the ride in flight, the rest waits for next time. */
    override fun onStopJob(params: JobParameters): Boolean {
        val why = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) " (${UploadLog.stopReason(params.stopReason)})" else ""
        UploadLog.add(this, "${jobName(params.jobId)}: stopped by Android$why")
        Upload.requestStop()
        return true
    }

    private fun notification(text: String, done: Int, total: Int, ongoing: Boolean): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, getString(R.string.channel_upload), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(if (ongoing) android.R.drawable.stat_sys_upload else android.R.drawable.stat_sys_upload_done)
            .setContentTitle(getString(if (ongoing) R.string.uploading_rides else R.string.upload_finished))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .apply { if (ongoing) setProgress(total, done, total == 0) }
            .build()
    }

    private fun notify(params: JobParameters, text: String, done: Int, total: Int) {
        val n = notification(text, done, total, ongoing = true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && params.isUserInitiatedJob) {
            setNotification(params, NOTIFICATION_ID, n, JOB_END_NOTIFICATION_POLICY_DETACH)
        } else {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, n)
        }
    }

    private fun notifyDone(params: JobParameters, summary: Upload.Summary?) {
        val text = when {
            summary == null -> getString(R.string.upload_failed_retry)
            else -> buildList {
                if (summary.uploaded > 0) add(resources.getQuantityString(R.plurals.uploaded_n, summary.uploaded, summary.uploaded))
                if (summary.rejected > 0) add(resources.getQuantityString(R.plurals.rejected_n, summary.rejected, summary.rejected))
                summary.message?.let { add(it) }
            }.joinToString(". ").ifEmpty { getString(R.string.nothing_new_to_upload) }
        }
        val n = notification(text, 0, 0, ongoing = false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && params.isUserInitiatedJob) {
            setNotification(params, NOTIFICATION_ID, n, JOB_END_NOTIFICATION_POLICY_DETACH)
        }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, n)
    }

    companion object {
        private const val SOON = 1
        private const val PERIODIC = 2
        private const val NOW = 3
        private const val CHANNEL_ID = "upload"
        private const val EXTRA_ONLY = "only"
        private const val NOTIFICATION_ID = 2

        /** One ride takes seconds on Wi-Fi; two minutes cover a slow connection. */
        private const val TAKE_OVER_WAIT_MS = 2 * 60 * 1000L

        /** The jobs that upload without the rider asking, with their names in the upload log. */
        val AUTOMATIC_JOBS = listOf(SOON to "Upload on Wi-Fi", PERIODIC to "6-hour check")

        private fun jobName(id: Int) = when (id) {
            SOON -> "Upload on Wi-Fi"
            PERIODIC -> "6-hour check"
            else -> "Upload now"
        }

        /** Call after a ride ends and whenever the upload settings change; [why] goes into the upload log. */
        fun schedule(context: Context, why: String? = null) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (!Upload.auto(context)) {
                scheduler.cancel(SOON)
                scheduler.cancel(PERIODIC)
                return
            }
            val service = ComponentName(context, UploadJob::class.java)
            // Scheduling an id again would stop that job if it's running, so leave a pending or running one alone
            if (scheduler.getPendingJob(SOON) == null) {
                val result = scheduler.schedule(
                    JobInfo.Builder(SOON, service)
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                        .setBackoffCriteria(10 * 60 * 1000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                        .setPersisted(true)
                        .build()
                )
                if (why != null || result != JobScheduler.RESULT_SUCCESS) {
                    val outcome = if (result == JobScheduler.RESULT_SUCCESS) "scheduled" else "Android refused to schedule it"
                    UploadLog.add(context, "${why ?: "Upload on Wi-Fi"}: $outcome")
                }
            } else if (why != null) {
                UploadLog.add(context, "$why: upload on Wi-Fi already waiting")
            }
            // A safety net in case a run was cut short and its retry got lost
            if (scheduler.getPendingJob(PERIODIC) == null) {
                scheduler.schedule(
                    JobInfo.Builder(PERIODIC, service)
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                        .setPeriodic(6 * 60 * 60 * 1000L)
                        .setPersisted(true)
                        .build()
                )
            }
        }

        /**
         * "Upload now": runs at once on any network and keeps going with the app closed. Call while
         * the app is open. [only]: the ride folders the rider picked; null uploads everything waiting.
         */
        fun uploadNow(context: Context, only: List<String>? = null) {
            val builder = JobInfo.Builder(NOW, ComponentName(context, UploadJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setExtras(PersistableBundle().apply { if (only != null) putStringArray(EXTRA_ONLY, only.toTypedArray()) })
                .setBackoffCriteria(30 * 1000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            val scheduler = context.getSystemService(JobScheduler::class.java)
            // Tapped again while it runs: scheduling the same id would stop it
            if (scheduler.getPendingJob(NOW) != null) return
            // Best kind of job first; fall back if this Android version or state doesn't allow it
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                runCatching { scheduler.schedule(builder.setUserInitiated(true).build()) }.isSuccess
            ) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) builder.setUserInitiated(false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                runCatching { scheduler.schedule(builder.setExpedited(true).build()) }.isSuccess
            ) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) builder.setExpedited(false)
            scheduler.schedule(builder.build())
        }
    }
}
