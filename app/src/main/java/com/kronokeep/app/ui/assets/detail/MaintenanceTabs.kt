package com.kronokeep.app.ui.assets.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kronokeep.app.domain.maintenance.AssetMaintenance
import com.kronokeep.app.domain.model.Asset
import com.kronokeep.app.domain.model.AssetCategory
import com.kronokeep.app.domain.model.ScheduleWithPrediction
import com.kronokeep.app.domain.model.ServiceLog
import com.kronokeep.app.domain.presets.Preset
import com.kronokeep.app.domain.presets.PresetTask
import com.kronokeep.app.ui.components.Formats
import com.kronokeep.app.ui.components.FormTextField
import com.kronokeep.app.ui.components.IconBadge
import com.kronokeep.app.ui.components.KronoCard
import com.kronokeep.app.ui.components.KronoDefaults
import com.kronokeep.app.domain.maintenance.DueWindow
import com.kronokeep.app.ui.maintenance.DueWindowTimeline
import com.kronokeep.app.ui.maintenance.MaintenanceFormats
import com.kronokeep.app.ui.maintenance.StatusPill
import com.kronokeep.app.ui.theme.KronoTheme
import com.kronokeep.app.ui.theme.SerialTextStyle
import java.time.Duration
import java.time.Instant

object MaintenanceTabTags {
    const val UPDATE_ODOMETER = "update_odometer"
    const val ODOMETER_INPUT = "odometer_input"
    const val ODOMETER_SAVE = "odometer_save"
    const val ADD_FROM_PRESET = "add_from_preset"
    const val ADD_CUSTOM = "add_custom_schedule"
    fun schedule(id: String) = "schedule_$id"
}

/** What a destructive confirm dialog is about to remove. */
sealed interface PendingDelete {
    data class Schedule(val id: String, val task: String) : PendingDelete
    data class Log(val id: String) : PendingDelete
}

fun LazyListScope.scheduleTab(
    asset: Asset,
    maintenance: AssetMaintenance,
    now: Instant,
    onUpdateOdometer: () -> Unit,
    onAddFromPreset: () -> Unit,
    onAddCustom: () -> Unit,
    onLogService: (scheduleId: String) -> Unit,
    onEdit: (scheduleId: String) -> Unit,
    onDelete: (PendingDelete) -> Unit,
) {
    if (asset.category == AssetCategory.ELECTRONIC) {
        item(key = "schedule-electronic") {
            TabMessage(Icons.Outlined.Build, "Warranty only", "Electronics don't need scheduled maintenance. The warranty date is tracked above.")
        }
        return
    }
    if (asset.category == AssetCategory.VEHICLE) {
        item(key = "odometer") { OdometerCard(maintenance, now, onUpdateOdometer) }
    }
    if (maintenance.schedules.isEmpty()) {
        item(key = "schedule-empty") {
            TabMessage(Icons.Outlined.Build, "No schedules yet", "Add the tasks you want reminders for, from a preset or your own.")
        }
    }
    items(maintenance.schedules, key = { it.schedule.id }) { item ->
        ScheduleCard(
            item = item,
            now = now,
            onLogService = { onLogService(item.schedule.id) },
            onEdit = { onEdit(item.schedule.id) },
            onDelete = { onDelete(PendingDelete.Schedule(item.schedule.id, item.schedule.task)) },
        )
    }
    item(key = "schedule-actions") {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (Preset.forCategory(asset.category).isNotEmpty()) {
                OutlinedButton(onClick = onAddFromPreset, modifier = Modifier.testTag(MaintenanceTabTags.ADD_FROM_PRESET)) {
                    Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("From preset", Modifier.padding(start = 8.dp))
                }
            }
            OutlinedButton(onClick = onAddCustom, modifier = Modifier.testTag(MaintenanceTabTags.ADD_CUSTOM)) {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("Custom", Modifier.padding(start = 8.dp))
            }
        }
    }
}

