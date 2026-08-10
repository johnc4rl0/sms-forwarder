package com.johnc4rl0.smsforwarder.ui.onboarding

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.johnc4rl0.smsforwarder.R
import com.johnc4rl0.smsforwarder.domain.model.ActiveLine
import com.johnc4rl0.smsforwarder.ui.auth.DeviceAuthenticator
import com.johnc4rl0.smsforwarder.ui.components.ErrorBanner
import com.johnc4rl0.smsforwarder.ui.components.InfoBanner
import com.johnc4rl0.smsforwarder.ui.components.KeyValueRow
import com.johnc4rl0.smsforwarder.ui.components.SectionCard
import com.johnc4rl0.smsforwarder.ui.components.StepScaffold
import com.johnc4rl0.smsforwarder.ui.util.HibernationStatus
import com.johnc4rl0.smsforwarder.ui.util.REQUIRED_RUNTIME_PERMISSIONS
import com.johnc4rl0.smsforwarder.ui.util.appDetailsSettingsIntent
import com.johnc4rl0.smsforwarder.ui.util.formatActiveLineLabel
import com.johnc4rl0.smsforwarder.ui.util.friendlyPermissionLabel
import com.johnc4rl0.smsforwarder.ui.util.manageUnusedAppRestrictionsIntent
import com.johnc4rl0.smsforwarder.ui.util.securitySettingsIntent

@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshEnvironment()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        viewModel.onPermissionsResult()
    }

    // imePadding keeps focused fields above the soft keyboard (PC-002).
    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        val progress = (state.step.ordinal + 1).toFloat() / OnboardingStep.entries.size
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
        )
        Text(
            text = stringResource(R.string.onboarding_title) +
                " (${state.step.ordinal + 1}/${OnboardingStep.entries.size})",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 20.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (state.busy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        state.errorMessage?.let { ErrorBanner(it, Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) }
        state.infoMessage?.let { InfoBanner(it, Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) }

        when (state.step) {
            OnboardingStep.Disclosure -> DisclosureStep(
                onAccept = viewModel::acceptDisclosure,
                busy = state.busy,
            )
            OnboardingStep.Permissions -> PermissionsStep(
                statuses = state.permissionStatuses,
                onRequest = {
                    permissionLauncher.launch(REQUIRED_RUNTIME_PERMISSIONS.toTypedArray())
                },
                onOpenSettings = {
                    context.startActivity(context.appDetailsSettingsIntent())
                },
                onContinue = viewModel::continueFromPermissions,
                onBack = viewModel::goBack,
                busy = state.busy,
            )
            OnboardingStep.DeviceSecurity -> DeviceSecurityStep(
                deviceSecure = state.deviceSecure,
                canAuth = state.canAuth,
                hibernation = state.hibernation,
                hibernationAcknowledged = state.hibernationAcknowledged,
                onOpenSecurity = {
                    context.startActivity(context.securitySettingsIntent())
                },
                onOpenHibernation = {
                    context.startActivity(context.manageUnusedAppRestrictionsIntent())
                },
                onAcknowledgeHibernation = viewModel::acknowledgeHibernationRisk,
                onContinue = viewModel::continueFromSecurity,
                onBack = viewModel::goBack,
                onRefresh = viewModel::refreshEnvironment,
                busy = state.busy,
            )
            OnboardingStep.InboundSim -> SimSelectionStep(
                title = stringResource(R.string.sim_inbound_title),
                body = stringResource(R.string.sim_inbound_body),
                lines = state.activeLines,
                selectedId = state.selectedSubscriptionId,
                manualNumbers = state.manualNumberBySubId,
                onSelect = viewModel::selectLine,
                onManualNumber = viewModel::updateManualNumber,
                onRefresh = viewModel::refreshEnvironment,
                onConfirm = viewModel::confirmInboundSelection,
                onBack = viewModel::goBack,
                busy = state.busy,
            )
            OnboardingStep.OutboundSim -> SimSelectionStep(
                title = stringResource(R.string.sim_outbound_title),
                body = stringResource(R.string.sim_outbound_body),
                lines = state.activeLines,
                selectedId = state.selectedSubscriptionId,
                manualNumbers = state.manualNumberBySubId,
                onSelect = viewModel::selectLine,
                onManualNumber = viewModel::updateManualNumber,
                onRefresh = viewModel::refreshEnvironment,
                onConfirm = viewModel::confirmOutboundSelection,
                onBack = viewModel::goBack,
                busy = state.busy,
            )
            OnboardingStep.Destination -> DestinationStep(
                state = state,
                onDestinationChange = viewModel::updateDestinationInput,
                onSave = viewModel::saveDestination,
                onCodeChange = viewModel::updateVerificationCode,
                onSendCode = viewModel::sendVerificationCode,
                onConfirmCode = viewModel::confirmVerificationCode,
                onBack = viewModel::goBack,
            )
            OnboardingStep.Activate -> ActivateStep(
                state = state,
                onActivate = {
                    val activity = context as? FragmentActivity
                    if (activity != null) {
                        viewModel.activate(DeviceAuthenticator(activity))
                    }
                },
                onBack = viewModel::goBack,
            )
        }
    }
}

