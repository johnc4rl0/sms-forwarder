package com.johnc4rl0.smsforwarder.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.johnc4rl0.smsforwarder.R
import com.johnc4rl0.smsforwarder.di.AppContainer
import com.johnc4rl0.smsforwarder.domain.ActivationCoordinator
import com.johnc4rl0.smsforwarder.domain.SubscriptionCatalog
import com.johnc4rl0.smsforwarder.domain.VerificationConfirmResult
import com.johnc4rl0.smsforwarder.domain.VerificationSendResult
import com.johnc4rl0.smsforwarder.domain.model.ActiveLine
import com.johnc4rl0.smsforwarder.domain.model.ForwardingConfig
import com.johnc4rl0.smsforwarder.domain.model.LineSelection
import com.johnc4rl0.smsforwarder.ui.auth.AuthOutcome
import com.johnc4rl0.smsforwarder.ui.auth.DeviceAuthenticator
import com.johnc4rl0.smsforwarder.ui.util.isValidE164
import com.johnc4rl0.smsforwarder.ui.util.maskE164
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

import com.johnc4rl0.smsforwarder.domain.IdentityComparisonResult
import com.johnc4rl0.smsforwarder.domain.SubscriptionIdentity

enum class SettingsStep {
    View,
    Edit,
    ReauthDestination,
    ReverifyDestination,
}

data class SettingsUiState(
    val step: SettingsStep = SettingsStep.View,
    val config: ForwardingConfig = ForwardingConfig(),
    val activeLines: List<ActiveLine> = emptyList(),
    val selectedSourceSubId: Int? = null,
    val selectedOutboundSubId: Int? = null,
    val destinationInput: String = "",
    val verificationCodeInput: String = "",
    /** Original destination when edit started (normalized for compare). */
    val originalDestination: String? = null,
    val destinationChangeAuthenticated: Boolean = false,
    val busy: Boolean = false,
    val errorMessage: String? = null,
    val infoMessage: String? = null,
    val maskedSource: String = "—",
    val maskedOutbound: String = "—",
    val maskedDestination: String = "—",
) {
    val destinationChanged: Boolean
        get() {
            val draft = destinationInput.trim()
            val orig = originalDestination?.trim().orEmpty()
            return draft.isNotBlank() && draft != orig
        }
    val linesChanged: Boolean
        get() {
            val liveSource = activeLines.find { it.subscriptionId == selectedSourceSubId }
            val liveOutbound = activeLines.find { it.subscriptionId == selectedOutboundSubId }
            val srcDiff = selectedSourceSubId != null && (
                selectedSourceSubId != config.source?.subscriptionId ||
                SubscriptionIdentity.compare(config.source?.identityToken, liveSource?.identityToken) != IdentityComparisonResult.Same
            )
            val outDiff = selectedOutboundSubId != null && (
                selectedOutboundSubId != config.outbound?.subscriptionId ||
                SubscriptionIdentity.compare(config.outbound?.identityToken, liveOutbound?.identityToken) != IdentityComparisonResult.Same
            )
            return srcDiff || outDiff
        }
}

