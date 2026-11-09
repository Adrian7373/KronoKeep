package com.kronokeep.app.ui.assets

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Kitchen
import androidx.compose.material.icons.outlined.TwoWheeler
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kronokeep.app.domain.model.Asset
import com.kronokeep.app.domain.model.AssetCategory
import com.kronokeep.app.domain.repository.AssetRepository
import com.kronokeep.app.ui.components.EmptyState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

sealed interface AssetsUiState {
    data object Loading : AssetsUiState
    data class Loaded(val assets: List<Asset>) : AssetsUiState
}

@HiltViewModel
class AssetsViewModel @Inject constructor(repository: AssetRepository) : ViewModel() {
    val state: StateFlow<AssetsUiState> = repository.observeActive()
        .map<List<Asset>, AssetsUiState> { AssetsUiState.Loaded(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AssetsUiState.Loading)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssetsScreen(viewModel: AssetsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(topBar = { TopAppBar(title = { Text("Assets") }) }) { padding ->
        when (val s = state) {
            AssetsUiState.Loading -> Unit
            is AssetsUiState.Loaded -> if (s.assets.isEmpty()) {
                EmptyState(
                    icon = Icons.Outlined.Inventory2,
                    title = "Your vault is empty",
                    body = "Log your motorcycle, aircon or laptop once. Keep the receipt, never miss a service.",
                    modifier = Modifier.padding(padding),
                )
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 16.dp), modifier = Modifier.padding(padding)) {
                    items(s.assets, key = { it.id }) { asset ->
                        ListItem(
                            headlineContent = { Text(asset.name) },
                            supportingContent = {
                                Text(listOfNotNull(asset.brand, asset.model).joinToString(" ").ifEmpty { asset.category.label })
                            },
                            leadingContent = { Icon(asset.category.icon, contentDescription = asset.category.label) },
                        )
                    }
                }
            }
        }
    }
}

val AssetCategory.label: String
    get() = when (this) {
        AssetCategory.VEHICLE -> "Vehicle"
        AssetCategory.APPLIANCE -> "Appliance"
        AssetCategory.ELECTRONIC -> "Electronic"
    }

val AssetCategory.icon
    get() = when (this) {
        AssetCategory.VEHICLE -> Icons.Outlined.TwoWheeler
        AssetCategory.APPLIANCE -> Icons.Outlined.Kitchen
        AssetCategory.ELECTRONIC -> Icons.Outlined.Devices
    }
