package com.kronokeep.app.ui.assets.detail

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.ui.platform.testTag
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kronokeep.app.domain.model.Asset
import com.kronokeep.app.domain.model.AssetCategory
import com.kronokeep.app.domain.model.DocumentKind
import com.kronokeep.app.ui.assets.icon
import com.kronokeep.app.ui.assets.label
import com.kronokeep.app.ui.assets.subtitle
import com.kronokeep.app.ui.components.EmptyState
import com.kronokeep.app.ui.components.Formats
import com.kronokeep.app.ui.components.SyncBadge
import com.kronokeep.app.ui.theme.KronoTheme
import com.kronokeep.app.ui.theme.SerialTextStyle
import androidx.compose.ui.text.TextStyle
import java.time.LocalDate
import java.time.temporal.ChronoUnit

private enum class DetailTab(val title: String) { SCHEDULE("Schedule"), HISTORY("History"), DOCUMENTS("Documents") }

object AssetDetailTags {
    const val LOG_SERVICE = "detail_log_service"
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AssetDetailScreen(
    viewModel: AssetDetailViewModel,
    onBack: () -> Unit,
    onEdit: (assetId: String) -> Unit,
    onOpenDocument: (documentId: String) -> Unit,
    onAddSchedule: (assetId: String) -> Unit,
    onEditSchedule: (assetId: String, scheduleId: String) -> Unit,
    onLogService: (assetId: String, scheduleId: String?) -> Unit,
    onOpenReports: (assetId: String) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val transient by viewModel.transient.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableIntStateOf(DetailTab.SCHEDULE.ordinal) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    var presetSheetOpen by rememberSaveable { mutableStateOf(false) }
    var odometerOpen by rememberSaveable { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<PendingDelete?>(null) }

    LaunchedEffect(transient.message) {
        transient.message?.let {
            viewModel.onMessageShown()
            snackbar.showSnackbar(it)
        }
    }
    LaunchedEffect(transient.closed) {
        if (transient.closed) {
            viewModel.onClosedHandled()
            onBack()
        }
    }

    val loaded = state as? AssetDetailUiState.Loaded
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(loaded?.asset?.name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    if (loaded != null) {
                        IconButton(onClick = { onEdit(loaded.asset.id) }) {
                            Icon(Icons.Outlined.Edit, contentDescription = "Edit asset")
                        }
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Outlined.MoreVert, contentDescription = "More options")
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("Verified report") },
                                    leadingIcon = { Icon(Icons.Outlined.VerifiedUser, contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        onOpenReports(loaded.asset.id)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(if (loaded.asset.isArchived) "Unarchive" else "Archive") },
                                    leadingIcon = {
                                        Icon(
                                            if (loaded.asset.isArchived) Icons.Outlined.Unarchive else Icons.Outlined.Archive,
                                            contentDescription = null,
                                        )
                                    },
                                    onClick = {
                                        menuOpen = false
                                        viewModel.toggleArchived()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Delete") },
                                    leadingIcon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        confirmDelete = true
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (loaded != null && tab == DetailTab.DOCUMENTS.ordinal) {
                ExtendedFloatingActionButton(
                    onClick = { sheetOpen = true },
                    icon = { Icon(Icons.AutoMirrored.Outlined.NoteAdd, contentDescription = null) },
                    text = { Text("Add document") },
                    expanded = !loaded.isImporting,
                )
            } else if (loaded != null) {
                ExtendedFloatingActionButton(
                    onClick = { onLogService(loaded.asset.id, null) },
                    icon = { Icon(Icons.Outlined.TaskAlt, contentDescription = null) },
                    text = { Text("Log service") },
                    modifier = Modifier.testTag(AssetDetailTags.LOG_SERVICE),
                )
            }
        },
    ) { padding ->
        when (val s = state) {
            AssetDetailUiState.Loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            AssetDetailUiState.Missing -> EmptyState(
                icon = Icons.Outlined.SearchOff,
                title = "Asset not found",
                body = "It may have been deleted on another device.",
                modifier = Modifier.padding(padding),
            )
            is AssetDetailUiState.Loaded -> LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(bottom = 96.dp),
            ) {
                item(key = "overview") { OverviewCard(s.asset) }
                stickyHeader(key = "tabs") {
                    PrimaryTabRow(selectedTabIndex = tab) {
                        DetailTab.entries.forEach { t ->
                            Tab(selected = tab == t.ordinal, onClick = { tab = t.ordinal }, text = { Text(t.title) })
                        }
                    }
                }
                when (DetailTab.entries[tab]) {
                    DetailTab.SCHEDULE -> scheduleTab(
                        asset = s.asset,
                        maintenance = s.maintenance,
                        now = s.now,
                        onUpdateOdometer = { odometerOpen = true },
                        onAddFromPreset = { presetSheetOpen = true },
                        onAddCustom = { onAddSchedule(s.asset.id) },
                        onLogService = { onLogService(s.asset.id, it) },
                        onEdit = { onEditSchedule(s.asset.id, it) },
                        onDelete = { pendingDelete = it },
                    )
                    DetailTab.HISTORY -> historyTab(s.logs, onDelete = { pendingDelete = it })
                    DetailTab.DOCUMENTS -> {
                        if (s.isImporting) item(key = "importing") { ImportingRow() }
                        if (s.documents.isEmpty() && !s.isImporting) {
                            item(key = "docs-empty") {
                                TabPlaceholder(
                                    icon = Icons.AutoMirrored.Outlined.NoteAdd,
                                    title = "No documents yet",
                                    body = "Snap the receipt and warranty card so they're here when you need a claim.",
                                )
                            }
                        }
                        items(s.documents, key = { it.id }) { doc ->
                            DocumentRow(
                                document = doc,
                                onOpen = { onOpenDocument(doc.id) },
                                onDelete = { viewModel.deleteDocument(doc.id) },
                            )
                        }
                    }
                }
            }
        }
    }

