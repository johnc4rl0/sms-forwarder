package com.johnc4rl0.smsforwarder.di

import android.content.Context
import android.telephony.SmsManager
import android.util.Log
import androidx.datastore.preferences.preferencesDataStoreFile
import com.johnc4rl0.smsforwarder.data.config.DataStoreConfigRepository
import com.johnc4rl0.smsforwarder.data.crypto.AndroidKeystoreCryptoVault
import com.johnc4rl0.smsforwarder.data.crypto.CryptoVault
import com.johnc4rl0.smsforwarder.data.db.AppDatabase
import com.johnc4rl0.smsforwarder.data.db.RoomDedupStore
import com.johnc4rl0.smsforwarder.data.db.RoomForwardJobRepository
import com.johnc4rl0.smsforwarder.domain.ActivationCoordinator
import com.johnc4rl0.smsforwarder.domain.ConfigRepository
import com.johnc4rl0.smsforwarder.domain.DedupStore
import com.johnc4rl0.smsforwarder.domain.DefaultActivationCoordinator
import com.johnc4rl0.smsforwarder.domain.DefaultForwardingEngine
import com.johnc4rl0.smsforwarder.domain.ForwardJobRepository
import com.johnc4rl0.smsforwarder.domain.ForwardSubmissionGate
import com.johnc4rl0.smsforwarder.domain.ForwardingEngine
import com.johnc4rl0.smsforwarder.domain.SmsGateway
import com.johnc4rl0.smsforwarder.domain.SubscriptionCatalog
import com.johnc4rl0.smsforwarder.telephony.AndroidSubscriptionCatalog
import com.johnc4rl0.smsforwarder.telephony.DefaultSmsGateway
import com.johnc4rl0.smsforwarder.telephony.NotificationController
import com.johnc4rl0.smsforwarder.telephony.PermissionAndNotificationHealth
import com.johnc4rl0.smsforwarder.telephony.SensitiveSmsPrivilege
import com.johnc4rl0.smsforwarder.ui.notification.ForwardingStatusNotifier
import com.johnc4rl0.smsforwarder.ui.notification.NotificationControllerAdapter
import kotlinx.coroutines.runBlocking

/**
 * Manual dependency injection graph.
 *
 * Public property types are stable domain/data interfaces; implementations are swapped here.
 */
