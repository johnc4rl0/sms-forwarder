package com.johnc4rl0.smsforwarder.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.johnc4rl0.smsforwarder.R
import com.johnc4rl0.smsforwarder.domain.model.OperationalState
import com.johnc4rl0.smsforwarder.domain.model.PauseReason
import com.johnc4rl0.smsforwarder.ui.auth.DeviceAuthenticator
import com.johnc4rl0.smsforwarder.ui.components.ErrorBanner
import com.johnc4rl0.smsforwarder.ui.components.InfoBanner
import com.johnc4rl0.smsforwarder.ui.components.KeyValueRow
import com.johnc4rl0.smsforwarder.ui.components.SectionCard
import com.johnc4rl0.smsforwarder.ui.dashboard.DashboardUiState
import com.johnc4rl0.smsforwarder.ui.dashboard.DashboardViewModel
import com.johnc4rl0.smsforwarder.ui.util.HibernationStatus
import com.johnc4rl0.smsforwarder.ui.util.toUserLabel

@Composable
fun StatusScreen(
    viewModel: DashboardViewModel,
    state: DashboardUiState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.busy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        state.errorMessage?.let { ErrorBanner(it) }
        state.infoMessage?.let { InfoBanner(it) }

        StatusStateCard(state)

        SectionCard(title = stringResource(R.string.dashboard_config_section)) {
            KeyValueRow(stringResource(R.string.dashboard_source), state.maskedSource)
            KeyValueRow(stringResource(R.string.dashboard_outbound), state.maskedOutbound)
            KeyValueRow(stringResource(R.string.dashboard_destination), state.maskedDestination)
            Text(
                stringResource(R.string.status_edit_in_settings),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(title = stringResource(R.string.dashboard_health_section)) {
            KeyValueRow(
                stringResource(R.string.dashboard_health_permissions),
                healthLabel(state.health.permissionsOk),
            )
            KeyValueRow(
                stringResource(R.string.dashboard_health_subscriptions),
                healthLabel(state.health.subscriptionsOk),
            )
            KeyValueRow(
                stringResource(R.string.dashboard_health_notifications),
                healthLabel(state.health.notificationsOk),
            )
            KeyValueRow(
                stringResource(R.string.dashboard_health_sensitive_sms),
                healthLabel(state.health.sensitiveSmsPrivilegeOk),
            )
            KeyValueRow(
                stringResource(R.string.dashboard_health_hibernation),
                when (state.health.hibernation) {
                    HibernationStatus.SAFE, HibernationStatus.NOT_APPLICABLE ->
                        stringResource(R.string.dashboard_health_ok)
                    HibernationStatus.RISK ->
                        stringResource(R.string.dashboard_health_bad)
                    HibernationStatus.UNKNOWN ->
                        stringResource(R.string.not_available)
                },
            )
            if (!state.health.sensitiveSmsPrivilegeOk) {
                Text(
                    stringResource(R.string.dashboard_sensitive_sms_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SectionCard(title = stringResource(R.string.dashboard_quota_section)) {
            Text(
                stringResource(
                    R.string.dashboard_quota_messages,
                    state.quota.sourceMessagesUsed,
                    viewModel.sourceMessageLimit,
                ),
            )
            Text(
                stringResource(
                    R.string.dashboard_quota_segments,
                    state.quota.outboundSegmentsUsed,
                    viewModel.outboundSegmentLimit,
                ),
            )
        }

        InfoBanner(stringResource(R.string.dashboard_otp_requirement))
        InfoBanner(stringResource(R.string.dashboard_force_stop_note))

        if (viewModel.canPause(state.config)) {
            OutlinedButton(
                onClick = viewModel::pause,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.dashboard_pause))
            }
        }
        if (viewModel.isSourceIdentityIssue(state)) {
            Button(
                onClick = {
                    val activity = context as? FragmentActivity
                    if (activity != null) {
                        viewModel.repairSourceLine(DeviceAuthenticator(activity))
                    }
                },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.dashboard_review_source))
            }
        } else if (viewModel.isOutboundIdentityIssue(state)) {
            Button(
                onClick = {
                    val activity = context as? FragmentActivity
                    if (activity != null) {
                        viewModel.repairOutboundLine(DeviceAuthenticator(activity))
                    }
                },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.dashboard_review_outbound))
            }
        } else if (viewModel.canReEnable(state)) {
            Button(
                onClick = {
                    val activity = context as? FragmentActivity
                    if (activity != null) {
                        viewModel.reEnable(DeviceAuthenticator(activity))
                    }
                },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.dashboard_reenable))
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun StatusStateCard(state: DashboardUiState) {
    val context = LocalContext.current
    val op = state.config.operationalState
    val label = op.toUserLabel(context)
    val reason: PauseReason? = when (op) {
        is OperationalState.SafetyPaused -> op.reason
        is OperationalState.Unhealthy -> op.reason
        OperationalState.ManuallyPaused -> state.config.pauseReason ?: PauseReason.MANUAL
        else -> state.config.pauseReason
    }
    SectionCard {
        Text(label, style = MaterialTheme.typography.headlineSmall)
        if (reason != null && op !is OperationalState.Enabled) {
            Text(
                stringResource(R.string.dashboard_pause_reason, reason.toUserLabel(context)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun healthLabel(ok: Boolean): String =
    if (ok) stringResource(R.string.dashboard_health_ok)
    else stringResource(R.string.dashboard_health_bad)
