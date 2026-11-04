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

/** Looks for a new app version once a day and posts a notification when there is one. */
class UpdateJob : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            val info = runCatching { Updater.check(this) }
            info.getOrNull()?.let { if (Updater.firstNotice(this, it)) notifyAvailable(this, it) }
            jobFinished(params, info.isFailure)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters) = true

    companion object {
        private const val JOB_ID = 4
        private const val CHANNEL_ID = "update"
        private const val NOTIFICATION_ID = 3
        const val EXTRA_SHOW_UPDATE = "show_update"

        /** Call when the app opens; leaves an already scheduled job alone. */
        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (!Updater.enabled(context)) {
                scheduler.cancel(JOB_ID)
                return
            }
            if (scheduler.getPendingJob(JOB_ID) != null) return
            scheduler.schedule(
                JobInfo.Builder(JOB_ID, ComponentName(context, UpdateJob::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(24 * 60 * 60 * 1000L)
                    .setPersisted(true)
                    .build()
            )
        }

        fun notifyAvailable(context: Context, info: Updater.Info) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, context.getString(R.string.channel_updates), NotificationManager.IMPORTANCE_DEFAULT))
            val open = PendingIntent.getActivity(
                context, 1,
                Intent(context, MainActivity::class.java).putExtra(EXTRA_SHOW_UPDATE, true)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(context.getString(R.string.update_available))
                .setContentText(context.getString(R.string.update_available_text, info.versionName))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIFICATION_ID, n)
        }
    }
}
