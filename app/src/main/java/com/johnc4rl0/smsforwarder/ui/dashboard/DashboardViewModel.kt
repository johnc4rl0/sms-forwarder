package com.johnc4rl0.smsforwarder.ui.dashboard

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
import com.johnc4rl0.smsforwarder.domain.ForwardJobRepository
import com.johnc4rl0.smsforwarder.domain.SubscriptionCatalog
import com.johnc4rl0.smsforwarder.domain.model.ForwardingConfig
import com.johnc4rl0.smsforwarder.domain.model.LineValidation
import com.johnc4rl0.smsforwarder.domain.model.OperationalState
import com.johnc4rl0.smsforwarder.domain.model.OutcomeMetadata
import com.johnc4rl0.smsforwarder.domain.model.QuotaSnapshot
import com.johnc4rl0.smsforwarder.domain.model.RuntimeSnapshot
import com.johnc4rl0.smsforwarder.ui.auth.AuthOutcome
import com.johnc4rl0.smsforwarder.ui.auth.DeviceAuthenticator
import com.johnc4rl0.smsforwarder.ui.notification.ForwardingStatusNotifier
import com.johnc4rl0.smsforwarder.telephony.SensitiveSmsPrivilege
import com.johnc4rl0.smsforwarder.ui.util.HibernationStatus
import com.johnc4rl0.smsforwarder.ui.util.allRequiredPermissionsGranted
import com.johnc4rl0.smsforwarder.ui.util.areNotificationsUsable
import com.johnc4rl0.smsforwarder.ui.util.hibernationStatus
import com.johnc4rl0.smsforwarder.ui.util.maskE164
import com.johnc4rl0.smsforwarder.ui.util.toUserLabel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HealthSnapshot(
    val permissionsOk: Boolean = false,
    val notificationsOk: Boolean = false,
    val sensitiveSmsPrivilegeOk: Boolean = false,
    val subscriptionsOk: Boolean = false,
    val hibernation: HibernationStatus = HibernationStatus.UNKNOWN,
)

data class DashboardUiState(
    val config: ForwardingConfig = ForwardingConfig(),
    val health: HealthSnapshot = HealthSnapshot(),
    val quota: QuotaSnapshot = QuotaSnapshot(0, 0, 0L),
    val outcomes: List<OutcomeMetadata> = emptyList(),
    val busy: Boolean = false,
    val errorMessage: String? = null,
    val infoMessage: String? = null,
    val maskedSource: String = "—",
    val maskedOutbound: String = "—",
    val maskedDestination: String = "—",
)

