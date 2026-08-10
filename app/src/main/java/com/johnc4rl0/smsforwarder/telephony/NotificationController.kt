package com.johnc4rl0.smsforwarder.telephony

import com.johnc4rl0.smsforwarder.domain.model.ForwardingConfig

/**
 * Status-notification seam for the UI/notification agent.
 *
 * Telephony restores or cancels the ongoing status notification after boot and
 * state transitions; concrete NotificationCompat implementation lives outside this package.
 */
interface NotificationController {
    /** Show or refresh the low-importance ongoing status notification while enabled. */
    fun showOrUpdateStatus(config: ForwardingConfig)

    /** Remove the status notification (paused / disabled / not configured). */
    fun cancelStatus()
}

/** Compile-safe no-op until the UI agent wires a real implementation. */
class NoOpNotificationController : NotificationController {
    override fun showOrUpdateStatus(config: ForwardingConfig) = Unit
    override fun cancelStatus() = Unit
}
