package com.kronokeep.app.ui.assets.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kronokeep.app.domain.maintenance.MaintenanceError
import com.kronokeep.app.domain.maintenance.ScheduleField
import com.kronokeep.app.domain.presets.Preset
import com.kronokeep.app.ui.components.DateField
import com.kronokeep.app.ui.components.Formats
import com.kronokeep.app.ui.components.FormTextField
import com.kronokeep.app.ui.maintenance.MaintenanceFormats
import com.kronokeep.app.ui.maintenance.message
import java.time.LocalDate

object ScheduleStepTags {
    const val NO_PRESET = "preset_none"
    fun preset(p: Preset) = "preset_${p.name}"
    fun task(index: Int) = "suggested_task_$index"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SuggestedSchedulesStep(state: AssetEditorUiState, vm: AssetEditorViewModel) {
    Text("Suggested schedules", style = MaterialTheme.typography.headlineSmall)
    Text(
        "Pick what to track. Intervals follow common PH service guides and you can edit them later.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.padding(top = 8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        state.presetOptions.forEach { p ->
            FilterChip(
                selected = state.preset == p,
                onClick = { vm.choosePreset(p) },
                label = { Text(p.label) },
                colors = com.kronokeep.app.ui.components.KronoDefaults.filterChipColors(),
                modifier = Modifier.testTag(ScheduleStepTags.preset(p)),
            )
        }
        FilterChip(
            selected = state.presetChosen && state.preset == null,
            onClick = { vm.choosePreset(null) },
            label = { Text("None for now") },
            colors = com.kronokeep.app.ui.components.KronoDefaults.filterChipColors(),
            modifier = Modifier.testTag(ScheduleStepTags.NO_PRESET),
        )
    }
    Spacer(Modifier.padding(top = 8.dp))
    if (state.suggestions.isEmpty()) {
        Text(
            "No schedules yet. You can add them from the asset's Schedule tab any time.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.suggestions.forEachIndexed { index, t ->
            SuggestedTaskCard(
                task = t,
                isVehicle = state.isVehicle,
                currentKm = state.form.odometerKm,
                errors = state.scheduleErrors[index].orEmpty(),
                onToggle = { vm.toggleTask(index) },
                onLastDoneOn = { vm.setLastDoneOn(index, it) },
                onLastDoneKm = { vm.setLastDoneKm(index, it) },
                modifier = Modifier.testTag(ScheduleStepTags.task(index)),
            )
        }
    }
}

@Composable
private fun SuggestedTaskCard(
    task: SuggestedTask,
    isVehicle: Boolean,
    currentKm: String,
    errors: Map<ScheduleField, MaintenanceError>,
    onToggle: () -> Unit,
    onLastDoneOn: (LocalDate?) -> Unit,
    onLastDoneKm: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val showFields = expanded || errors.isNotEmpty()
    OutlinedCard(onClick = onToggle, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = task.selected, onCheckedChange = { onToggle() })
            Column(Modifier.weight(1f)) {
                Text(task.task.task, style = MaterialTheme.typography.titleSmall)
                Text(
                    MaintenanceFormats.interval(task.task.intervalDays, task.task.intervalKm),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (task.selected && !showFields) {
                    Text(
                        "Last done: " + (task.lastDoneOn?.let(Formats::date) ?: "today"),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (task.selected && !showFields) TextButton(onClick = { expanded = true }) { Text("Change") }
        }
        if (task.selected && showFields) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                DateField(
                    value = task.lastDoneOn,
                    onValueChange = onLastDoneOn,
                    label = "Last done",
                    hint = "Leave blank for today",
                    error = errors[ScheduleField.LAST_DONE_AT]?.message(),
                    max = LocalDate.now(),
                )
                if (isVehicle && task.task.intervalKm != null) {
                    val kmError = errors[ScheduleField.LAST_DONE_KM]
                    FormTextField(
                        value = task.lastDoneKm,
                        onValueChange = onLastDoneKm,
                        label = "Odometer when last done",
                        suffix = "km",
                        hint = if (currentKm.isBlank()) "Needed for km-based tasks" else "Leave blank for the current odometer",
                        error = when (kmError) {
                            null -> null
                            MaintenanceError.Required -> "Enter it here, or set the current odometer in the previous step"
                            else -> kmError.message()
                        },
                        keyboardType = KeyboardType.Number,
                    )
                }
            }
        }
    }
}