    // Launchers live here, not in the sheet: the sheet closes before the picker returns.
    var kind by rememberSaveable { mutableStateOf(DocumentKind.RECEIPT) }
    val pickers = rememberDocumentPickers(
        kind = { kind },
        newCaptureUri = viewModel::newCaptureUri,
        onCaptureCancelled = viewModel::captureCancelled,
        onPicked = viewModel::addDocument,
        onError = viewModel::showMessage,
    )
    if (sheetOpen) {
        AddDocumentSheet(
            kind = kind,
            onKindChange = { kind = it },
            pickers = pickers,
            onDismiss = { sheetOpen = false },
        )
    }

    if (presetSheetOpen && loaded != null) {
        PresetSheet(
            category = loaded.asset.category,
            existingTasks = loaded.maintenance.schedules.map { it.schedule.task }.toSet(),
            onAdd = viewModel::addPresetTasks,
            onDismiss = { presetSheetOpen = false },
        )
    }

    if (odometerOpen && loaded != null) {
        OdometerDialog(
            currentKm = loaded.maintenance.currentKm,
            error = transient.odometerError,
            saved = transient.odometerSaved,
            onSave = viewModel::saveOdometer,
            onDismiss = {
                odometerOpen = false
                viewModel.onOdometerDialogClosed()
            },
        )
    }

    pendingDelete?.let { target ->
        ConfirmDeleteDialog(
            target = target,
            onConfirm = {
                pendingDelete = null
                when (target) {
                    is PendingDelete.Schedule -> viewModel.deleteSchedule(target.id)
                    is PendingDelete.Log -> viewModel.deleteLog(target.id)
                }
            },
            onDismiss = { pendingDelete = null },
        )
    }

    if (confirmDelete && loaded != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            icon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null) },
            title = { Text("Delete ${loaded.asset.name}?") },
            text = {
                Text("It disappears from all your devices along with its documents. To keep its history, archive it instead.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.delete()
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun OverviewCard(asset: Asset) {
    val today = LocalDate.now()
    Card(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    asset.category.icon,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    // asset.subtitle falls back to the category, so only repeat the category under a real make/model.
                    val hasMakeModel = asset.brand != null || asset.model != null
                    Text(asset.subtitle, style = MaterialTheme.typography.titleMedium)
                    val caption = listOfNotNull(
                        asset.category.label.takeIf { hasMakeModel },
                        "Archived".takeIf { asset.isArchived },
                    ).joinToString(" · ")
                    if (caption.isNotEmpty()) Text(caption, style = MaterialTheme.typography.bodySmall)
                }
                SyncBadge(asset.syncState)
            }
            asset.serialNo?.let {
                Fact(
                    if (asset.category == AssetCategory.VEHICLE) "Plate / chassis" else "Serial no.",
                    it,
                    valueStyle = SerialTextStyle,
                )
            }
            if (asset.purchaseDate != null || asset.price != null) {
                Fact(
                    "Purchased",
                    listOfNotNull(asset.purchaseDate?.let(Formats::date), asset.price?.format()).joinToString(" · "),
                )
            }
            asset.warrantyExpiresOn?.let { expires ->
                val days = ChronoUnit.DAYS.between(today, expires)
                val color = when {
                    days < 0 -> KronoTheme.status.overdue
                    days <= 60 -> KronoTheme.status.dueSoon
                    else -> Color.Unspecified
                }
                Fact("Warranty", Formats.warranty(expires, today), color)
            }
            if (asset.category == AssetCategory.VEHICLE) {
                asset.currentOdometerKm?.let { Fact("Odometer", Formats.km(it)) }
                asset.weeklyKmBaseline?.let { Fact("Usage", "about ${Formats.km(it)} a week") }
            }
            asset.notes?.let { Fact("Notes", it) }
        }
    }
}

@Composable
private fun Fact(
    label: String,
    value: String,
    valueColor: Color = Color.Unspecified,
    valueStyle: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    Row {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
            modifier = Modifier.weight(0.4f),
        )
        Text(value, style = valueStyle, color = valueColor, modifier = Modifier.weight(0.6f))
    }
}

@Composable
private fun TabPlaceholder(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun ImportingRow() {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Text("Compressing and saving…", modifier = Modifier.padding(start = 12.dp))
    }
}
