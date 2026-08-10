package com.johnc4rl0.smsforwarder.ui.util

import android.content.Context
import com.johnc4rl0.smsforwarder.R
import com.johnc4rl0.smsforwarder.domain.model.ErrorCategory
import com.johnc4rl0.smsforwarder.domain.model.ForwardState
import com.johnc4rl0.smsforwarder.domain.model.OperationalState
import com.johnc4rl0.smsforwarder.domain.model.PauseReason

fun PauseReason.toUserLabel(context: Context): String {
    val res = when (this) {
        PauseReason.MANUAL -> R.string.pause_manual
        PauseReason.QUOTA_SOURCE_MESSAGES -> R.string.pause_quota_messages
        PauseReason.QUOTA_OUTBOUND_SEGMENTS -> R.string.pause_quota_segments
        PauseReason.PERMISSIONS_REVOKED -> R.string.pause_permissions
        PauseReason.NOTIFICATIONS_DISABLED -> R.string.pause_notifications
        PauseReason.SENSITIVE_SMS_PRIVILEGE_MISSING -> R.string.pause_sensitive_sms_privilege
        PauseReason.SOURCE_SUBSCRIPTION_INACTIVE -> R.string.pause_source_inactive
        PauseReason.OUTBOUND_SUBSCRIPTION_INACTIVE -> R.string.pause_outbound_inactive
        PauseReason.SOURCE_IDENTITY_MISMATCH -> R.string.pause_source_identity
        PauseReason.OUTBOUND_IDENTITY_MISMATCH -> R.string.pause_outbound_identity
        PauseReason.MISSING_INBOUND_SUBSCRIPTION_ID -> R.string.pause_missing_sub
        PauseReason.ENCRYPTION_UNAVAILABLE -> R.string.pause_encryption
        PauseReason.HIBERNATION_RISK -> R.string.pause_hibernation
        PauseReason.CONFIGURATION_INCOMPLETE -> R.string.pause_config
    }
    return context.getString(res)
}

fun OperationalState.toUserLabel(context: Context): String = when (this) {
    OperationalState.NotConfigured -> context.getString(R.string.state_not_configured)
    OperationalState.Enabled -> context.getString(R.string.state_enabled)
    OperationalState.ManuallyPaused -> context.getString(R.string.state_manually_paused)
    is OperationalState.SafetyPaused -> context.getString(R.string.state_safety_paused)
    is OperationalState.Unhealthy -> context.getString(R.string.state_unhealthy)
}

fun ForwardState.toUserLabel(): String = name

fun ErrorCategory?.toUserLabel(): String = this?.name ?: "—"
