package com.johnc4rl0.smsforwarder.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.johnc4rl0.smsforwarder.di.appContainer
import com.johnc4rl0.smsforwarder.domain.model.ForwardState

/**
 * Missing sent-result callbacks after 15 minutes → UNKNOWN.
 * Does not retry (including partial multi-part states).
 */
class CallbackTimeoutWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB_ID)
        if (jobId.isNullOrBlank()) return Result.success()

        val repo = applicationContext.appContainer().forwardJobRepository
        val job = try {
            repo.getJob(jobId)
        } catch (e: Exception) {
            Log.e(TAG, "getJob failed")
            return Result.success()
        } ?: return Result.success()

        if (job.state == ForwardState.SUBMITTING) {
            Log.w(TAG, "callback timeout → UNKNOWN")
            try {
                repo.updateState(jobId, ForwardState.UNKNOWN, attemptCount = job.attemptCount)
                repo.purgeSensitivePayloads(listOf(jobId))
            } catch (e: Exception) {
                Log.e(TAG, "timeout update failed")
            }
        }
        // PARTIAL / SENT / FAILED already terminal — no action, no retry.
        return Result.success()
    }

    companion object {
        const val KEY_JOB_ID: String = "job_id"
        private const val TAG = "CallbackTimeout"
    }
}