class SettingsViewModel(
    application: Application,
    private val activation: ActivationCoordinator,
    private val catalog: SubscriptionCatalog,
) : AndroidViewModel(application) {

    private val _ui = MutableStateFlow(SettingsUiState())

    val ui: StateFlow<SettingsUiState> = combine(
        activation.observeConfig(),
        _ui,
    ) { config, local ->
        local.copy(
            config = config,
            maskedSource = maskE164(config.source?.effectiveNumberE164),
            maskedOutbound = maskE164(config.outbound?.effectiveNumberE164),
            maskedDestination = maskE164(config.destinationE164),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    init {
        refreshLines()
    }

    fun refreshLines() {
        viewModelScope.launch {
            val lines = runCatching { catalog.listActiveLines() }.getOrDefault(emptyList())
            _ui.update { it.copy(activeLines = lines) }
        }
    }

    /** Pre-edit config snapshot, captured in [startEdit], so a discard can revert an
     *  in-progress (unverified) destination change back to the prior verified setup. */
    private var preEditSnapshot: ForwardingConfig? = null

    fun startEdit() {
        val cfg = ui.value.config
        preEditSnapshot = cfg
        _ui.update {
            it.copy(
                step = SettingsStep.Edit,
                selectedSourceSubId = cfg.source?.subscriptionId,
                selectedOutboundSubId = cfg.outbound?.subscriptionId,
                destinationInput = cfg.destinationE164.orEmpty(),
                originalDestination = cfg.destinationE164,
                verificationCodeInput = "",
                destinationChangeAuthenticated = false,
                errorMessage = null,
                infoMessage = null,
            )
        }
        refreshLines()
    }

    fun cancelEdit() {
        val snapshot = preEditSnapshot
        val discardPendingDestination = snapshot != null &&
            (ui.value.step == SettingsStep.ReauthDestination ||
                ui.value.step == SettingsStep.ReverifyDestination)
        _ui.update {
            it.copy(
                step = SettingsStep.View,
                errorMessage = null,
                infoMessage = null,
                verificationCodeInput = "",
                destinationChangeAuthenticated = false,
            )
        }
        if (discardPendingDestination) {
            preEditSnapshot = null
            // Revert the persisted (unverified) destination back to the prior setup.
            viewModelScope.launch {
                runAction {
                    activation.restoreConfig(snapshot!!)
                }
            }
        }
    }

    fun selectSource(subId: Int) {
        _ui.update { it.copy(selectedSourceSubId = subId) }
    }

    fun selectOutbound(subId: Int) {
        _ui.update { it.copy(selectedOutboundSubId = subId) }
    }

    fun updateDestinationInput(value: String) {
        _ui.update { it.copy(destinationInput = value) }
    }

    fun updateVerificationCode(value: String) {
        _ui.update { it.copy(verificationCodeInput = value.filter { ch -> ch.isDigit() }.take(6)) }
    }

    /**
     * From Edit: if destination changed → Reauth step; else apply line changes and return to View.
     */
    fun continueFromEdit() {
        val ctx = getApplication<Application>()
        val state = ui.value
        val sourceId = state.selectedSourceSubId
        val outboundId = state.selectedOutboundSubId
        if (sourceId == null || outboundId == null) {
            _ui.update { it.copy(errorMessage = ctx.getString(R.string.sim_none)) }
            return
        }
        val dest = state.destinationInput.trim()
        if (!isValidE164(dest)) {
            _ui.update { it.copy(errorMessage = ctx.getString(R.string.sim_invalid_e164)) }
            return
        }
        if (state.destinationChanged || state.linesChanged) {
            _ui.update {
                it.copy(
                    step = SettingsStep.ReauthDestination,
                    errorMessage = null,
                    infoMessage = ctx.getString(R.string.settings_dest_must_change_auth),
                )
            }
            return
        }
        // No changes made — return to View.
        _ui.update {
            it.copy(
                step = SettingsStep.View,
                errorMessage = null,
                infoMessage = null,
            )
        }
    }

    fun authenticateDestinationChange(authenticator: DeviceAuthenticator) {
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            runAction {
                val outcome = authenticator.authenticate(
                    title = ctx.getString(R.string.settings_reauth_auth_title),
                    subtitle = ctx.getString(R.string.settings_reauth_auth_subtitle),
                )
                when (outcome) {
                    AuthOutcome.Success -> {
                        val state = ui.value
                        val sourceId = state.selectedSourceSubId
                            ?: error("source required")
                        val outboundId = state.selectedOutboundSubId
                            ?: error("outbound required")
                        val dest = state.destinationInput.trim()
                        applyLineSelections(sourceId, outboundId)
                        if (state.destinationChanged) {
                            val err = activation.setDestination(dest)
                            if (err != null) {
                                _ui.update {
                                    it.copy(
                                        step = SettingsStep.Edit,
                                        errorMessage = err,
                                        destinationChangeAuthenticated = false,
                                    )
                                }
                                return@runAction
                            }
                            _ui.update {
                                it.copy(
                                    step = SettingsStep.ReverifyDestination,
                                    destinationChangeAuthenticated = true,
                                    errorMessage = null,
                                    infoMessage = null,
                                    verificationCodeInput = "",
                                )
                            }
                        } else {
                            _ui.update {
                                it.copy(
                                    step = SettingsStep.View,
                                    infoMessage = ctx.getString(R.string.settings_lines_saved),
                                    destinationChangeAuthenticated = false,
                                )
                            }
                        }
                    }
                    AuthOutcome.Cancelled ->
                        _ui.update {
                            it.copy(errorMessage = ctx.getString(R.string.activate_auth_cancelled))
                        }
                    is AuthOutcome.Failed ->
                        _ui.update {
                            it.copy(errorMessage = ctx.getString(R.string.activate_auth_failed))
                        }
                }
            }
        }
    }

    fun sendVerificationCode() {
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            runAction {
                when (val r = activation.sendVerificationCode()) {
                    VerificationSendResult.Sent ->
                        _ui.update {
                            it.copy(infoMessage = ctx.getString(R.string.destination_sent))
                        }
                    VerificationSendResult.RateLimited ->
                        _ui.update {
                            it.copy(errorMessage = ctx.getString(R.string.destination_rate_limited))
                        }
                    VerificationSendResult.DestinationMissing ->
                        _ui.update {
                            it.copy(errorMessage = ctx.getString(R.string.sim_invalid_e164))
                        }
                    VerificationSendResult.OutboundUnavailable ->
                        _ui.update {
                            it.copy(errorMessage = ctx.getString(R.string.destination_outbound_unavailable))
                        }
                    is VerificationSendResult.Failed ->
                        _ui.update {
                            it.copy(
                                errorMessage = r.message
                                    ?: ctx.getString(R.string.error_generic),
                            )
                        }
                }
            }
        }
    }

    fun confirmVerificationAndSave() {
        val ctx = getApplication<Application>()
        val code = ui.value.verificationCodeInput
        viewModelScope.launch {
            runAction {
                when (val r = activation.confirmVerificationCode(code)) {
                    VerificationConfirmResult.Verified ->
                        _ui.update {
                            it.copy(
                                step = SettingsStep.View,
                                infoMessage = ctx.getString(R.string.settings_saved_banner),
                                destinationChangeAuthenticated = false,
                                verificationCodeInput = "",
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

    private suspend fun applyLineSelections(sourceId: Int, outboundId: Int) {
        val lines = ui.value.activeLines
        val sourceLine = lines.find { it.subscriptionId == sourceId }
            ?: throw IllegalStateException("Source line missing")
        val outboundLine = lines.find { it.subscriptionId == outboundId }
            ?: throw IllegalStateException("Outbound line missing")
        val cfg = ui.value.config
        val sourceSel = lineToSelection(sourceLine, cfg.source)
        val outboundSel = lineToSelection(outboundLine, cfg.outbound)
        if (cfg.source?.subscriptionId != sourceId ||
            SubscriptionIdentity.compare(cfg.source?.identityToken, sourceSel.identityToken) != IdentityComparisonResult.Same
        ) {
            activation.setSourceLine(sourceSel)
        }
        if (cfg.outbound?.subscriptionId != outboundId ||
            SubscriptionIdentity.compare(cfg.outbound?.identityToken, outboundSel.identityToken) != IdentityComparisonResult.Same
        ) {
            activation.setOutboundLine(outboundSel)
        }
    }

    private fun lineToSelection(line: ActiveLine, previous: LineSelection?): LineSelection =
        LineSelection(
            subscriptionId = line.subscriptionId,
            slotIndex = line.slotIndex,
            carrierDisplayName = line.carrierDisplayName,
            reportedNumberE164 = line.reportedNumberE164,
            manualNumberE164 = previous
                ?.takeIf { it.subscriptionId == line.subscriptionId }
                ?.manualNumberE164,
            identityToken = line.identityToken,
        )

    private suspend fun runAction(block: suspend () -> Unit) {
        _ui.update { it.copy(busy = true, errorMessage = null) }
        try {
            block()
        } catch (e: Exception) {
            _ui.update {
                it.copy(
                    errorMessage = e.message?.takeIf { m -> m.isNotBlank() }
                        ?: getApplication<Application>().getString(R.string.error_generic),
                )
            }
        } finally {
            _ui.update { it.copy(busy = false) }
        }
    }

    class Factory(
        private val application: Application,
        private val container: AppContainer,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SettingsViewModel(
                application = application,
                activation = container.activationCoordinator,
                catalog = container.subscriptionCatalog,
            ) as T
        }
    }
}
