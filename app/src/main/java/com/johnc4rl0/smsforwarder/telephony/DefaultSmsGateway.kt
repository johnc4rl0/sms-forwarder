package com.johnc4rl0.smsforwarder.telephony

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telephony.SmsManager
import android.util.Log
import com.johnc4rl0.smsforwarder.domain.SmsGateway
import com.johnc4rl0.smsforwarder.domain.model.ErrorCategory
import com.johnc4rl0.smsforwarder.domain.model.ForwardJob
import com.johnc4rl0.smsforwarder.domain.model.SubmitResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Sends forwarded SMS via [SmsManager.createForSubscriptionId] only — never the default SMS subscription.
 * Unique immutable sent-result PendingIntents per segment; no delivery reports.
 *
 * [ForwardJob.body] is already the fully formatted payload from [com.johnc4rl0.smsforwarder.domain.MessageFormatter]
 * (header + original body). This gateway must not wrap it again.
 */
class DefaultSmsGateway(
    context: Context,
) : SmsGateway {

    private val appContext = context.applicationContext

    override suspend fun submit(job: ForwardJob): SubmitResult = withContext(Dispatchers.IO) {
        val outboundSubId = job.outboundSubscriptionId
        if (outboundSubId < 0) {
            Log.w(TAG, "submit rejected: invalid outbound subscription category=policy")
            return@withContext SubmitResult.Failed(ErrorCategory.POLICY_OR_GENERIC)
        }

        // Engine already stored the full forward payload in job.body — send as-is.
        val payload = job.body
        if (payload.isEmpty()) {
            Log.e(TAG, "submit rejected: empty payload category=policy")
            return@withContext SubmitResult.Failed(ErrorCategory.POLICY_OR_GENERIC)
        }

        // Spec: bind to outbound subscription only — never send via default SMS subscription.
        // createForSubscriptionId / getSmsManagerForSubscriptionId both scope SmsManager to subId.
        val smsManager = try {
            SmsManager.getDefault().createForSubscriptionId(outboundSubId)
                ?: SmsManager.getSmsManagerForSubscriptionId(outboundSubId)
        } catch (e: Exception) {
            Log.e(TAG, "createForSubscriptionId failed category=policy")
            return@withContext SubmitResult.Failed(ErrorCategory.POLICY_OR_GENERIC, e.message)
        }

        val parts: ArrayList<String> = try {
            smsManager.divideMessage(payload)
        } catch (e: Exception) {
            Log.e(TAG, "divideMessage failed category=policy")
            return@withContext SubmitResult.Failed(ErrorCategory.POLICY_OR_GENERIC, e.message)
        }

        if (parts.isEmpty()) {
            Log.e(TAG, "divideMessage empty parts category=policy")
            return@withContext SubmitResult.Failed(ErrorCategory.POLICY_OR_GENERIC, "empty parts")
        }

        val partCount = parts.size
        val sentIntents = ArrayList<PendingIntent>(partCount)
        for (index in 0 until partCount) {
            sentIntents.add(createSentPendingIntent(job.id, index, partCount))
        }

        try {
            // deliveryIntents = null → no delivery reports
            smsManager.sendMultipartTextMessage(
                job.destinationE164,
                null,
                parts,
                sentIntents,
                null,
            )
            Log.i(TAG, "submitted job segments=$partCount")
            SubmitResult.Submitted(segmentCount = partCount)
        } catch (e: SecurityException) {
            Log.e(TAG, "sendMultipart failed category=policy")
            SubmitResult.Failed(ErrorCategory.POLICY_OR_GENERIC, e.message)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "sendMultipart failed category=policy")
            SubmitResult.Failed(ErrorCategory.POLICY_OR_GENERIC, e.message)
        } catch (e: Exception) {
            Log.e(TAG, "sendMultipart failed category=unknown")
            SubmitResult.Failed(ErrorCategory.UNKNOWN, e.message)
        }
    }

    private fun createSentPendingIntent(jobId: String, partIndex: Int, partCount: Int): PendingIntent {
        val intent = Intent(appContext, SendResultReceiver::class.java).apply {
            action = SendResultReceiver.ACTION_SMS_SENT
            // Unique data URI so PendingIntents are distinct per segment.
            data = Uri.parse("smsfwd://sent/$jobId/$partIndex")
            putExtra(SendResultReceiver.EXTRA_JOB_ID, jobId)
            putExtra(SendResultReceiver.EXTRA_PART_INDEX, partIndex)
            putExtra(SendResultReceiver.EXTRA_PART_COUNT, partCount)
        }
        // requestCode must also differ so the system does not collapse intents.
        val requestCode = (jobId.hashCode() * 31 + partIndex) and 0x7FFF_FFFF
        return PendingIntent.getBroadcast(
            appContext,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        private const val TAG = "SmsGateway"
    }
}
