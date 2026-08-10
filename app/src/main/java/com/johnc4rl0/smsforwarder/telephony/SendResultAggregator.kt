package com.johnc4rl0.smsforwarder.telephony

import android.content.Context
import android.content.Intent
import android.util.Log
import com.johnc4rl0.smsforwarder.domain.ForwardJobRepository
import com.johnc4rl0.smsforwarder.domain.model.ForwardState
import com.johnc4rl0.smsforwarder.domain.model.PartSendResult
import com.johnc4rl0.smsforwarder.work.ForwardWorkScheduler

/**
 * Handles per-segment sent-result broadcasts: recordPartResult + retry scheduling when appropriate.
 *
 * Terminal aggregation is primarily owned by [ForwardJobRepository.recordPartResult]; this class
 * classifies the result code and reacts to the returned job state.
 */
class SendResultAggregator(
    private val forwardJobRepository: ForwardJobRepository,
    private val workScheduler: ForwardWorkScheduler = ForwardWorkScheduler(),
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    suspend fun handle(context: Context, intent: Intent, resultCode: Int) {
        val jobId = intent.getStringExtra(SendResultReceiver.EXTRA_JOB_ID)
        if (jobId.isNullOrBlank()) {
            Log.w(TAG, "missing job id on send result")
            return
        }
        val partIndex = intent.getIntExtra(SendResultReceiver.EXTRA_PART_INDEX, -1)
        val partCount = intent.getIntExtra(SendResultReceiver.EXTRA_PART_COUNT, -1)
        if (partIndex < 0 || partCount <= 0) {
            Log.w(TAG, "invalid part metadata")
            return
        }

        val partResult = PartSendResult(
            jobId = jobId,
            partIndex = partIndex,
            partCount = partCount,
            resultCode = resultCode,
            isTransient = SendResultClassifier.isTransient(resultCode),
            receivedAtMillis = clock(),
        )

        val job = try {
            forwardJobRepository.recordPartResult(partResult)
        } catch (e: Exception) {
            Log.e(TAG, "recordPartResult failed")
            return
        }

        if (job == null) {
            Log.d(TAG, "part recorded; aggregation incomplete or unknown job")
            return
        }

        when (job.state) {
            ForwardState.SENT -> {
                Log.i(TAG, "job COMPLETE")
                try {
                    forwardJobRepository.purgeSensitivePayloads(listOf(job.id))
                } catch (_: Exception) {
                }
            }
            ForwardState.PARTIAL -> {
                // Never retry partial multipart sends.
                Log.w(TAG, "job PARTIAL — no retry")
                try {
                    forwardJobRepository.purgeSensitivePayloads(listOf(job.id))
                } catch (_: Exception) {
                }
            }
            ForwardState.RETRY_WAIT -> {
                val delay = RetryPolicy.delayAfterFailedAttempt(job.attemptCount)
                if (delay != null && RetryPolicy.canAttempt(job.attemptCount)) {
                    val due = clock() + delay
                    try {
                        // Persist due time so expedited process work cannot collapse backoff.
                        forwardJobRepository.updateState(
                            jobId = job.id,
                            state = ForwardState.RETRY_WAIT,
                            attemptCount = job.attemptCount,
                            nextAttemptAtMillis = due,
                            updateNextAttemptAt = true,
                        )
                    } catch (_: Exception) {
                    }
                    workScheduler.enqueueProcessDelayed(context, delay)
                    Log.i(TAG, "job RETRY_WAIT scheduled delayMs=$delay")
                } else {
                    try {
                        forwardJobRepository.updateState(
                            job.id,
                            ForwardState.FAILED,
                            attemptCount = job.attemptCount,
                        )
                        forwardJobRepository.purgeSensitivePayloads(listOf(job.id))
                    } catch (_: Exception) {
                    }
                }
            }
            ForwardState.FAILED, ForwardState.UNKNOWN -> {
                Log.i(TAG, "job terminal state=${job.state}")
                try {
                    forwardJobRepository.purgeSensitivePayloads(listOf(job.id))
                } catch (_: Exception) {
                }
            }
            else -> {
                // Still SUBMITTING or other intermediate — wait for remaining parts.
                Log.d(TAG, "job still open state=${job.state}")
            }
        }
    }

    companion object {
        private const val TAG = "SendResultAgg"
    }
}
