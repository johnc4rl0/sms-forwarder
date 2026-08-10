package com.johnc4rl0.smsforwarder.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.johnc4rl0.smsforwarder.R
import com.johnc4rl0.smsforwarder.domain.model.ActiveLine
import com.johnc4rl0.smsforwarder.ui.auth.DeviceAuthenticator
import com.johnc4rl0.smsforwarder.ui.components.ErrorBanner
import com.johnc4rl0.smsforwarder.ui.components.InfoBanner
import com.johnc4rl0.smsforwarder.ui.components.KeyValueRow
import com.johnc4rl0.smsforwarder.ui.components.SectionCard
import com.johnc4rl0.smsforwarder.ui.util.formatActiveLineLabel

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    when (state.step) {
        SettingsStep.View -> SettingsViewContent(state, viewModel, modifier)
        SettingsStep.Edit -> SettingsEditContent(state, viewModel, modifier)
        SettingsStep.ReauthDestination -> SettingsReauthContent(state, viewModel, modifier)
        SettingsStep.ReverifyDestination -> SettingsReverifyContent(state, viewModel, modifier)
    }
}

@Composable
private fun SettingsViewContent(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    modifier: Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Messages(state)
        Text(
            stringResource(R.string.settings_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SectionCard(title = stringResource(R.string.settings_current_setup)) {
            KeyValueRow(stringResource(R.string.settings_inbound), state.maskedSource)
            KeyValueRow(stringResource(R.string.settings_outbound), state.maskedOutbound)
            KeyValueRow(
                stringResource(R.string.settings_destination),
                buildString {
                    append(state.maskedDestination)
                    append(" · ")
                    append(
                        if (state.config.destinationVerified) {
                            stringResource(R.string.settings_verified)
                        } else {
                            stringResource(R.string.settings_not_verified)
                        },
                    )
                },
            )
        }
        Button(
            onClick = viewModel::startEdit,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.settings_edit))
        }
        SectionCard(title = stringResource(R.string.settings_safety)) {
            KeyValueRow(
                stringResource(R.string.settings_dest_change_policy),
                stringResource(R.string.settings_dest_change_policy_value),
            )
            KeyValueRow(
                stringResource(R.string.settings_line_change_policy),
                stringResource(R.string.settings_line_change_policy_value),
            )
        }
        SectionCard(title = stringResource(R.string.settings_about)) {
            KeyValueRow(stringResource(R.string.app_name), "1.0.0")
            KeyValueRow("Internet", "Not used")
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SettingsEditContent(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    modifier: Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.busy) CircularProgressIndicator()
        Messages(state)
        InfoBanner(stringResource(R.string.settings_dest_dirty_warn))

        SectionCard(title = stringResource(R.string.settings_inbound)) {
            LinePicker(
                lines = state.activeLines,
                selectedId = state.selectedSourceSubId,
                onSelect = viewModel::selectSource,
            )
        }
        SectionCard(title = stringResource(R.string.settings_outbound)) {
            LinePicker(
                lines = state.activeLines,
                selectedId = state.selectedOutboundSubId,
                onSelect = viewModel::selectOutbound,
            )
        }
        SectionCard(title = stringResource(R.string.settings_destination)) {
            OutlinedTextField(
                value = state.destinationInput,
                onValueChange = viewModel::updateDestinationInput,
                label = { Text(stringResource(R.string.destination_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.busy,
            )
            if (state.destinationChanged) {
                Text(
                    stringResource(R.string.settings_dest_must_change_auth),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Text(
                    stringResource(R.string.settings_dest_unchanged_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Button(
            onClick = viewModel::continueFromEdit,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (state.destinationChanged) {
                    stringResource(R.string.settings_continue_auth)
                } else {
                    stringResource(R.string.settings_save_lines)
                },
            )
        }
        TextButton(onClick = viewModel::cancelEdit) {
            Text(stringResource(R.string.cancel_action))
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SettingsReauthContent(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    modifier: Modifier,
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
            CircularProgressIndicator()
        }
        Messages(state)
        Text(
            stringResource(R.string.settings_reauth_headline),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            stringResource(R.string.settings_reauth_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SectionCard {
            Text(stringResource(R.string.settings_new_destination))
            Text(
                state.destinationInput,
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Button(
            onClick = {
                val activity = context as? FragmentActivity ?: return@Button
                viewModel.authenticateDestinationChange(DeviceAuthenticator(activity))
            },
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.settings_reauth_action))
        }
        TextButton(onClick = viewModel::cancelEdit) {
            Text(stringResource(R.string.cancel_action))
        }
    }
}

@Composable
private fun SettingsReverifyContent(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    modifier: Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.busy) {
            CircularProgressIndicator()
        }
        Messages(state)
        Text(
            stringResource(R.string.settings_reverify_headline),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            stringResource(R.string.settings_reverify_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SectionCard {
            KeyValueRow(
                stringResource(R.string.settings_new_destination),
                state.maskedDestination,
            )
        }
        OutlinedButton(
            onClick = viewModel::sendVerificationCode,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.destination_send_code))
        }
        OutlinedTextField(
            value = state.verificationCodeInput,
            onValueChange = viewModel::updateVerificationCode,
            label = { Text(stringResource(R.string.destination_code_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.busy,
        )
        Button(
            onClick = viewModel::confirmVerificationAndSave,
            enabled = !state.busy && state.verificationCodeInput.length == 6,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.settings_confirm_save))
        }
        TextButton(onClick = viewModel::cancelEdit) {
            Text(stringResource(R.string.settings_discard))
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun LinePicker(
    lines: List<ActiveLine>,
    selectedId: Int?,
    onSelect: (Int) -> Unit,
) {
    if (lines.isEmpty()) {
        Text(stringResource(R.string.sim_none))
        return
    }
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        lines.forEach { line ->
            val selected = line.subscriptionId == selectedId
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = selected,
                        onClick = { onSelect(line.subscriptionId) },
                        role = Role.RadioButton,
                    )
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected, onClick = { onSelect(line.subscriptionId) })
                Column(Modifier.padding(start = 8.dp)) {
                    Text(
                        formatActiveLineLabel(
                            slotIndex = line.slotIndex,
                            carrierDisplayName = line.carrierDisplayName,
                            reportedNumberE164 = line.reportedNumberE164,
                            isEmbedded = line.isEmbedded,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "subId=${line.subscriptionId}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun Messages(state: SettingsUiState) {
    state.errorMessage?.let { ErrorBanner(it) }
    state.infoMessage?.let { InfoBanner(it) }
}