class DashboardViewModel(
    application: Application,
    private val activation: ActivationCoordinator,
    private val jobs: ForwardJobRepository,
    private val catalog: SubscriptionCatalog,
    private val statusNotifier: ForwardingStatusNotifier,
) : AndroidViewModel(application) {

    private val _meta = MutableStateFlow(
        MetaState(
            health = HealthSnapshot(),
            quota = QuotaSnapshot(0, 0, System.currentTimeMillis()),
            busy = false,
            errorMessage = null,
            infoMessage = null,
        ),
    )

    private data class MetaState(
        val health: HealthSnapshot,
        val quota: QuotaSnapshot,
        val busy: Boolean,
        val errorMessage: String?,
        val infoMessage: String?,
    )

    val ui: StateFlow<DashboardUiState> = combine(
        activation.observeConfig(),
        jobs.observeRecent(50),
        _meta,
    ) { config, outcomes, meta ->
        DashboardUiState(
            config = config,
            health = meta.health,
            quota = meta.quota,
            outcomes = outcomes,
            busy = meta.busy,
            errorMessage = meta.errorMessage,
            infoMessage = meta.infoMessage,
            maskedSource = maskE164(config.source?.effectiveNumberE164),
            maskedOutbound = maskE164(config.outbound?.effectiveNumberE164),
            maskedDestination = maskE164(config.destinationE164),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    init {
        refreshHealthAndQuota()
        viewModelScope.launch {
            activation.observeConfig().collect { cfg ->
                statusNotifier.sync(cfg)
                refreshHealthAndQuota()
            }
        }
        // PC-003: when recent outcomes change (new SENT/FAILED/etc.), re-query quota.
        viewModelScope.launch {
            jobs.observeRecent(50)
                .map { list ->
                    list.size to list.maxOfOrNull { it.finishedAtMillis }
                }
                .distinctUntilChanged()
                .collect {
                    refreshQuota()
                }
        }
    }

    fun refreshHealthAndQuota() {
        val ctx = getApplication<Application>()
        val config = ui.value.config
        val permissionsOk = ctx.allRequiredPermissionsGranted()
        val notificationsOk = ctx.areNotificationsUsable()
        val sensitiveSmsPrivilegeOk = SensitiveSmsPrivilege.privilegeOk(ctx)
        val hibernation = ctx.hibernationStatus()
        val subscriptionsOk = runCatching {
            val sourceOk = config.source?.let { catalog.validate(it) is LineValidation.Valid } ?: false
            val outboundOk =
                config.outbound?.let { catalog.validate(it) is LineValidation.Valid } ?: false
            sourceOk && outboundOk
        }.getOrDefault(false)

        _meta.update {
            it.copy(
                health = HealthSnapshot(
                    permissionsOk = permissionsOk,
                    notificationsOk = notificationsOk,
                    sensitiveSmsPrivilegeOk = sensitiveSmsPrivilegeOk,
                    subscriptionsOk = subscriptionsOk,
                    hibernation = hibernation,
                ),
            )
        }

        refreshQuota()
    }

    /** Reload rolling 24h quota from the job repository (cheap Room SUM). */
    fun refreshQuota() {
        viewModelScope.launch {
            runCatching {
                jobs.currentQuota(System.currentTimeMillis())
            }.onSuccess { snap ->
                _meta.update { it.copy(quota = snap) }
            }
        }
    }

    fun clearMessages() {
        _meta.update { it.copy(errorMessage = null, infoMessage = null) }
    }

    fun pause() {
        viewModelScope.launch {
            runAction {
                activation.pauseManual()
                statusNotifier.cancel()
                _meta.update {
                    it.copy(
                        infoMessage = getApplication<Application>()
                            .getString(R.string.state_manually_paused),
                    )
                }
            }
        }
    }

    fun reEnable(authenticator: DeviceAuthenticator) {
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            runAction {
                val result = activation.reEnable {
                    when (
                        authenticator.authenticate(
                            title = ctx.getString(R.string.dashboard_reenable_auth_title),
                            subtitle = ctx.getString(R.string.dashboard_reenable_auth_subtitle),
                        )
                    ) {
                        AuthOutcome.Success -> DeviceAuthResult.Success
                        AuthOutcome.Cancelled -> DeviceAuthResult.Cancelled
                        is AuthOutcome.Failed -> DeviceAuthResult.Failed
                    }
                }
                when (result) {
                    EnableResult.Enabled -> {
                        statusNotifier.sync(ui.value.config)
                        _meta.update {
                            it.copy(infoMessage = ctx.getString(R.string.activate_success))
                        }
                    }
                    EnableResult.AuthFailed ->
                        _meta.update {
                            it.copy(errorMessage = ctx.getString(R.string.activate_auth_failed))
                        }
                    EnableResult.AuthCancelled ->
                        _meta.update {
                            it.copy(errorMessage = ctx.getString(R.string.activate_auth_cancelled))
                        }
                    is EnableResult.Blocked ->
                        _meta.update {
                            it.copy(
                                errorMessage = ctx.getString(
                                    R.string.activate_blocked,
                                    result.reason.toUserLabel(ctx),
                                ),
                            )
                        }
                }
            }
        }
    }

    fun canPause(config: ForwardingConfig): Boolean =
        config.operationalState is OperationalState.Enabled

    fun canReEnable(config: ForwardingConfig): Boolean =
        when (config.operationalState) {
            OperationalState.ManuallyPaused,
            is OperationalState.SafetyPaused,
            is OperationalState.Unhealthy,
            -> true
            else -> false
        }

    val sourceMessageLimit: Int = RuntimeSnapshot.DEFAULT_SOURCE_MESSAGE_LIMIT
    val outboundSegmentLimit: Int = RuntimeSnapshot.DEFAULT_OUTBOUND_SEGMENT_LIMIT

    private suspend fun runAction(block: suspend () -> Unit) {
        _meta.update { it.copy(busy = true, errorMessage = null) }
        try {
            block()
        } catch (_: NotImplementedError) {
            _meta.update {
                it.copy(
                    errorMessage = getApplication<Application>().getString(R.string.error_not_ready),
                )
            }
        } catch (e: Exception) {
            _meta.update {
                it.copy(
                    errorMessage = e.message?.takeIf { m -> m.isNotBlank() }
                        ?: getApplication<Application>().getString(R.string.error_generic),
                )
            }
        } finally {
            _meta.update { it.copy(busy = false) }
            refreshHealthAndQuota()
        }
    }

    class Factory(
        private val application: Application,
        private val container: AppContainer,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return DashboardViewModel(
                application = application,
                activation = container.activationCoordinator,
                jobs = container.forwardJobRepository,
                catalog = container.subscriptionCatalog,
                statusNotifier = ForwardingStatusNotifier(application),
            ) as T
        }
    }
}
