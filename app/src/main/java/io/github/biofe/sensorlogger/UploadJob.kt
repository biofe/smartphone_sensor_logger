package io.github.biofe.sensorlogger

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context

/**
 * Automatic upload on Wi-Fi. Android runs the job when an unmetered network is there, retries it
 * with back-off when the server couldn't be reached, and keeps it scheduled across reboots.
 */
class UploadJob : JobService() {

    @Volatile private var worker: Thread? = null

    override fun onStartJob(params: JobParameters): Boolean {
        if (!Upload.auto(this)) return false
        worker = Thread {
            val summary = runCatching { Upload.uploadAll(this) }.getOrNull()
            jobFinished(params, summary?.retry ?: true)
        }.apply { start() }
        return true
    }

    /** Wi-Fi went away mid-upload: the ride in flight stays "waiting" and is sent next time. */
    override fun onStopJob(params: JobParameters): Boolean = true

    companion object {
        private const val SOON = 1
        private const val PERIODIC = 2

        /** Call after a ride ends and whenever the upload settings change. */
        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (!Upload.auto(context)) {
                scheduler.cancel(SOON)
                scheduler.cancel(PERIODIC)
                return
            }
            val service = ComponentName(context, UploadJob::class.java)
            scheduler.schedule(
                JobInfo.Builder(SOON, service)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                    .setBackoffCriteria(10 * 60 * 1000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                    .setPersisted(true)
                    .build()
            )
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
    }
}
