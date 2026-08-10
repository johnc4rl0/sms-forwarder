package com.johnc4rl0.smsforwarder.data.config

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.johnc4rl0.smsforwarder.data.crypto.CryptoVault
import com.johnc4rl0.smsforwarder.data.crypto.EncryptedBlob
import com.johnc4rl0.smsforwarder.data.crypto.SecureStringCipher
import com.johnc4rl0.smsforwarder.domain.ConfigRepository
import com.johnc4rl0.smsforwarder.domain.model.DestinationVerificationState
import com.johnc4rl0.smsforwarder.domain.model.ForwardingConfig
import com.johnc4rl0.smsforwarder.domain.model.LineSelection
import com.johnc4rl0.smsforwarder.domain.model.OperationalState
import com.johnc4rl0.smsforwarder.domain.model.PauseReason
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * DataStore-backed [ConfigRepository].
 *
 * Sensitive fields (phone numbers, destination) are AES-GCM encrypted.
 * Verification codes are stored only as [DestinationVerificationState.codeDigest] (HMAC).
 */
class DataStoreConfigRepository(
    private val dataStore: DataStore<Preferences>,
    private val cryptoVault: CryptoVault,
) : ConfigRepository {

    private val cipher: SecureStringCipher get() = cryptoVault.stringCipher

    override fun observeConfig(): Flow<ForwardingConfig> =
        dataStore.data
            .catch { e ->
                if (e is IOException) emit(emptyPreferences()) else throw e
            }
            .map { prefs -> prefs.toConfig(cipher) }

    override suspend fun getConfig(): ForwardingConfig = observeConfig().first()

    override suspend fun updateConfig(transform: (ForwardingConfig) -> ForwardingConfig) {
        requireCrypto()
        dataStore.edit { prefs ->
            val current = prefs.toConfig(cipher)
            val next = transform(current)
            prefs.writeConfig(next, cipher)
        }
    }

    override suspend fun getVerificationState(): DestinationVerificationState? {
        requireCrypto()
        val prefs = dataStore.data.first()
        return prefs.toVerificationState(cipher)
    }

    override suspend fun setVerificationState(state: DestinationVerificationState?) {
        requireCrypto()
        dataStore.edit { prefs ->
            if (state == null) {
                prefs.clearVerification()
            } else {
                prefs.writeVerification(state, cipher)
            }
        }
    }

    override suspend fun purgeAll() {
        dataStore.edit { it.clear() }
    }

    private fun requireCrypto() {
        check(cipher.isAvailable()) { "Encryption unavailable — purge encrypted state" }
    }

    companion object {
        const val DATA_STORE_FILE = "forwarding_config.preferences_pb"

        fun create(
            context: Context,
            cryptoVault: CryptoVault,
            scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        ): DataStoreConfigRepository {
            val store = PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { context.applicationContext.preferencesDataStoreFile(DATA_STORE_FILE) },
            )
            return DataStoreConfigRepository(store, cryptoVault)
        }

        fun create(
            dataStore: DataStore<Preferences>,
            cryptoVault: CryptoVault,
        ): DataStoreConfigRepository = DataStoreConfigRepository(dataStore, cryptoVault)
    }
}

// --- Preference keys ---

private object Keys {
    val DISCLOSURE = booleanPreferencesKey("disclosure_accepted")
    val CONFIG_REVISION = longPreferencesKey("config_revision")
    val DESTINATION_VERIFIED = booleanPreferencesKey("destination_verified")
    val DESTINATION_CT = stringPreferencesKey("destination_ct")
    val DESTINATION_IV = stringPreferencesKey("destination_iv")

    val OP_STATE = stringPreferencesKey("operational_state")
    val OP_PAUSE_REASON = stringPreferencesKey("pause_reason")
    // SafetyPaused / Unhealthy embed reason in OP_STATE as "SafetyPaused:REASON"

    // Source line
    val SRC_SUB_ID = intPreferencesKey("src_sub_id")
    val SRC_SLOT = intPreferencesKey("src_slot")
    val SRC_CARRIER = stringPreferencesKey("src_carrier")
    val SRC_REPORTED_CT = stringPreferencesKey("src_reported_ct")
    val SRC_REPORTED_IV = stringPreferencesKey("src_reported_iv")
    val SRC_MANUAL_CT = stringPreferencesKey("src_manual_ct")
    val SRC_MANUAL_IV = stringPreferencesKey("src_manual_iv")
    val SRC_IDENTITY = stringPreferencesKey("src_identity")
    val SRC_PRESENT = booleanPreferencesKey("src_present")

