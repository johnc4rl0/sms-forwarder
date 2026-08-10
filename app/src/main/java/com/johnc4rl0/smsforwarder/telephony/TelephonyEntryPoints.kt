package com.johnc4rl0.smsforwarder.telephony

import android.content.Context
import com.johnc4rl0.smsforwarder.di.AppContainer
import com.johnc4rl0.smsforwarder.di.appContainer
import com.johnc4rl0.smsforwarder.work.ForwardWorkScheduler

/**
 * Builds telephony pipeline collaborators from [AppContainer].
 * Receivers resolve the live graph via [Context.appContainer].
 */
object TelephonyEntryPoints {

    fun inboundSmsProcessor(context: Context): InboundSmsProcessor {
        val c = context.appContainer()
        return inboundSmsProcessor(c)
    }

    fun inboundSmsProcessor(container: AppContainer): InboundSmsProcessor {
        val snapshotBuilder = RuntimeSnapshotBuilder(
            appContext = container.applicationContext,
            configRepository = container.configRepository,
            forwardJobRepository = container.forwardJobRepository,
            subscriptionCatalog = container.subscriptionCatalog,
        )
        return InboundSmsProcessor(
            forwardingEngine = container.forwardingEngine,
            forwardJobRepository = container.forwardJobRepository,
            activationCoordinator = container.activationCoordinator,
            runtimeSnapshotBuilder = snapshotBuilder,
            workScheduler = ForwardWorkScheduler(),
            dedupStore = container.dedupStore,
            mac = { data -> container.cryptoVault.hmac.mac(data) },
        )
    }

    fun sendResultAggregator(context: Context): SendResultAggregator {
        val c = context.appContainer()
        return SendResultAggregator(
            forwardJobRepository = c.forwardJobRepository,
            workScheduler = ForwardWorkScheduler(),
        )
    }

    fun bootRestoreCoordinator(context: Context): BootRestoreCoordinator {
        val c = context.appContainer()
        return BootRestoreCoordinator(
            configRepository = c.configRepository,
            subscriptionCatalog = c.subscriptionCatalog,
            activationCoordinator = c.activationCoordinator,
            forwardJobRepository = c.forwardJobRepository,
            dedupStore = c.dedupStore,
            notificationController = c.notificationController,
            workScheduler = ForwardWorkScheduler(),
        )
    }

    fun androidSubscriptionCatalog(context: Context): AndroidSubscriptionCatalog =
        AndroidSubscriptionCatalog(context)

    fun defaultSmsGateway(context: Context): DefaultSmsGateway {
        val c = context.appContainer()
        return DefaultSmsGateway(context = c.applicationContext)
    }
}
