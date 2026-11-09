package com.kronokeep.app.ui.service

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kronokeep.app.domain.maintenance.VisitField
import com.kronokeep.app.ui.components.DateField
import com.kronokeep.app.ui.components.EmptyState
import com.kronokeep.app.ui.components.Formats
import com.kronokeep.app.ui.components.FormTextField
import com.kronokeep.app.ui.maintenance.StatusPill
import com.kronokeep.app.ui.maintenance.message
import java.time.LocalDate

object LogServiceTags {
    const val ODOMETER = "log_odometer"
    const val COST = "log_cost"
    const val SHOP = "log_shop"
    const val NOTES = "log_notes"
    const val SAVE = "log_save"
    fun task(id: String) = "log_task_$id"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogServiceScreen(viewModel: LogServiceViewModel, onDone: (savedCount: Int) -> Unit, onClose: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.savedCount) { state.savedCount?.let(onDone) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Log service")
                        if (state.assetName.isNotEmpty()) {
                            Text(state.assetName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, contentDescription = "Close") } },
            )
        },
        bottomBar = {
            if (!state.isLoading && !state.notFound) {
                Surface(tonalElevation = 2.dp) {
                    Row(
                        Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(16.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        Button(onClick = viewModel::save, enabled = !state.isSaving, modifier = Modifier.testTag(LogServiceTags.SAVE)) {
                            if (state.isSaving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Save")
                        }
                    }
                }
            }
        },
    ) { padding ->
        when {
            state.isLoading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.notFound -> EmptyState(Icons.Outlined.SearchOff, "Asset not found", "It may have been deleted.", Modifier.padding(padding))
            else -> VisitForm(state, viewModel, Modifier.padding(padding))
        }
    }
}

@Composable
private fun VisitForm(state: LogServiceUiState, vm: LogServiceViewModel, modifier: Modifier) {
    val f = state.form
    val e = state.errors
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (state.tasks.isNotEmpty()) {
            Section("What was done")
            state.tasks.forEach { t ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .toggleable(value = t.id in f.ticked, role = Role.Checkbox, onValueChange = { vm.toggle(t.id) })
                        .testTag(LogServiceTags.task(t.id))
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = t.id in f.ticked, onCheckedChange = null, modifier = Modifier.padding(12.dp))
                    Text(t.task, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    StatusPill(t.status)
                }
            }
        }

        FormTextField(
            value = f.notes,
            onValueChange = { v -> vm.edit(VisitField.NOTES) { it.copy(notes = v) } },
            label = if (state.tasks.isEmpty()) "What was done *" else "Notes or other repairs",
            placeholder = "e.g. Replaced brake pads",
            error = e[VisitField.NOTES]?.message(),
            singleLine = false,
            minLines = 2,
            testTag = LogServiceTags.NOTES,
        )

        Section("When and where")
        DateField(
            value = f.date,
            onValueChange = { v -> v?.let { d -> vm.edit(VisitField.PERFORMED_AT) { it.copy(date = d) } } },
            label = "Date",
            error = e[VisitField.PERFORMED_AT]?.message(),
            max = LocalDate.now(),
        )
        if (state.isVehicle) {
            FormTextField(
                value = f.odometer,
                onValueChange = { v -> vm.edit(VisitField.ODOMETER) { it.copy(odometer = v) } },
                label = "Odometer at service",
                suffix = "km",
                placeholder = state.currentKm?.toString(),
                hint = state.currentKm?.let { "Last reading: ${Formats.km(it)}" },
                error = e[VisitField.ODOMETER]?.message(),
                keyboardType = KeyboardType.Number,
                testTag = LogServiceTags.ODOMETER,
            )
        }
        FormTextField(
            value = f.cost,
            onValueChange = { v -> vm.edit(VisitField.COST) { it.copy(cost = v) } },
            label = "Total cost",
            prefix = "₱ ",
            placeholder = "0.00",
            error = e[VisitField.COST]?.message(),
            keyboardType = KeyboardType.Decimal,
            testTag = LogServiceTags.COST,
        )
        FormTextField(
            value = f.shop,
            onValueChange = { v -> vm.edit(VisitField.SHOP) { it.copy(shop = v) } },
            label = "Shop or technician",
            error = e[VisitField.SHOP]?.message(),
            capitalization = KeyboardCapitalization.Words,
            testTag = LogServiceTags.SHOP,
        )
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