    // Outbound line
    val OUT_SUB_ID = intPreferencesKey("out_sub_id")
    val OUT_SLOT = intPreferencesKey("out_slot")
    val OUT_CARRIER = stringPreferencesKey("out_carrier")
    val OUT_REPORTED_CT = stringPreferencesKey("out_reported_ct")
    val OUT_REPORTED_IV = stringPreferencesKey("out_reported_iv")
    val OUT_MANUAL_CT = stringPreferencesKey("out_manual_ct")
    val OUT_MANUAL_IV = stringPreferencesKey("out_manual_iv")
    val OUT_IDENTITY = stringPreferencesKey("out_identity")
    val OUT_PRESENT = booleanPreferencesKey("out_present")

    // Verification (digest only — never plaintext code)
    val VER_DEST_CT = stringPreferencesKey("ver_dest_ct")
    val VER_DEST_IV = stringPreferencesKey("ver_dest_iv")
    val VER_DIGEST = stringPreferencesKey("ver_digest_b64")
    val VER_EXPIRES = longPreferencesKey("ver_expires")
    val VER_ATTEMPTS = intPreferencesKey("ver_attempts")
    val VER_SENDS = intPreferencesKey("ver_sends")
    val VER_LAST_SEND = longPreferencesKey("ver_last_send")
    val VER_PRESENT = booleanPreferencesKey("ver_present")
}

private fun Preferences.toConfig(cipher: SecureStringCipher): ForwardingConfig {
    val destination = readEncryptedString(cipher, Keys.DESTINATION_CT, Keys.DESTINATION_IV)
    val (opState, pauseFromState) = readOperationalState()
    val pauseReason = this[Keys.OP_PAUSE_REASON]?.let {
        runCatching { PauseReason.valueOf(it) }.getOrNull()
    } ?: pauseFromState

    return ForwardingConfig(
        disclosureAccepted = this[Keys.DISCLOSURE] ?: false,
        source = readLine(
            presentKey = Keys.SRC_PRESENT,
            subIdKey = Keys.SRC_SUB_ID,
            slotKey = Keys.SRC_SLOT,
            carrierKey = Keys.SRC_CARRIER,
            reportedCt = Keys.SRC_REPORTED_CT,
            reportedIv = Keys.SRC_REPORTED_IV,
            manualCt = Keys.SRC_MANUAL_CT,
            manualIv = Keys.SRC_MANUAL_IV,
            identityKey = Keys.SRC_IDENTITY,
            cipher = cipher,
        ),
        outbound = readLine(
            presentKey = Keys.OUT_PRESENT,
            subIdKey = Keys.OUT_SUB_ID,
            slotKey = Keys.OUT_SLOT,
            carrierKey = Keys.OUT_CARRIER,
            reportedCt = Keys.OUT_REPORTED_CT,
            reportedIv = Keys.OUT_REPORTED_IV,
            manualCt = Keys.OUT_MANUAL_CT,
            manualIv = Keys.OUT_MANUAL_IV,
            identityKey = Keys.OUT_IDENTITY,
            cipher = cipher,
        ),
        destinationE164 = destination,
        destinationVerified = this[Keys.DESTINATION_VERIFIED] ?: false,
        configRevision = this[Keys.CONFIG_REVISION] ?: 0L,
        operationalState = opState,
        pauseReason = pauseReason,
    )
}

private fun Preferences.readOperationalState(): Pair<OperationalState, PauseReason?> {
    val raw = this[Keys.OP_STATE] ?: return OperationalState.NotConfigured to null
    return when {
        raw == "NotConfigured" -> OperationalState.NotConfigured to null
        raw == "Enabled" -> OperationalState.Enabled to null
        raw == "ManuallyPaused" -> OperationalState.ManuallyPaused to null
        raw.startsWith("SafetyPaused:") -> {
            val reasonName = raw.removePrefix("SafetyPaused:")
            val reason = runCatching { PauseReason.valueOf(reasonName) }
                .getOrDefault(PauseReason.CONFIGURATION_INCOMPLETE)
            OperationalState.SafetyPaused(reason) to reason
        }
        raw.startsWith("Unhealthy:") -> {
            val reasonName = raw.removePrefix("Unhealthy:")
            val reason = runCatching { PauseReason.valueOf(reasonName) }
                .getOrDefault(PauseReason.CONFIGURATION_INCOMPLETE)
            OperationalState.Unhealthy(reason) to reason
        }
        else -> OperationalState.NotConfigured to null
    }
}

