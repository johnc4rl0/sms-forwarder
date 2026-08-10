package com.johnc4rl0.smsforwarder.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.johnc4rl0.smsforwarder.R
import com.johnc4rl0.smsforwarder.domain.model.ForwardState
import com.johnc4rl0.smsforwarder.domain.model.OutcomeMetadata
import com.johnc4rl0.smsforwarder.ui.components.SectionCard
import com.johnc4rl0.smsforwarder.ui.dashboard.DashboardUiState
import com.johnc4rl0.smsforwarder.ui.util.toUserLabel
import java.text.DateFormat
import java.util.Date

private enum class OutcomeFilter {
    All,
    Sent,
    Failed,
    Partial,
    Unknown,
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OutcomesScreen(
    state: DashboardUiState,
    modifier: Modifier = Modifier,
) {
    var filter by rememberSaveable { mutableStateOf(OutcomeFilter.All) }
    val filtered = state.outcomes.filter { it.matches(filter) }
    val timeFmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.outcomes_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutcomeFilter.entries.forEach { f ->
                FilterChip(
                    selected = filter == f,
                    onClick = { filter = f },
                    label = {
                        Text(
                            when (f) {
                                OutcomeFilter.All -> stringResource(R.string.outcomes_filter_all)
                                OutcomeFilter.Sent -> ForwardState.SENT.name
                                OutcomeFilter.Failed -> ForwardState.FAILED.name
                                OutcomeFilter.Partial -> ForwardState.PARTIAL.name
                                OutcomeFilter.Unknown -> ForwardState.UNKNOWN.name
                            },
                        )
                    },
                )
            }
        }

        SectionCard(title = stringResource(R.string.dashboard_outcomes_section)) {
            if (filtered.isEmpty()) {
                Text(stringResource(R.string.dashboard_outcomes_empty))
            } else {
                filtered.forEach { outcome ->
                    OutcomeRow(outcome = outcome, timeFmt = timeFmt)
                }
            }
        }
    }
}

@Composable
private fun OutcomeRow(outcome: OutcomeMetadata, timeFmt: DateFormat) {
    val segs = outcome.segmentCount?.toString() ?: "—"
    val time = timeFmt.format(Date(outcome.finishedAtMillis))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Text(
            text = "${outcome.state.toUserLabel()} · segs $segs",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = stringResource(
                R.string.dashboard_outcome_row,
                outcome.errorCategory?.name ?: "OK",
                outcome.attemptCount,
                segs,
            ) + " · $time",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun OutcomeMetadata.matches(filter: OutcomeFilter): Boolean =
    when (filter) {
        OutcomeFilter.All -> true
        OutcomeFilter.Sent -> state == ForwardState.SENT
        OutcomeFilter.Failed -> state == ForwardState.FAILED
        OutcomeFilter.Partial -> state == ForwardState.PARTIAL
        OutcomeFilter.Unknown -> state == ForwardState.UNKNOWN
    }
