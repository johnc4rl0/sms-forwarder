package com.johnc4rl0.smsforwarder.ui.notification

import com.johnc4rl0.smsforwarder.domain.model.ForwardingConfig
import com.johnc4rl0.smsforwarder.telephony.NotificationController

/**
 * Bridges UI [ForwardingStatusNotifier] to telephony [NotificationController].
 */
class NotificationControllerAdapter(
    private val notifier: ForwardingStatusNotifier,
) : NotificationController {
    override fun showOrUpdateStatus(config: ForwardingConfig) {
        notifier.sync(config)
    }

    override fun cancelStatus() {
        notifier.cancel()
    }
}