@Composable
private fun DisclosureStep(
    onAccept: () -> Unit,
    busy: Boolean,
) {
    StepScaffold(title = stringResource(R.string.disclosure_title)) {
        Text(
            text = stringResource(R.string.disclosure_body),
            style = MaterialTheme.typography.bodyLarge,
        )
        InfoBanner(stringResource(R.string.disclosure_otp_note))
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onAccept,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.disclosure_accept))
        }
    }
}

@Composable
private fun PermissionsStep(
    statuses: List<com.johnc4rl0.smsforwarder.ui.util.PermissionStatus>,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
    busy: Boolean,
) {
    StepScaffold(title = stringResource(R.string.permissions_title)) {
        Text(stringResource(R.string.permissions_body), style = MaterialTheme.typography.bodyLarge)
        SectionCard {
            statuses.forEach { status ->
                KeyValueRow(
                    label = friendlyPermissionLabel(status.permission),
                    value = if (status.granted) {
                        stringResource(R.string.permissions_granted)
                    } else {
                        stringResource(R.string.permissions_denied)
                    },
                )
            }
        }
        InfoBanner(stringResource(R.string.permissions_hard_restricted_hint))
        Button(onClick = onRequest, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.permissions_request))
        }
        OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.permissions_open_settings))
        }
        NavigationRow(onBack = onBack, onContinue = onContinue, continueEnabled = !busy)
    }
}

@Composable
private fun DeviceSecurityStep(
    deviceSecure: Boolean,
    canAuth: Boolean,
    hibernation: HibernationStatus,
    hibernationAcknowledged: Boolean,
    onOpenSecurity: () -> Unit,
    onOpenHibernation: () -> Unit,
    onAcknowledgeHibernation: () -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    busy: Boolean,
) {
    StepScaffold(title = stringResource(R.string.security_title)) {
        Text(stringResource(R.string.security_body), style = MaterialTheme.typography.bodyLarge)
        SectionCard {
            Text(
                if (deviceSecure) {
                    stringResource(R.string.security_lock_ok)
                } else {
                    stringResource(R.string.security_lock_missing)
                },
            )
            Text(
                if (canAuth) {
                    stringResource(R.string.security_auth_ok)
                } else {
                    stringResource(R.string.security_auth_missing)
                },
            )
        }
        if (!deviceSecure || !canAuth) {
            OutlinedButton(onClick = onOpenSecurity, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.security_open_lock_settings))
            }
        }

        SectionCard(title = stringResource(R.string.security_hibernation_title)) {
            Text(stringResource(R.string.security_hibernation_body))
            Text(
                when (hibernation) {
                    HibernationStatus.SAFE, HibernationStatus.NOT_APPLICABLE ->
                        stringResource(R.string.security_hibernation_ok)
                    HibernationStatus.RISK ->
                        stringResource(R.string.security_hibernation_risk)
                    HibernationStatus.UNKNOWN ->
                        stringResource(R.string.security_hibernation_unknown)
                },
            )
        }
        OutlinedButton(onClick = onOpenHibernation, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.security_open_hibernation))
        }
        if (hibernation == HibernationStatus.RISK && !hibernationAcknowledged) {
            TextButton(onClick = onAcknowledgeHibernation) {
                Text(stringResource(R.string.security_continue_anyway))
            }
        }
        OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.retry_action))
        }
        val canContinue = deviceSecure && canAuth &&
            (hibernation != HibernationStatus.RISK || hibernationAcknowledged)
        NavigationRow(onBack = onBack, onContinue = onContinue, continueEnabled = canContinue && !busy)
    }
}

