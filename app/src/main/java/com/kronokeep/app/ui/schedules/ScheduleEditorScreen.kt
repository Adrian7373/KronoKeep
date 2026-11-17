package com.kronokeep.app.ui.schedules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kronokeep.app.domain.maintenance.MaintenanceError
import com.kronokeep.app.domain.maintenance.ScheduleField
import com.kronokeep.app.ui.components.DateField
import com.kronokeep.app.ui.components.EmptyState
import com.kronokeep.app.ui.components.Formats
import com.kronokeep.app.ui.components.FormTextField
import com.kronokeep.app.ui.components.KronoDefaults
import com.kronokeep.app.ui.components.KronoTopBar
import com.kronokeep.app.ui.maintenance.message
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleEditorScreen(viewModel: ScheduleEditorViewModel, onClose: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.saved) { if (state.saved) onClose() }

    Scaffold(
        topBar = {
            KronoTopBar(
                title = if (state.isEdit) "Edit schedule" else "New schedule",
                subtitle = state.assetName.ifEmpty { null },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, contentDescription = "Close") } },
            )
        },
        bottomBar = {
            if (!state.isLoading && !state.notFound) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(
                        Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(16.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        Button(onClick = viewModel::save, enabled = !state.isSaving, modifier = Modifier.height(48.dp).widthIn(min = 120.dp)) {
                            if (state.isSaving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Save")
                        }
                    }
                }
            }
        },
    ) { padding ->
        when {
            state.isLoading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.notFound -> EmptyState(Icons.Outlined.SearchOff, "Not found", "It may have been deleted.", Modifier.padding(padding))
            else -> ScheduleForm(state, viewModel, Modifier.padding(padding))
        }
    }
}

@Composable
private fun ScheduleForm(state: ScheduleEditorUiState, vm: ScheduleEditorViewModel, modifier: Modifier) {
    val f = state.form
    val e = state.errors
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        FormTextField(
            value = f.task,
            onValueChange = { v -> vm.edit(ScheduleField.TASK) { it.copy(task = v) } },
            label = "Task *",
            placeholder = if (state.isVehicle) "e.g. Brake fluid change" else "e.g. Descale the kettle",
            error = e[ScheduleField.TASK]?.message(),
            capitalization = KeyboardCapitalization.Sentences,
        )

        Section("Repeat")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FormTextField(
                value = f.interval,
                onValueChange = { v -> vm.edit(ScheduleField.INTERVAL_DAYS) { it.copy(interval = v) } },
                label = "Every",
                error = e[ScheduleField.INTERVAL_DAYS]?.message(),
                keyboardType = KeyboardType.Number,
                modifier = Modifier.weight(1f),
            )
            SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                IntervalUnit.entries.forEachIndexed { i, unit ->
                    SegmentedButton(
                        selected = f.unit == unit,
                        onClick = { vm.edit(null) { it.copy(unit = unit) } },
                        shape = SegmentedButtonDefaults.itemShape(i, IntervalUnit.entries.size),
                        colors = KronoDefaults.segmentedButtonColors(),
                        label = { Text(unit.label) },
                    )
                }
            }
        }
        if (state.isVehicle) {
            FormTextField(
                value = f.intervalKm,
                onValueChange = { v -> vm.edit(ScheduleField.INTERVAL_KM) { it.copy(intervalKm = v) } },
                label = "or every",
                suffix = "km",
                hint = "Fill one or both. Whichever comes first triggers the reminder.",
                error = e[ScheduleField.INTERVAL_KM]?.message(),
                keyboardType = KeyboardType.Number,
            )
        }

        Section("Last done")
        DateField(
            value = f.lastDoneOn,
            onValueChange = { v -> vm.edit(ScheduleField.LAST_DONE_AT) { it.copy(lastDoneOn = v) } },
            label = "Date",
            error = e[ScheduleField.LAST_DONE_AT]?.message(),
            max = LocalDate.now(),
        )
        if (state.isVehicle) {
            val kmError = e[ScheduleField.LAST_DONE_KM]
            FormTextField(
                value = f.lastDoneKm,
                onValueChange = { v -> vm.edit(ScheduleField.LAST_DONE_KM) { it.copy(lastDoneKm = v) } },
                label = "Odometer then",
                suffix = "km",
                hint = state.currentKm?.let { "Now: ${Formats.km(it)}" },
                error = if (kmError == MaintenanceError.Required) "Needed for a km interval" else kmError?.message(),
                keyboardType = KeyboardType.Number,
            )
        }
    }
}

@Composable
private fun Section(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}
