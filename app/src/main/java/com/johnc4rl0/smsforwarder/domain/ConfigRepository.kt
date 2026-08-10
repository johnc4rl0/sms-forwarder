package com.johnc4rl0.smsforwarder.domain

import com.johnc4rl0.smsforwarder.domain.model.DestinationVerificationState
import com.johnc4rl0.smsforwarder.domain.model.ForwardingConfig
import kotlinx.coroutines.flow.Flow

/**
 * Persists [ForwardingConfig] (DataStore) and verification digests (encrypted).
 */
interface ConfigRepository {
    fun observeConfig(): Flow<ForwardingConfig>

    suspend fun getConfig(): ForwardingConfig

    suspend fun updateConfig(transform: (ForwardingConfig) -> ForwardingConfig)

    suspend fun getVerificationState(): DestinationVerificationState?

    suspend fun setVerificationState(state: DestinationVerificationState?)

    /** Wipe config-related secrets and return toward onboarding (e.g. Keystore loss). */
    suspend fun purgeAll()
}
