package com.johnc4rl0.smsforwarder.ui.onboarding

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.johnc4rl0.smsforwarder.R
import com.johnc4rl0.smsforwarder.di.AppContainer
import com.johnc4rl0.smsforwarder.domain.ActivationCoordinator
import com.johnc4rl0.smsforwarder.domain.DeviceAuthResult
import com.johnc4rl0.smsforwarder.domain.EnableResult
import com.johnc4rl0.smsforwarder.domain.SubscriptionCatalog
import com.johnc4rl0.smsforwarder.domain.VerificationConfirmResult
import com.johnc4rl0.smsforwarder.domain.VerificationSendResult
import com.johnc4rl0.smsforwarder.domain.model.ActiveLine
import com.johnc4rl0.smsforwarder.domain.model.ForwardingConfig
import com.johnc4rl0.smsforwarder.domain.model.LineSelection
import com.johnc4rl0.smsforwarder.ui.auth.AuthOutcome
import com.johnc4rl0.smsforwarder.ui.auth.DeviceAuthenticator
import com.johnc4rl0.smsforwarder.ui.notification.ForwardingStatusNotifier
import com.johnc4rl0.smsforwarder.ui.util.HibernationStatus
import com.johnc4rl0.smsforwarder.ui.util.PermissionStatus
import com.johnc4rl0.smsforwarder.ui.util.allRequiredPermissionsGranted
import com.johnc4rl0.smsforwarder.ui.util.areNotificationsUsable
import com.johnc4rl0.smsforwarder.ui.util.canAuthenticateForActivation
import com.johnc4rl0.smsforwarder.ui.util.hibernationStatus
import com.johnc4rl0.smsforwarder.ui.util.isDeviceSecureLock
import com.johnc4rl0.smsforwarder.ui.util.isValidE164
import com.johnc4rl0.smsforwarder.ui.util.maskE164
import com.johnc4rl0.smsforwarder.ui.util.permissionStatuses
import com.johnc4rl0.smsforwarder.ui.util.toUserLabel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.Disclosure,
    val config: ForwardingConfig = ForwardingConfig(),
    val busy: Boolean = false,
    val errorMessage: String? = null,
    val infoMessage: String? = null,
    val permissionStatuses: List<PermissionStatus> = emptyList(),
    val deviceSecure: Boolean = false,
    val canAuth: Boolean = false,
    val hibernation: HibernationStatus = HibernationStatus.UNKNOWN,
    val hibernationAcknowledged: Boolean = false,
    val activeLines: List<ActiveLine> = emptyList(),
    val selectedSubscriptionId: Int? = null,
    val manualNumberBySubId: Map<Int, String> = emptyMap(),
    val destinationInput: String = "",
    val verificationCodeInput: String = "",
    val destinationSaved: Boolean = false,
)

