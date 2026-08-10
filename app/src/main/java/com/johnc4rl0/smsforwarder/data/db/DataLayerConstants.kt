package com.johnc4rl0.smsforwarder.data.db

import com.johnc4rl0.smsforwarder.domain.model.ForwardState
import com.johnc4rl0.smsforwarder.domain.model.RuntimeSnapshot

object DataLayerConstants {
    const val QUOTA_WINDOW_MS: Long = 24L * 60L * 60L * 1000L
    const val JOB_TTL_MS: Long = 24L * 60L * 60L * 1000L
    const val DEDUP_RETENTION_MS: Long = 24L * 60L * 60L * 1000L
    const val SOURCE_MESSAGE_LIMIT: Int = RuntimeSnapshot.DEFAULT_SOURCE_MESSAGE_LIMIT
    const val OUTBOUND_SEGMENT_LIMIT: Int = RuntimeSnapshot.DEFAULT_OUTBOUND_SEGMENT_LIMIT
    const val DEFAULT_RECENT_LIMIT: Int = 50

    val TERMINAL_STATES: Set<ForwardState> = setOf(
        ForwardState.SENT,
        ForwardState.FAILED,
        ForwardState.PARTIAL,
        ForwardState.UNKNOWN,
        ForwardState.PURGED,
    )

    val UNSENT_STATES: Set<ForwardState> = setOf(
        ForwardState.QUEUED,
        ForwardState.SUBMITTING,
        ForwardState.RETRY_WAIT,
    )

    val TERMINAL_STATE_NAMES: List<String> = TERMINAL_STATES.map { it.name }
    val UNSENT_STATE_NAMES: List<String> = UNSENT_STATES.map { it.name }
}
