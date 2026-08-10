package com.johnc4rl0.smsforwarder.telephony

import android.content.Context
import com.johnc4rl0.smsforwarder.domain.ConfigRepository
import com.johnc4rl0.smsforwarder.domain.ForwardJobRepository
import com.johnc4rl0.smsforwarder.domain.SubscriptionCatalog
import com.johnc4rl0.smsforwarder.domain.model.RuntimeSnapshot

/**
 * Assembles a [RuntimeSnapshot] from repositories and live device state for each inbound SMS.
 */
class RuntimeSnapshotBuilder(
    private val appContext: Context,
    private val configRepository: ConfigRepository,
    private val forwardJobRepository: ForwardJobRepository,
    private val subscriptionCatalog: SubscriptionCatalog,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    suspend fun build(): RuntimeSnapshot {
        val now = clock()
        val config = configRepository.getConfig()
        val lines = subscriptionCatalog.listActiveLines()
        val quota = forwardJobRepository.currentQuota(now)
        return RuntimeSnapshot(
            config = config,
            permissionsOk = PermissionAndNotificationHealth.permissionsOk(appContext),
            notificationsOk = PermissionAndNotificationHealth.notificationsEnabled(appContext),
            sensitiveSmsPrivilegeOk = SensitiveSmsPrivilege.privilegeOk(appContext),
            activeSubscriptionIds = lines.map { it.subscriptionId }.toSet(),
            currentIdentityTokens = lines.associate { it.subscriptionId to it.identityToken },
            sourceMessagesUsedInWindow = quota.sourceMessagesUsed,
            outboundSegmentsUsedInWindow = quota.outboundSegmentsUsed,
            nowMillis = now,
        )
    }
}