class AppContainer(
    private val appContext: Context,
) {
    val applicationContext: Context
        get() = appContext.applicationContext

    /**
     * True when Keystore material could not be established after purge recovery.
     * Callers fail closed (no enablement) while this is set.
     */
    @Volatile
    var encryptionUnusable: Boolean = false
        private set

    val cryptoVault: CryptoVault by lazy { createCryptoVault() }

    val database: AppDatabase by lazy { AppDatabase.build(applicationContext) }

    val configRepository: ConfigRepository by lazy {
        DataStoreConfigRepository.create(applicationContext, cryptoVault)
    }

    val forwardJobRepository: ForwardJobRepository by lazy {
        RoomForwardJobRepository(database, cryptoVault)
    }

    val dedupStore: DedupStore by lazy { RoomDedupStore(database) }

    /** Shared by UI/configuration mutations and the final worker send boundary. */
    val forwardSubmissionGate: ForwardSubmissionGate by lazy { ForwardSubmissionGate() }

    val subscriptionCatalog: SubscriptionCatalog by lazy {
        AndroidSubscriptionCatalog(applicationContext)
    }

    val forwardingEngine: ForwardingEngine by lazy {
        DefaultForwardingEngine(
            mac = { data -> cryptoVault.hmac.mac(data) },
            isDuplicate = { fingerprint ->
                // Receivers/workers run on background threads; blocking is acceptable.
                runBlocking { dedupStore.seenRecently(fingerprint) }
            },
        )
    }

    val smsGateway: SmsGateway by lazy {
        DefaultSmsGateway(context = applicationContext)
    }

    val statusNotifier: ForwardingStatusNotifier by lazy {
        ForwardingStatusNotifier(applicationContext).also { it.ensureChannel() }
    }

    val notificationController: NotificationController by lazy {
        NotificationControllerAdapter(statusNotifier)
    }

    val activationCoordinator: ActivationCoordinator by lazy {
        DefaultActivationCoordinator(
            configRepository = configRepository,
            forwardJobRepository = forwardJobRepository,
            subscriptionCatalog = subscriptionCatalog,
            mac = { data -> cryptoVault.hmac.mac(data) },
            sendVerificationSms = { subscriptionId, destination, code ->
                sendVerificationSms(subscriptionId, destination, code)
            },
            permissionsOk = {
                PermissionAndNotificationHealth.permissionsOk(applicationContext)
            },
            notificationsOk = {
                PermissionAndNotificationHealth.notificationsEnabled(applicationContext) &&
                    PermissionAndNotificationHealth.postNotificationsOk(applicationContext)
            },
            sensitiveSmsPrivilegeOk = {
                SensitiveSmsPrivilege.privilegeOk(applicationContext)
            },
            submissionGate = forwardSubmissionGate,
            encryptionAvailable = {
                !encryptionUnusable &&
                    cryptoVault.stringCipher.isAvailable() &&
                    cryptoVault.hmac.isAvailable()
            },
            onConfigChanged = { config ->
                try {
                    notificationController.showOrUpdateStatus(config)
                } catch (e: Exception) {
                    Log.w(TAG, "status notification update failed")
                }
            },
        )
    }

    private fun createCryptoVault(): CryptoVault {
        val vault = AndroidKeystoreCryptoVault()
        // Never silently create replacement keys over encrypted state. If either
        // alias disappeared while the app data remains, purge the old ciphertext
        // first and return the user to onboarding with a fresh key set.
        if (hasPersistedEncryptedState() && !vault.hasKeyMaterial()) {
            Log.e(TAG, "Keystore aliases missing while encrypted state exists — purging state")
            purgeEncryptedState(vault)
        }
        try {
            vault.ensureKeys()
            probeCrypto(vault)
            encryptionUnusable = false
            return vault
        } catch (e: Exception) {
            Log.e(TAG, "Keystore ensure/probe failed — purge encrypted state and retry")
            purgeEncryptedState(vault)
            return try {
                vault.ensureKeys()
                probeCrypto(vault)
                encryptionUnusable = false
                vault
            } catch (e2: Exception) {
                Log.e(TAG, "Keystore still unusable after purge — fail closed")
                encryptionUnusable = true
                vault
            }
        }
    }

    private fun hasPersistedEncryptedState(): Boolean =
        applicationContext
            .preferencesDataStoreFile(DataStoreConfigRepository.DATA_STORE_FILE)
            .exists() || applicationContext.getDatabasePath(AppDatabase.DB_NAME).exists()

    private fun probeCrypto(vault: CryptoVault) {
        val blob = vault.stringCipher.encrypt("probe")
        val roundTrip = vault.stringCipher.decrypt(blob)
        check(roundTrip == "probe") { "AES probe failed" }
        val mac = vault.hmac.mac(byteArrayOf(1, 2, 3))
        check(mac.isNotEmpty()) { "HMAC probe failed" }
    }

    /**
     * Spec: if Keystore data is lost or corrupt, purge encrypted state and return to onboarding.
     */
    private fun purgeEncryptedState(vault: CryptoVault) {
        try {
            vault.destroyKeys()
        } catch (_: Exception) {
            // continue purge
        }
        try {
            applicationContext.deleteDatabase(AppDatabase.DB_NAME)
        } catch (_: Exception) {
            // best-effort
        }
        try {
            applicationContext
                .preferencesDataStoreFile(DataStoreConfigRepository.DATA_STORE_FILE)
                .delete()
        } catch (_: Exception) {
            // best-effort
        }
    }

    private fun sendVerificationSms(
        subscriptionId: Int,
        destinationE164: String,
        code: String,
    ): Boolean {
        if (subscriptionId < 0) return false
        return try {
            val smsManager = SmsManager.getDefault().createForSubscriptionId(subscriptionId)
                ?: SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
            val body = DefaultActivationCoordinator.verificationMessageBody(code)
            smsManager.sendTextMessage(destinationE164, null, body, null, null)
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "verification send SecurityException")
            false
        } catch (e: Exception) {
            Log.e(TAG, "verification send failed")
            false
        }
    }

    companion object {
        private const val TAG = "AppContainer"
    }
}

/** Marker for components that can resolve the process-wide [AppContainer]. */
interface AppContainerProvider {
    val container: AppContainer
}

fun Context.appContainer(): AppContainer {
    val app = applicationContext
    check(app is AppContainerProvider) {
        "Application must implement AppContainerProvider"
    }
    return app.container
}