@Composable
private fun SimSelectionStep(
    title: String,
    body: String,
    lines: List<ActiveLine>,
    selectedId: Int?,
    manualNumbers: Map<Int, String>,
    onSelect: (Int) -> Unit,
    onManualNumber: (Int, String) -> Unit,
    onRefresh: () -> Unit,
    onConfirm: () -> Unit,
    onBack: () -> Unit,
    busy: Boolean,
) {
    StepScaffold(title = title) {
        Text(body, style = MaterialTheme.typography.bodyLarge)
        if (lines.isEmpty()) {
            ErrorBanner(stringResource(R.string.sim_none))
        } else {
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                lines.forEach { line ->
                    val selected = line.subscriptionId == selectedId
                    SectionCard {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = selected,
                                    onClick = { onSelect(line.subscriptionId) },
                                    role = Role.RadioButton,
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selected,
                                onClick = { onSelect(line.subscriptionId) },
                            )
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
                        if (selected) {
                            // PC-001: manual E.164 only when OS number missing, or user opts in
                            // because the displayed OS number is wrong.
                            val osNumberMissing = line.reportedNumberE164.isNullOrBlank()
                            var showOverride by rememberSaveable(line.subscriptionId) {
                                mutableStateOf(osNumberMissing)
                            }
                            // If OS later reports a number, keep override open only if user typed one.
                            val hasManualInput =
                                !manualNumbers[line.subscriptionId].orEmpty().isBlank()
                            val showField = osNumberMissing || showOverride || hasManualInput

                            if (osNumberMissing) {
                                Text(
                                    stringResource(R.string.sim_manual_required),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                Text(
                                    stringResource(R.string.sim_manual_optional_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (!showField) {
                                    TextButton(
                                        onClick = { showOverride = true },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text(stringResource(R.string.sim_os_number_wrong))
                                    }
                                }
                            }

                            if (showField) {
                                OutlinedTextField(
                                    value = manualNumbers[line.subscriptionId].orEmpty(),
                                    onValueChange = { onManualNumber(line.subscriptionId, it) },
                                    label = {
                                        Text(stringResource(R.string.sim_manual_number_label))
                                    },
                                    placeholder = {
                                        Text(stringResource(R.string.sim_manual_number_hint))
                                    },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                if (!osNumberMissing) {
                                    TextButton(
                                        onClick = {
                                            onManualNumber(line.subscriptionId, "")
                                            showOverride = false
                                        },
                                    ) {
                                        Text(stringResource(R.string.sim_use_os_number))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.sim_refresh))
        }
        NavigationRow(
            onBack = onBack,
            onContinue = onConfirm,
            continueLabel = stringResource(R.string.sim_select),
            continueEnabled = selectedId != null && !busy,
        )
    }
}

@Composable
private fun DestinationStep(
    state: OnboardingUiState,
    onDestinationChange: (String) -> Unit,
    onSave: () -> Unit,
    onCodeChange: (String) -> Unit,
    onSendCode: () -> Unit,
    onConfirmCode: () -> Unit,
    onBack: () -> Unit,
) {
    StepScaffold(title = stringResource(R.string.destination_title)) {
        Text(stringResource(R.string.destination_body), style = MaterialTheme.typography.bodyLarge)
        OutlinedTextField(
            value = state.destinationInput,
            onValueChange = onDestinationChange,
            label = { Text(stringResource(R.string.destination_label)) },
            placeholder = { Text(stringResource(R.string.destination_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.busy,
        )
        Button(onClick = onSave, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.destination_save))
        }

        if (state.destinationSaved || state.config.destinationE164 != null) {
            SectionCard(title = stringResource(R.string.destination_verify_title)) {
                Text(stringResource(R.string.destination_verify_body))
                Button(onClick = onSendCode, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.destination_send_code))
                }
                OutlinedTextField(
                    value = state.verificationCodeInput,
                    onValueChange = onCodeChange,
                    label = { Text(stringResource(R.string.destination_code_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.busy,
                )
                Button(
                    onClick = onConfirmCode,
                    enabled = !state.busy && state.verificationCodeInput.length == 6,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.destination_confirm))
                }
            }
        }
        TextButton(onClick = onBack) { Text(stringResource(R.string.back_action)) }
    }
}

@Composable
private fun ActivateStep(
    state: OnboardingUiState,
    onActivate: () -> Unit,
    onBack: () -> Unit,
) {
    val (source, outbound, dest) = Triple(
        state.config.source?.effectiveNumberE164,
        state.config.outbound?.effectiveNumberE164,
        state.config.destinationE164,
    )
    StepScaffold(title = stringResource(R.string.activate_title)) {
        Text(stringResource(R.string.activate_body), style = MaterialTheme.typography.bodyLarge)
        InfoBanner(stringResource(R.string.disclosure_otp_note))
        SectionCard {
            Text(
                stringResource(
                    R.string.activate_summary_source,
                    com.johnc4rl0.smsforwarder.ui.util.maskE164(source),
                ),
            )
            Text(
                stringResource(
                    R.string.activate_summary_outbound,
                    com.johnc4rl0.smsforwarder.ui.util.maskE164(outbound),
                ),
            )
            Text(
                stringResource(
                    R.string.activate_summary_destination,
                    com.johnc4rl0.smsforwarder.ui.util.maskE164(dest),
                ),
            )
        }
        if (state.busy) {
            CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
        }
        Button(
            onClick = onActivate,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.activate_action))
        }
        TextButton(onClick = onBack) { Text(stringResource(R.string.back_action)) }
    }
}

@Composable
private fun NavigationRow(
    onBack: () -> Unit,
    onContinue: () -> Unit,
    continueLabel: String = stringResource(R.string.continue_action),
    continueEnabled: Boolean = true,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.back_action)) }
        Button(onClick = onContinue, enabled = continueEnabled) {
            Text(continueLabel)
        }
    }
}