fun LazyListScope.historyTab(logs: List<ServiceLog>, onDelete: (PendingDelete) -> Unit) {
    if (logs.isEmpty()) {
        item(key = "history-empty") {
            TabMessage(Icons.Outlined.History, "No service history yet", "Log oil changes, cleanings and repairs here to build a record you can hand over when you sell.")
        }
        return
    }
    val total = logs.sumOf { it.cost?.centavos ?: 0L }
    if (total > 0) {
        item(key = "history-total") {
            Text(
                "${logs.size} entries · ${com.kronokeep.app.domain.model.Peso(total).format()} spent",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
    items(logs, key = { it.id }) { log ->
        KronoCard(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), contentPadding = 0.dp) {
            LogRow(log, onDelete = { onDelete(PendingDelete.Log(log.id)) })
        }
    }
}

@Composable
private fun OdometerCard(m: AssetMaintenance, now: Instant, onUpdate: () -> Unit) {
    val last = m.lastReading
    val days = last?.let { Duration.between(it.recordedAt, now).toDays() }
    val stale = days == null || days > 7
    val s = KronoTheme.status
    KronoCard(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        color = if (stale) s.dueSoonContainer else MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(
                Icons.Outlined.Speed,
                container = if (stale) s.dueSoon.copy(alpha = 0.18f) else MaterialTheme.colorScheme.primaryContainer,
                content = if (stale) s.onDueSoonContainer else MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    m.currentKm?.let(Formats::km) ?: "No odometer yet",
                    style = SerialTextStyle.copy(fontSize = MaterialTheme.typography.titleMedium.fontSize),
                )
                Text(
                    when (days) {
                        null -> "Add a reading to predict km-based tasks"
                        0L -> "Updated today"
                        1L -> "Updated yesterday"
                        else -> "Updated $days days ago"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (stale) s.onDueSoonContainer else MaterialTheme.colorScheme.onSurface,
                )
                Text(MaintenanceFormats.rate(m.rate), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FilledTonalButton(
                onClick = onUpdate,
                colors = KronoDefaults.tonalButtonColors(),
                modifier = Modifier.testTag(MaintenanceTabTags.UPDATE_ODOMETER),
            ) { Text("Update") }
        }
    }
}

@Composable
private fun ScheduleCard(
    item: ScheduleWithPrediction,
    now: Instant,
    onLogService: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val (s, p) = item
    var menu by remember { mutableStateOf(false) }
    Surface(
        onClick = onLogService,
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).testTag(MaintenanceTabTags.schedule(s.id)),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(s.task, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f, fill = false))
                    StatusPill(p.status)
                }
                Text(MaintenanceFormats.due(p, now), style = MaterialTheme.typography.bodyLarge)
                val window = remember(p, now) { DueWindow.of(p, now) }
                if (window != null) {
                    DueWindowTimeline(window, p.status, Modifier.padding(top = 4.dp, end = 12.dp))
                    Text(
                        listOfNotNull(
                            MaintenanceFormats.window(window),
                            p.nextDueKm?.let {
                                if (s.intervalDays != null) "Or at ${Formats.km(it)}, whichever comes first." else "At ${Formats.km(it)}."
                            },
                        ).joinToString(" "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val detail = MaintenanceFormats.dueDetail(p)
                if (window == null && detail.isNotEmpty()) {
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    listOfNotNull(
                        MaintenanceFormats.interval(s.intervalDays, s.intervalKm),
                        s.lastDoneAt?.let { "last done ${Formats.date(it)}" },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "Options for ${s.task}") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Log service") },
                        leadingIcon = { Icon(Icons.Outlined.TaskAlt, contentDescription = null) },
                        onClick = { menu = false; onLogService() },
                    )
                    DropdownMenuItem(
                        text = { Text("Edit schedule") },
                        leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                        onClick = { menu = false; onEdit() },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        leadingIcon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null) },
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        }
    }
}

@Composable
private fun LogRow(log: ServiceLog, onDelete: () -> Unit) {
    ListItem(
        overlineContent = { Text(Formats.date(log.performedAt)) },
        headlineContent = { Text(log.task ?: log.notes ?: "Repair") },
        supportingContent = {
            val line = listOfNotNull(
                log.odometerKm?.let(Formats::km),
                log.cost?.format(),
                log.shop,
                log.notes.takeIf { log.task != null },
            ).joinToString(" · ")
            if (line.isNotEmpty()) Text(line)
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        trailingContent = {
            if (log.isLocked) {
                LockedPill()
            } else {
                IconButton(onClick = onDelete) { Icon(Icons.Outlined.DeleteOutline, contentDescription = "Delete entry") }
            }
        },
    )
}

/** Brass, because a locked entry is part of a verified record. */
@Composable
private fun LockedPill() {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(50),
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "Locked by a verified report" },
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(Icons.Outlined.Lock, contentDescription = null, modifier = Modifier.size(14.dp))
            Text("Locked", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun TabMessage(icon: ImageVector, title: String, body: String) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconBadge(icon, size = 64.dp)
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
fun OdometerDialog(
    currentKm: Int?,
    error: String?,
    saved: Boolean,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(saved) { if (saved) onDismiss() }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Speed, contentDescription = null) },
        title = { Text("Update odometer") },
        text = {
            FormTextField(
                value = text,
                onValueChange = { text = it },
                label = "Odometer now",
                suffix = "km",
                placeholder = currentKm?.toString(),
                hint = currentKm?.let { "Last: ${Formats.km(it)}" },
                error = error,
                keyboardType = KeyboardType.Number,
                testTag = MaintenanceTabTags.ODOMETER_INPUT,
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }, modifier = Modifier.testTag(MaintenanceTabTags.ODOMETER_SAVE)) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ConfirmDeleteDialog(target: PendingDelete, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null) },
        title = {
            Text(
                when (target) {
                    is PendingDelete.Schedule -> "Delete \"${target.task}\"?"
                    is PendingDelete.Log -> "Delete this service entry?"
                },
            )
        },
        text = {
            Text(
                when (target) {
                    is PendingDelete.Schedule -> "Reminders for it stop. Past service entries stay in the history."
                    is PendingDelete.Log -> "If it was the latest for its task, the due date goes back to the previous entry."
                },
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Pick preset tasks to add. Tasks the asset already has (same name) are shown as added. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresetSheet(
    category: AssetCategory,
    existingTasks: Set<String>,
    onAdd: (List<PresetTask>) -> Unit,
    onDismiss: () -> Unit,
) {
    var picked by remember { mutableStateOf(emptySet<PresetTask>()) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text("Add from preset", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
            Text(
                "Added as done today. Edit a schedule afterwards to set when it was really last done.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            Preset.forCategory(category).forEach { preset ->
                Text(
                    preset.label,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 24.dp, top = 12.dp),
                )
                preset.tasks.forEach { task ->
                    val added = task.task in existingTasks
                    ListItem(
                        headlineContent = { Text(task.task) },
                        supportingContent = { Text(if (added) "Already added" else MaintenanceFormats.interval(task.intervalDays, task.intervalKm)) },
                        leadingContent = {
                            Checkbox(
                                checked = added || task in picked,
                                enabled = !added,
                                onCheckedChange = { on -> picked = if (on) picked + task else picked - task },
                            )
                        },
                    )
                }
            }
            Button(
                onClick = { onAdd(picked.toList()); onDismiss() },
                enabled = picked.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
            ) { Text(if (picked.isEmpty()) "Add" else "Add ${picked.size}") }
        }
    }
}
