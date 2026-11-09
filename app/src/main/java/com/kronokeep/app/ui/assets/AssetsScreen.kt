package com.kronokeep.app.ui.assets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kronokeep.app.domain.model.Asset
import com.kronokeep.app.domain.model.AssetCategory
import com.kronokeep.app.domain.repository.AssetRepository
import com.kronokeep.app.ui.components.EmptyState
import com.kronokeep.app.ui.components.SyncBadge
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

sealed interface AssetsUiState {
    data object Loading : AssetsUiState
    data class Loaded(val assets: List<Asset>, val showingArchived: Boolean) : AssetsUiState
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AssetsViewModel @Inject constructor(repository: AssetRepository) : ViewModel() {
    private val showArchived = MutableStateFlow(false)

    val state: StateFlow<AssetsUiState> = showArchived
        .flatMapLatest { archived ->
            val source = if (archived) repository.observeArchived() else repository.observeActive()
            source.map<List<Asset>, AssetsUiState> { AssetsUiState.Loaded(it, archived) }
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
            TopAppBar(
                title = { Text(if (archived) "Archived" else "Assets") },
                actions = {
                    IconButton(onClick = viewModel::toggleArchived) {
                        Icon(
                            if (archived) Icons.Outlined.Unarchive else Icons.Outlined.Archive,
                            contentDescription = if (archived) "Show active assets" else "Show archived assets",
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            // The empty state has its own call to action; don't show two.
            if (!archived && (state as? AssetsUiState.Loaded)?.assets?.isNotEmpty() == true) {
                ExtendedFloatingActionButton(
                    onClick = onAddAsset,
                    icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                    text = { Text("Add asset") },
                )
            }
        },
    ) { padding ->
        when (val s = state) {
            AssetsUiState.Loading -> Unit
            is AssetsUiState.Loaded -> when {
                s.assets.isEmpty() && s.showingArchived -> EmptyState(
                    icon = Icons.Outlined.Archive,
                    title = "Nothing archived",
                    body = "Sold or retired something? Archive it to keep its history without the clutter.",
                    modifier = Modifier.padding(padding),
                )
                s.assets.isEmpty() -> EmptyState(
                    icon = Icons.Outlined.Inventory2,
                    title = "Your vault is empty",
                    body = "Log your motorcycle, aircon or laptop once. Keep the receipt, never miss a service.",
                    modifier = Modifier.padding(padding),
                    action = { Button(onClick = onAddAsset) { Text("Add your first asset") } },
                )
                else -> LazyColumn(contentPadding = PaddingValues(bottom = 96.dp), modifier = Modifier.padding(padding)) {
                    items(s.assets, key = { it.id }) { asset ->
                        ListItem(
                            headlineContent = { Text(asset.name) },
                            supportingContent = {
                                Column {
                                    Text(asset.subtitle)
                                    SyncBadge(asset.syncState, Modifier.padding(top = 2.dp))
                                }
                            },
                            leadingContent = { Icon(asset.category.icon, contentDescription = asset.category.label) },
                            modifier = Modifier.clickable { onOpenAsset(asset.id) },
                        )
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
