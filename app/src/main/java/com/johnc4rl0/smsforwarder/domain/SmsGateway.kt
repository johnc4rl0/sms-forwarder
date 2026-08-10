package com.johnc4rl0.smsforwarder.domain

import com.johnc4rl0.smsforwarder.domain.model.ForwardJob
import com.johnc4rl0.smsforwarder.domain.model.SubmitResult

/**
 * Submits a constructed forward payload via SmsManager for a specific outbound subscription.
 * Never uses the default SMS subscription.
 */
interface SmsGateway {
    /**
     * Send [ForwardJob.body] (already fully formatted by the engine) as multipart SMS
     * with unique sent-result PendingIntents. Does not request delivery reports.
     * Does not re-format or wrap the payload.
     */
    suspend fun submit(job: ForwardJob): SubmitResult
}
