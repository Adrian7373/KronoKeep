package com.kronokeep.app.ui.assets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Kitchen
import androidx.compose.material.icons.outlined.TwoWheeler
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kronokeep.app.domain.common.Dates
import com.kronokeep.app.domain.dashboard.AssetTag
import com.kronokeep.app.domain.dashboard.AssetTags
import com.kronokeep.app.domain.maintenance.ObserveAllMaintenanceUseCase
import com.kronokeep.app.domain.model.Asset
import com.kronokeep.app.domain.model.AssetCategory
import com.kronokeep.app.domain.repository.AssetRepository
import com.kronokeep.app.ui.components.EmptyState
import com.kronokeep.app.ui.components.KronoTopBar
import com.kronokeep.app.ui.maintenance.MaintenanceFormats
import com.kronokeep.app.ui.settings.ThemeToggleButton
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject

sealed interface AssetsUiState {
    data object Loading : AssetsUiState
    data class Loaded(val tags: List<AssetTag>, val showingArchived: Boolean, val now: Instant) : AssetsUiState
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AssetsViewModel @Inject constructor(
    repository: AssetRepository,
    observeAll: ObserveAllMaintenanceUseCase,
    clock: Clock,
    zone: ZoneId,
) : ViewModel() {
    private val showArchived = MutableStateFlow(false)

    // Active tags carry live predictions (same source as the dashboard); archived assets get no reminders.
    val state: StateFlow<AssetsUiState> = showArchived
        .flatMapLatest { archived ->
            if (archived) {
                repository.observeArchived().map { AssetsUiState.Loaded(AssetTags.plain(it), true, clock.instant()) }
            } else {
                observeAll().map { data ->
                    val now = clock.instant()
                    AssetsUiState.Loaded(AssetTags.from(data, Dates.localDate(now, zone)), false, now)
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AssetsUiState.Loading)

    fun toggleArchived() {
        showArchived.value = !showArchived.value
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssetsScreen(
    onAddAsset: () -> Unit,
    onOpenAsset: (String) -> Unit,
    viewModel: AssetsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val archived = (state as? AssetsUiState.Loaded)?.showingArchived == true
    Scaffold(
        topBar = {
            val loaded = state as? AssetsUiState.Loaded
            KronoTopBar(
                title = if (archived) "Archived" else "Assets",
                subtitle = loaded?.let { s ->
                    val count = MaintenanceFormats.plural(s.tags.size, "asset")
                    if (archived) "$count, kept for their history" else "$count in your vault"
                },
                actions = {
                    IconButton(onClick = viewModel::toggleArchived) {
                        Icon(
                            if (archived) Icons.Outlined.Unarchive else Icons.Outlined.Archive,
                            contentDescription = if (archived) "Show active assets" else "Show archived assets",
                        )
                    }
                    ThemeToggleButton()
                },
            )
        },
        floatingActionButton = {
            // The empty state has its own call to action; don't show two.
            if (!archived && (state as? AssetsUiState.Loaded)?.tags?.isNotEmpty() == true) {
                ExtendedFloatingActionButton(
                    onClick = onAddAsset,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                    text = { Text("Add asset") },
                )
            }
        },
    ) { padding ->
        when (val s = state) {
            AssetsUiState.Loading -> Unit
            is AssetsUiState.Loaded -> when {
                s.tags.isEmpty() && s.showingArchived -> EmptyState(
                    icon = Icons.Outlined.Archive,
                    title = "Nothing archived",
                    body = "Sold or retired something? Archive it to keep its history without the clutter.",
                    modifier = Modifier.padding(padding),
                )
                s.tags.isEmpty() -> EmptyState(
                    icon = Icons.Outlined.Inventory2,
                    title = "Your vault is empty",
                    body = "Log your motorcycle, aircon or laptop once. Keep the receipt, never miss a service.",
                    modifier = Modifier.padding(padding),
                    action = { Button(onClick = onAddAsset) { Text("Add your first asset") } },
                )
                else -> LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(padding),
                ) {
                    items(s.tags, key = { it.asset.id }) { tag ->
                        AssetTagCard(tag, s.now, onClick = { onOpenAsset(tag.asset.id) })
                    }
                }
            }
        }
    }
}

val Asset.subtitle: String
    get() = listOfNotNull(brand, model).joinToString(" ").ifEmpty { category.label }

val AssetCategory.label: String
    get() = when (this) {
        AssetCategory.VEHICLE -> "Vehicle"
        AssetCategory.APPLIANCE -> "Appliance"
        AssetCategory.ELECTRONIC -> "Electronic"
    }

val AssetCategory.icon: ImageVector
    get() = when (this) {
        AssetCategory.VEHICLE -> Icons.Outlined.TwoWheeler
        AssetCategory.APPLIANCE -> Icons.Outlined.Kitchen
        AssetCategory.ELECTRONIC -> Icons.Outlined.Devices
    }