private fun Preferences.readLine(
    presentKey: Preferences.Key<Boolean>,
    subIdKey: Preferences.Key<Int>,
    slotKey: Preferences.Key<Int>,
    carrierKey: Preferences.Key<String>,
    reportedCt: Preferences.Key<String>,
    reportedIv: Preferences.Key<String>,
    manualCt: Preferences.Key<String>,
    manualIv: Preferences.Key<String>,
    identityKey: Preferences.Key<String>,
    cipher: SecureStringCipher,
): LineSelection? {
    if (this[presentKey] != true) return null
    val subId = this[subIdKey] ?: return null
    return LineSelection(
        subscriptionId = subId,
        slotIndex = this[slotKey],
        carrierDisplayName = this[carrierKey],
        reportedNumberE164 = readEncryptedString(cipher, reportedCt, reportedIv),
        manualNumberE164 = readEncryptedString(cipher, manualCt, manualIv),
        identityToken = this[identityKey],
    )
}

private fun Preferences.readEncryptedString(
    cipher: SecureStringCipher,
    ctKey: Preferences.Key<String>,
    ivKey: Preferences.Key<String>,
): String? {
    val ctB64 = this[ctKey] ?: return null
    val ivB64 = this[ivKey] ?: return null
    if (!cipher.isAvailable()) return null
    return try {
        val blob = EncryptedBlob(
            ciphertext = Base64.getDecoder().decode(ctB64),
            iv = Base64.getDecoder().decode(ivB64),
        )
        cipher.decrypt(blob)
    } catch (_: Exception) {
        null
    }
}

private fun MutablePreferences.writeConfig(config: ForwardingConfig, cipher: SecureStringCipher) {
    this[Keys.DISCLOSURE] = config.disclosureAccepted
    this[Keys.CONFIG_REVISION] = config.configRevision
    this[Keys.DESTINATION_VERIFIED] = config.destinationVerified
    writeOperationalState(config.operationalState)
    if (config.pauseReason != null) {
        this[Keys.OP_PAUSE_REASON] = config.pauseReason!!.name
    } else {
        remove(Keys.OP_PAUSE_REASON)
    }

    writeEncryptedOptional(cipher, Keys.DESTINATION_CT, Keys.DESTINATION_IV, config.destinationE164)

    writeLine(
        config.source,
        presentKey = Keys.SRC_PRESENT,
        subIdKey = Keys.SRC_SUB_ID,
        slotKey = Keys.SRC_SLOT,
        carrierKey = Keys.SRC_CARRIER,
        reportedCt = Keys.SRC_REPORTED_CT,
        reportedIv = Keys.SRC_REPORTED_IV,
        manualCt = Keys.SRC_MANUAL_CT,
        manualIv = Keys.SRC_MANUAL_IV,
        identityKey = Keys.SRC_IDENTITY,
        cipher = cipher,
    )
    writeLine(
        config.outbound,
        presentKey = Keys.OUT_PRESENT,
        subIdKey = Keys.OUT_SUB_ID,
        slotKey = Keys.OUT_SLOT,
        carrierKey = Keys.OUT_CARRIER,
        reportedCt = Keys.OUT_REPORTED_CT,
        reportedIv = Keys.OUT_REPORTED_IV,
        manualCt = Keys.OUT_MANUAL_CT,
        manualIv = Keys.OUT_MANUAL_IV,
        identityKey = Keys.OUT_IDENTITY,
        cipher = cipher,
    )
}

private fun MutablePreferences.writeOperationalState(state: OperationalState) {
    this[Keys.OP_STATE] = when (state) {
        is OperationalState.NotConfigured -> "NotConfigured"
        is OperationalState.Enabled -> "Enabled"
        is OperationalState.ManuallyPaused -> "ManuallyPaused"
        is OperationalState.SafetyPaused -> "SafetyPaused:${state.reason.name}"
        is OperationalState.Unhealthy -> "Unhealthy:${state.reason.name}"
    }
}

