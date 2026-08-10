package com.johnc4rl0.smsforwarder.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.johnc4rl0.smsforwarder.telephony.RetryPolicy
import java.util.concurrent.TimeUnit

/**
 * Enqueues expedited processing, delayed retries, callback timeouts, and periodic cleanup/health.
 */
class ForwardWorkScheduler {

    fun enqueueProcessExpedited(context: Context) {
        val request = OneTimeWorkRequestBuilder<ProcessForwardJobsWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(TAG_PROCESS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_PROCESS,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
    }

    fun enqueueProcessDelayed(context: Context, delayMs: Long) {
        val request = OneTimeWorkRequestBuilder<ProcessForwardJobsWorker>()
            .setInitialDelay(delayMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            .addTag(TAG_PROCESS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_PROCESS_DELAYED,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
    }

    fun scheduleCallbackTimeout(context: Context, jobId: String) {
        val request = OneTimeWorkRequestBuilder<CallbackTimeoutWorker>()
            .setInitialDelay(RetryPolicy.CALLBACK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(CallbackTimeoutWorker.KEY_JOB_ID to jobId))
            .addTag(TAG_CALLBACK_TIMEOUT)
            .addTag(tagForJob(jobId))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            uniqueCallbackTimeout(jobId),
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun schedulePeriodicCleanup(context: Context) {
        val request = PeriodicWorkRequestBuilder<CleanupWorker>(12, TimeUnit.HOURS)
            .addTag(TAG_CLEANUP)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_CLEANUP,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun scheduleHealthCheck(context: Context) {
        // No network required — app is offline-capable.
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .build()
        val request = PeriodicWorkRequestBuilder<HealthCheckWorker>(6, TimeUnit.HOURS)
            .setConstraints(constraints)
            .addTag(TAG_HEALTH)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_HEALTH,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    companion object {
        const val UNIQUE_PROCESS: String = "smsfwd_process_forward_jobs"
        const val UNIQUE_PROCESS_DELAYED: String = "smsfwd_process_forward_jobs_delayed"
        const val UNIQUE_CLEANUP: String = "smsfwd_cleanup"
        const val UNIQUE_HEALTH: String = "smsfwd_health"

        const val TAG_PROCESS: String = "process"
        const val TAG_CLEANUP: String = "cleanup"
        const val TAG_HEALTH: String = "health"
        const val TAG_CALLBACK_TIMEOUT: String = "callback_timeout"

        fun uniqueCallbackTimeout(jobId: String): String = "smsfwd_callback_timeout_$jobId"
        fun tagForJob(jobId: String): String = "job_$jobId"
    }
}
