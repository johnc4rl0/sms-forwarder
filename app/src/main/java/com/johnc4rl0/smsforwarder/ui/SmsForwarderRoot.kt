package com.johnc4rl0.smsforwarder.ui

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.johnc4rl0.smsforwarder.di.AppContainer
import com.johnc4rl0.smsforwarder.di.appContainer
import com.johnc4rl0.smsforwarder.domain.model.ForwardingConfig
import com.johnc4rl0.smsforwarder.domain.model.OperationalState
import com.johnc4rl0.smsforwarder.ui.main.MainShellScreen
import com.johnc4rl0.smsforwarder.ui.onboarding.OnboardingScreen
import com.johnc4rl0.smsforwarder.ui.onboarding.OnboardingViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * Hosts onboarding vs main NavigationBar shell based on [ForwardingConfig] readiness.
 */
@Composable
fun SmsForwarderRoot(
    container: AppContainer = LocalContext.current.appContainer(),
    modifier: Modifier = Modifier,
) {
    val app = LocalContext.current.applicationContext as Application
    val rootVm: RootViewModel = viewModel(
        factory = RootViewModel.Factory(container),
    )
    val config by rootVm.config.collectAsStateWithLifecycle()

    if (isSetupComplete(config)) {
        MainShellScreen(container = container, modifier = modifier.fillMaxSize())
    } else {
        Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
            val onboardingVm: OnboardingViewModel = viewModel(
                factory = OnboardingViewModel.Factory(app, container),
            )
            OnboardingScreen(
                viewModel = onboardingVm,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
        }
    }
}

/**
 * Main shell once configuration is usable and the user has left pure setup
 * (any operational state other than [OperationalState.NotConfigured]).
 */
fun isSetupComplete(config: ForwardingConfig): Boolean {
    if (!config.disclosureAccepted) return false
    if (config.source == null || config.outbound == null) return false
    if (config.destinationE164.isNullOrBlank() || !config.destinationVerified) return false
    return config.operationalState !is OperationalState.NotConfigured
}

class RootViewModel(
    activation: com.johnc4rl0.smsforwarder.domain.ActivationCoordinator,
) : ViewModel() {
    val config: StateFlow<ForwardingConfig> = activation.observeConfig()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ForwardingConfig())

    class Factory(
        private val container: AppContainer,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return RootViewModel(container.activationCoordinator) as T
        }
    }
}
