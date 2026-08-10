package com.johnc4rl0.smsforwarder.ui.main

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.johnc4rl0.smsforwarder.R
import com.johnc4rl0.smsforwarder.di.AppContainer
import com.johnc4rl0.smsforwarder.ui.dashboard.DashboardViewModel
import com.johnc4rl0.smsforwarder.ui.settings.SettingsScreen
import com.johnc4rl0.smsforwarder.ui.settings.SettingsStep
import com.johnc4rl0.smsforwarder.ui.settings.SettingsViewModel
import kotlinx.coroutines.delay

/**
 * Post-onboarding shell: Material 3 NavigationBar with Status, Outcomes, Settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainShellScreen(
    container: AppContainer,
    modifier: Modifier = Modifier,
) {
    val app = LocalContext.current.applicationContext as Application
    val dashVm: DashboardViewModel = viewModel(
        factory = DashboardViewModel.Factory(app, container),
    )
    val settingsVm: SettingsViewModel = viewModel(
        factory = SettingsViewModel.Factory(app, container),
    )
    val dashState by dashVm.ui.collectAsStateWithLifecycle()
    val settingsState by settingsVm.ui.collectAsStateWithLifecycle()

    var tab by rememberSaveable { mutableStateOf(MainTab.Status) }
    // Keep user in Settings sub-flow until they return to View.
    val inSettingsSubflow = settingsState.step != SettingsStep.View

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                dashVm.refreshHealthAndQuota()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) {
        while (true) {
            dashVm.refreshHealthAndQuota()
            delay(2_000)
        }
    }

    val title = when {
        tab == MainTab.Settings && settingsState.step == SettingsStep.Edit ->
            stringResource(R.string.settings_edit_title)
        tab == MainTab.Settings && settingsState.step == SettingsStep.ReauthDestination ->
            stringResource(R.string.settings_reauth_title)
        tab == MainTab.Settings && settingsState.step == SettingsStep.ReverifyDestination ->
            stringResource(R.string.settings_reverify_title)
        tab == MainTab.Status -> stringResource(R.string.status_title)
        tab == MainTab.Outcomes -> stringResource(R.string.outcomes_title)
        else -> stringResource(R.string.settings_title)
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
        topBar = {
            TopAppBar(title = { Text(title) })
        },
        bottomBar = {
            if (!inSettingsSubflow) {
                NavigationBar {
                    NavigationBarItem(
                        selected = tab == MainTab.Status,
                        onClick = { tab = MainTab.Status },
                        icon = { Icon(Icons.Outlined.Speed, contentDescription = null) },
                        label = { Text(stringResource(R.string.nav_status)) },
                    )
                    NavigationBarItem(
                        selected = tab == MainTab.Outcomes,
                        onClick = { tab = MainTab.Outcomes },
                        icon = { Icon(Icons.AutoMirrored.Outlined.List, contentDescription = null) },
                        label = { Text(stringResource(R.string.nav_outcomes)) },
                    )
                    NavigationBarItem(
                        selected = tab == MainTab.Settings,
                        onClick = { tab = MainTab.Settings },
                        icon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                        label = { Text(stringResource(R.string.nav_settings)) },
                    )
                }
            }
        },
    ) { padding ->
        val contentMod = Modifier
            .padding(padding)
            .fillMaxSize()
        when (tab) {
            MainTab.Status -> StatusScreen(
                viewModel = dashVm,
                state = dashState,
                modifier = contentMod,
            )
            MainTab.Outcomes -> OutcomesScreen(
                state = dashState,
                modifier = contentMod,
            )
            MainTab.Settings -> SettingsScreen(
                viewModel = settingsVm,
                modifier = contentMod,
            )
        }
    }
}