class OnboardingViewModel(
    application: Application,
    private val activation: ActivationCoordinator,
    private val catalog: SubscriptionCatalog,
    private val statusNotifier: ForwardingStatusNotifier,
) : AndroidViewModel(application) {

    private val _ui = MutableStateFlow(OnboardingUiState())
    val ui: StateFlow<OnboardingUiState> = _ui.asStateFlow()

    val config: StateFlow<ForwardingConfig> = activation.observeConfig()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ForwardingConfig())

    init {
        viewModelScope.launch {
            activation.observeConfig().collect { cfg ->
                _ui.update {
                    it.copy(
                        config = cfg,
                        destinationInput = when {
                            it.destinationInput.isNotBlank() -> it.destinationInput
                            !cfg.destinationE164.isNullOrBlank() -> cfg.destinationE164.orEmpty()
                            else -> it.destinationInput
                        },
                        destinationSaved = cfg.destinationE164 != null,
                        step = reconcileStep(it.step, cfg),
                    )
                }
                statusNotifier.sync(cfg)
            }
        }
        refreshEnvironment()
    }

    fun refreshEnvironment() {
        val ctx = getApplication<Application>()
        _ui.update {
            it.copy(
                permissionStatuses = ctx.permissionStatuses(),
                deviceSecure = ctx.isDeviceSecureLock(),
                canAuth = ctx.canAuthenticateForActivation(),
                hibernation = ctx.hibernationStatus(),
                activeLines = runCatching { catalog.listActiveLines() }.getOrDefault(emptyList()),
            )
        }
    }

    fun clearMessages() {
        _ui.update { it.copy(errorMessage = null, infoMessage = null) }
    }

    fun goBack() {
        val prev = _ui.value.step.previousOrNull() ?: return
        _ui.update { it.copy(step = prev, errorMessage = null, infoMessage = null) }
    }

    fun goNext() {
        val next = _ui.value.step.nextOrNull() ?: return
        _ui.update { it.copy(step = next, errorMessage = null, infoMessage = null) }
    }

    fun acceptDisclosure() {
        viewModelScope.launch {
            runAction {
                activation.acceptDisclosure()
                _ui.update { it.copy(step = OnboardingStep.Permissions) }
            }
        }
    }

    fun onPermissionsResult() {
        refreshEnvironment()
        val ctx = getApplication<Application>()
        if (ctx.allRequiredPermissionsGranted() && ctx.areNotificationsUsable()) {
            _ui.update {
                it.copy(
                    step = OnboardingStep.DeviceSecurity,
                    errorMessage = null,
                    infoMessage = null,
                )
            }
        } else {
            _ui.update {
                it.copy(errorMessage = ctx.getString(R.string.permissions_all_required))
            }
        }
    }

    fun continueFromPermissions() {
        onPermissionsResult()
    }

    fun acknowledgeHibernationRisk() {
        _ui.update { it.copy(hibernationAcknowledged = true) }
    }

    fun continueFromSecurity() {
        val ctx = getApplication<Application>()
        refreshEnvironment()
        val state = _ui.value
        if (!state.deviceSecure || !state.canAuth) {
            _ui.update {
                it.copy(errorMessage = ctx.getString(R.string.security_auth_missing))
            }
            return
        }
        if (state.hibernation == HibernationStatus.RISK && !state.hibernationAcknowledged) {
            _ui.update {
                it.copy(errorMessage = ctx.getString(R.string.security_hibernation_risk))
            }
            return
        }
        _ui.update {
            it.copy(step = OnboardingStep.InboundSim, errorMessage = null, infoMessage = null)
        }
    }

    fun selectLine(subscriptionId: Int) {
        _ui.update { it.copy(selectedSubscriptionId = subscriptionId) }
    }

    fun updateManualNumber(subscriptionId: Int, value: String) {
        _ui.update {
            it.copy(
                manualNumberBySubId = it.manualNumberBySubId + (subscriptionId to value),
            )
        }
    }

    fun confirmInboundSelection() {
        confirmLineSelection(inbound = true)
    }

    fun confirmOutboundSelection() {
        confirmLineSelection(inbound = false)
    }

    private fun confirmLineSelection(inbound: Boolean) {
        val ctx = getApplication<Application>()
        val state = _ui.value
        val subId = state.selectedSubscriptionId
        if (subId == null) {
            _ui.update { it.copy(errorMessage = ctx.getString(R.string.sim_none)) }
            return
        }
        val line = state.activeLines.find { it.subscriptionId == subId }
        if (line == null) {
            refreshEnvironment()
            _ui.update { it.copy(errorMessage = ctx.getString(R.string.sim_none)) }
            return
        }
        val reported = line.reportedNumberE164?.takeIf { it.isNotBlank() }
        val manual = state.manualNumberBySubId[subId]?.trim()?.takeIf { it.isNotBlank() }
        // Routing uses subscriptionId; E.164 is for display / loop checks only.
        // PC-001: manual number is required only when the OS did not report one.
        // When OS reported a number, manual entry is optional (override if wrong).
        if (manual != null && !isValidE164(manual)) {
            _ui.update { it.copy(errorMessage = ctx.getString(R.string.sim_invalid_e164)) }
            return
        }
        if (reported == null && manual == null) {
            _ui.update { it.copy(errorMessage = ctx.getString(R.string.sim_manual_required)) }
            return
        }
        val selection = LineSelection(
            subscriptionId = line.subscriptionId,
            slotIndex = line.slotIndex,
            carrierDisplayName = line.carrierDisplayName,
            reportedNumberE164 = reported,
            manualNumberE164 = if (manual != null && manual != reported) manual else null,
            identityToken = line.identityToken,
        )
        viewModelScope.launch {
            runAction {
                if (inbound) {
                    activation.setSourceLine(selection)
                    _ui.update {
                        it.copy(
                            step = OnboardingStep.OutboundSim,
                            selectedSubscriptionId = null,
                            errorMessage = null,
                            infoMessage = null,
                        )
                    }
                } else {
                    activation.setOutboundLine(selection)
                    _ui.update {
                        it.copy(
                            step = OnboardingStep.Destination,
                            selectedSubscriptionId = null,
                            errorMessage = null,
                            infoMessage = null,
                        )
                    }
                }
            }
        }
    }

    fun updateDestinationInput(value: String) {
        _ui.update { it.copy(destinationInput = value) }
    }

    fun updateVerificationCode(value: String) {
        _ui.update { it.copy(verificationCodeInput = value.filter { ch -> ch.isDigit() }.take(6)) }
    }

    fun saveDestination() {
        val ctx = getApplication<Application>()
        val e164 = _ui.value.destinationInput.trim()
        if (!isValidE164(e164)) {
            _ui.update { it.copy(errorMessage = ctx.getString(R.string.sim_invalid_e164)) }
            return
        }
        viewModelScope.launch {
            runAction {
                val err = activation.setDestination(e164)
                if (err != null) {
                    _ui.update {
                        it.copy(
                            errorMessage = err.ifBlank {
                                ctx.getString(R.string.destination_reject_local)
                            },
                            destinationSaved = false,
                        )
                    }
                } else {
                    _ui.update {
                        it.copy(
                            destinationSaved = true,
                            errorMessage = null,
                            infoMessage = null,
                        )
                    }
                }
            }
        }
    }

    fun sendVerificationCode() {
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            runAction {
                when (val result = activation.sendVerificationCode()) {
                    VerificationSendResult.Sent ->
                        _ui.update {
                            it.copy(
                                infoMessage = ctx.getString(R.string.destination_sent),
                                errorMessage = null,
                            )
                        }
                    VerificationSendResult.RateLimited ->
                        _ui.update {
                            it.copy(errorMessage = ctx.getString(R.string.destination_rate_limited))
                        }
                    VerificationSendResult.DestinationMissing ->
                        _ui.update {
                            it.copy(errorMessage = ctx.getString(R.string.destination_missing))
                        }
                    VerificationSendResult.OutboundUnavailable ->
                        _ui.update {
                            it.copy(
                                errorMessage = ctx.getString(R.string.destination_outbound_unavailable),
                            )
                        }
                    is VerificationSendResult.Failed ->
                        _ui.update {
                            it.copy(
                                errorMessage = result.message
                                    ?: ctx.getString(R.string.error_not_ready),
                            )
                        }
                }
            }
        }
    }

    fun confirmVerificationCode() {
        val ctx = getApplication<Application>()
        val code = _ui.value.verificationCodeInput
        if (code.length != 6) {
            _ui.update { it.copy(errorMessage = ctx.getString(R.string.destination_mismatch)) }
            return
        }
        viewModelScope.launch {
            runAction {
                when (val result = activation.confirmVerificationCode(code)) {
                    VerificationConfirmResult.Verified ->
                        _ui.update {
                            it.copy(
                                step = OnboardingStep.Activate,
                                infoMessage = ctx.getString(R.string.destination_verified),
                                errorMessage = null,
                            )
                        }
                    VerificationConfirmResult.Expired ->
                        _ui.update {
                            it.copy(errorMessage = ctx.getString(R.string.destination_expired))
                        }
                    VerificationConfirmResult.Mismatch ->
                        _ui.update {
                            it.copy(errorMessage = ctx.getString(R.string.destination_mismatch))
                        }
                    VerificationConfirmResult.LockedOut ->
                        _ui.update {
                            it.copy(errorMessage = ctx.getString(R.string.destination_locked))
                        }
                    VerificationConfirmResult.NoPending ->
                        _ui.update {
                            it.copy(errorMessage = ctx.getString(R.string.destination_no_pending))
                        }
                }
            }
        }
    }

    fun activate(authenticator: DeviceAuthenticator) {
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            runAction {
                val result = activation.enable {
                    when (
                        authenticator.authenticate(
                            title = ctx.getString(R.string.activate_auth_title),
                            subtitle = ctx.getString(R.string.activate_auth_subtitle),
                        )
                    ) {
                        AuthOutcome.Success -> DeviceAuthResult.Success
                        AuthOutcome.Cancelled -> DeviceAuthResult.Cancelled
                        is AuthOutcome.Failed -> DeviceAuthResult.Failed
                    }
                }
                handleEnableResult(result, ctx.getString(R.string.activate_success))
            }
        }
    }

    private fun handleEnableResult(result: EnableResult, successMessage: String) {
        val ctx = getApplication<Application>()
        when (result) {
            EnableResult.Enabled -> {
                statusNotifier.sync(_ui.value.config.copy(
                    // config flow will update; force show in case stub is partial
                ))
                _ui.update {
                    it.copy(infoMessage = successMessage, errorMessage = null)
                }
            }
            EnableResult.AuthFailed ->
                _ui.update { it.copy(errorMessage = ctx.getString(R.string.activate_auth_failed)) }
            EnableResult.AuthCancelled ->
                _ui.update {
                    it.copy(errorMessage = ctx.getString(R.string.activate_auth_cancelled))
                }
            is EnableResult.Blocked ->
                _ui.update {
                    it.copy(
                        errorMessage = ctx.getString(
                            R.string.activate_blocked,
                            result.reason.toUserLabel(ctx),
                        ),
                    )
                }
        }
    }

    private suspend fun runAction(block: suspend () -> Unit) {
        _ui.update { it.copy(busy = true, errorMessage = null) }
        try {
            block()
        } catch (e: NotImplementedError) {
            _ui.update {
                it.copy(
                    errorMessage = getApplication<Application>()
                        .getString(R.string.error_not_ready),
                )
            }
        } catch (e: Exception) {
            _ui.update {
                it.copy(
                    errorMessage = e.message?.takeIf { m -> m.isNotBlank() }
                        ?: getApplication<Application>().getString(R.string.error_generic),
                )
            }
        } finally {
            _ui.update { it.copy(busy = false) }
            refreshEnvironment()
        }
    }

    /**
     * If the user already completed earlier stages (config restored), jump past them.
     * Never force-forward past the user's current later step.
     */
    private fun reconcileStep(current: OnboardingStep, cfg: ForwardingConfig): OnboardingStep {
        val minStep = when {
            !cfg.disclosureAccepted -> OnboardingStep.Disclosure
            cfg.source == null -> OnboardingStep.InboundSim // permissions/security still re-checked
            cfg.outbound == null -> OnboardingStep.OutboundSim
            !cfg.destinationVerified -> OnboardingStep.Destination
            else -> OnboardingStep.Activate
        }
        // Only auto-advance when current is behind restored progress for disclosure.
        return if (current == OnboardingStep.Disclosure && cfg.disclosureAccepted) {
            minStep
        } else {
            current
        }
    }

    fun maskedSummary(config: ForwardingConfig): Triple<String, String, String> =
        Triple(
            maskE164(config.source?.effectiveNumberE164),
            maskE164(config.outbound?.effectiveNumberE164),
            maskE164(config.destinationE164),
        )

    class Factory(
        private val application: Application,
        private val container: AppContainer,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val notifier = ForwardingStatusNotifier(application)
            return OnboardingViewModel(
                application = application,
                activation = container.activationCoordinator,
                catalog = container.subscriptionCatalog,
                statusNotifier = notifier,
            ) as T
        }
    }
}