private fun MutablePreferences.writeLine(
    line: LineSelection?,
    presentKey: Preferences.Key<Boolean>,
    subIdKey: Preferences.Key<Int>,
    slotKey: Preferences.Key<Int>,
    carrierKey: Preferences.Key<String>,
    reportedCt: Preferences.Key<String>,
    reportedIv: Preferences.Key<String>,
    manualCt: Preferences.Key<String>,
    manualIv: Preferences.Key<String>,
    identityKey: Preferences.Key<String>,
    cipher: SecureStringCipher,
) {
    if (line == null) {
        this[presentKey] = false
        remove(subIdKey)
        remove(slotKey)
        remove(carrierKey)
        remove(reportedCt)
        remove(reportedIv)
        remove(manualCt)
        remove(manualIv)
        remove(identityKey)
        return
    }
    this[presentKey] = true
    this[subIdKey] = line.subscriptionId
    if (line.slotIndex != null) this[slotKey] = line.slotIndex!! else remove(slotKey)
    if (line.carrierDisplayName != null) {
        this[carrierKey] = line.carrierDisplayName!!
    } else {
        remove(carrierKey)
    }
    writeEncryptedOptional(cipher, reportedCt, reportedIv, line.reportedNumberE164)
    writeEncryptedOptional(cipher, manualCt, manualIv, line.manualNumberE164)
    if (line.identityToken != null) {
        this[identityKey] = line.identityToken!!
    } else {
        remove(identityKey)
    }
}

private fun MutablePreferences.writeEncryptedOptional(
    cipher: SecureStringCipher,
    ctKey: Preferences.Key<String>,
    ivKey: Preferences.Key<String>,
    value: String?,
) {
    if (value.isNullOrEmpty()) {
        remove(ctKey)
        remove(ivKey)
        return
    }
    val blob = cipher.encrypt(value)
    this[ctKey] = Base64.getEncoder().encodeToString(blob.ciphertext)
    this[ivKey] = Base64.getEncoder().encodeToString(blob.iv)
}

private fun Preferences.toVerificationState(cipher: SecureStringCipher): DestinationVerificationState? {
    if (this[Keys.VER_PRESENT] != true) return null
    val dest = readEncryptedString(cipher, Keys.VER_DEST_CT, Keys.VER_DEST_IV) ?: return null
    val digestB64 = this[Keys.VER_DIGEST] ?: return null
    val expires = this[Keys.VER_EXPIRES] ?: return null
    val attempts = this[Keys.VER_ATTEMPTS] ?: return null
    val sends = this[Keys.VER_SENDS] ?: return null
    val lastSend = this[Keys.VER_LAST_SEND] ?: return null
    return DestinationVerificationState(
        destinationE164 = dest,
        codeDigest = Base64.getDecoder().decode(digestB64),
        expiresAtMillis = expires,
        attemptsRemaining = attempts,
        sendsInRollingHour = sends,
        lastSendAtMillis = lastSend,
    )
}

private fun MutablePreferences.writeVerification(
    state: DestinationVerificationState,
    cipher: SecureStringCipher,
) {
    this[Keys.VER_PRESENT] = true
    writeEncryptedOptional(cipher, Keys.VER_DEST_CT, Keys.VER_DEST_IV, state.destinationE164)
    this[Keys.VER_DIGEST] = Base64.getEncoder().encodeToString(state.codeDigest)
    this[Keys.VER_EXPIRES] = state.expiresAtMillis
    this[Keys.VER_ATTEMPTS] = state.attemptsRemaining
    this[Keys.VER_SENDS] = state.sendsInRollingHour
    this[Keys.VER_LAST_SEND] = state.lastSendAtMillis
}

private fun MutablePreferences.clearVerification() {
    this[Keys.VER_PRESENT] = false
    remove(Keys.VER_DEST_CT)
    remove(Keys.VER_DEST_IV)
    remove(Keys.VER_DIGEST)
    remove(Keys.VER_EXPIRES)
    remove(Keys.VER_ATTEMPTS)
    remove(Keys.VER_SENDS)
    remove(Keys.VER_LAST_SEND)
}
