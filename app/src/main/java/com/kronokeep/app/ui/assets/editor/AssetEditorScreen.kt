package com.kronokeep.app.ui.assets.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kronokeep.app.domain.asset.AssetField
import com.kronokeep.app.domain.model.AssetCategory
import com.kronokeep.app.ui.assets.icon
import com.kronokeep.app.ui.assets.label
import com.kronokeep.app.ui.components.DateField
import com.kronokeep.app.ui.components.EmptyState
import com.kronokeep.app.ui.components.FormTextField
import com.kronokeep.app.ui.components.message
import java.time.LocalDate

object AssetEditorTags {
    const val NAME = "asset_name"
    const val PRICE = "asset_price"
    const val ODOMETER = "asset_odometer"
    const val WEEKLY_KM = "asset_weekly_km"
    const val NEXT = "wizard_next"
    fun category(category: AssetCategory) = "category_${category.name}"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssetEditorScreen(
    viewModel: AssetEditorViewModel,
    onSaved: (assetId: String) -> Unit,
    onClose: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.savedAssetId) {
        state.savedAssetId?.let {
            viewModel.onSavedHandled()
            onSaved(it)
        }
    }
    BackHandler { if (!viewModel.back()) onClose() }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            when {
                                state.isEdit -> "Edit asset"
                                else -> "Add asset"
                            },
                        )
                    },
                    navigationIcon = {
                        if (state.isEdit || state.step == WizardStep.CATEGORY) {
                            IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, contentDescription = "Close") }
                        } else {
                            IconButton(onClick = { viewModel.back() }) {
                                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Previous step")
                            }
                        }
                    },
                )
                if (!state.isEdit) {
                    LinearProgressIndicator(
                        progress = { state.stepNumber.toFloat() / state.steps.size },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        bottomBar = {
            if (state.step != WizardStep.CATEGORY && !state.notFound && !state.isLoading) {
                Surface(tonalElevation = 2.dp) {
                    Row(
                        Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                    ) {
                        if (!state.isEdit) {
                            OutlinedButton(onClick = { viewModel.back() }) { Text("Back") }
                        }
                        Button(
                            onClick = viewModel::next,
                            enabled = !state.isSaving,
                            modifier = Modifier.testTag(AssetEditorTags.NEXT),
                        ) {
                            if (state.isSaving) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Text(if (state.isLastStep) "Save" else "Next")
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        when {
            state.isLoading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.notFound -> EmptyState(
                icon = Icons.Outlined.Close,
                title = "Asset not found",
                body = "It may have been deleted.",
                modifier = Modifier.padding(padding),
            )
            else -> Column(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (!state.isEdit) {
                    Text(
                        "Step ${state.stepNumber} of ${state.steps.size}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when {
                    state.isEdit -> {
                        DetailsSection(state, viewModel)
                        if (state.isVehicle) {
                            Spacer(Modifier.padding(top = 8.dp))
                            BaselinesSection(state, viewModel)
                        }
                    }
                    state.step == WizardStep.CATEGORY -> CategoryStep(state.form.category, viewModel::selectCategory)
                    state.step == WizardStep.DETAILS -> DetailsSection(state, viewModel)
                    state.step == WizardStep.BASELINES -> BaselinesSection(state, viewModel)
                }
            }
        }
    }
}

@Composable
private fun CategoryStep(selected: AssetCategory?, onSelect: (AssetCategory) -> Unit) {
    Text("What are you adding?", style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.padding(top = 8.dp))
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AssetCategory.entries.forEach { category ->
            val isSelected = category == selected
            OutlinedCard(
                onClick = { onSelect(category) },
                border = BorderStroke(
                    if (isSelected) 2.dp else 1.dp,
                    if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                ),
                modifier = Modifier.fillMaxWidth().testTag(AssetEditorTags.category(category)),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        category.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp),
                    )
                    Column(Modifier.padding(start = 16.dp)) {
                        Text(category.label, style = MaterialTheme.typography.titleMedium)
                        Text(
                            category.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailsSection(state: AssetEditorUiState, vm: AssetEditorViewModel) {
    val form = state.form
    val errors = state.errors
    val today = LocalDate.now()
    SectionTitle("Details")
    FormTextField(
        value = form.name,
        onValueChange = { v -> vm.edit(AssetField.NAME) { it.copy(name = v) } },
        label = "Name *",
        placeholder = form.category?.namePlaceholder,
        error = errors[AssetField.NAME]?.message(),
        capitalization = KeyboardCapitalization.Words,
        testTag = AssetEditorTags.NAME,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FormTextField(
            value = form.brand,
            onValueChange = { v -> vm.edit(AssetField.BRAND) { it.copy(brand = v) } },
            label = "Brand",
            error = errors[AssetField.BRAND]?.message(),
            capitalization = KeyboardCapitalization.Words,
            modifier = Modifier.weight(1f),
        )
        FormTextField(
            value = form.model,
            onValueChange = { v -> vm.edit(AssetField.MODEL) { it.copy(model = v) } },
            label = "Model",
            error = errors[AssetField.MODEL]?.message(),
            capitalization = KeyboardCapitalization.Characters,
            modifier = Modifier.weight(1f),
        )
    }
    FormTextField(
        value = form.serialNo,
        onValueChange = { v -> vm.edit(AssetField.SERIAL) { it.copy(serialNo = v) } },
        label = if (form.category == AssetCategory.VEHICLE) "Plate / chassis number" else "Serial number",
        error = errors[AssetField.SERIAL]?.message(),
        capitalization = KeyboardCapitalization.Characters,
    )

    SectionTitle("Purchase & warranty")
    DateField(
        value = form.purchaseDate,
        onValueChange = { v -> vm.edit(AssetField.PURCHASE_DATE) { it.copy(purchaseDate = v) } },
        label = "Purchase date",
        error = errors[AssetField.PURCHASE_DATE]?.message(),
        max = today,
    )
    FormTextField(
        value = form.price,
        onValueChange = { v -> vm.edit(AssetField.PRICE) { it.copy(price = v) } },
        label = "Price",
        prefix = "₱ ",
        placeholder = "0.00",
        error = errors[AssetField.PRICE]?.message(),
        keyboardType = KeyboardType.Decimal,
        testTag = AssetEditorTags.PRICE,
    )
    DateField(
        value = form.warrantyExpiresOn,
        onValueChange = { v -> vm.edit(AssetField.WARRANTY) { it.copy(warrantyExpiresOn = v) } },
        label = "Warranty expires",
        error = errors[AssetField.WARRANTY]?.message(),
        hint = "We'll remind you before it runs out",
        min = form.purchaseDate,
    )
    FormTextField(
        value = form.notes,
        onValueChange = { v -> vm.edit(AssetField.NOTES) { it.copy(notes = v) } },
        label = "Notes",
        error = errors[AssetField.NOTES]?.message(),
        singleLine = false,
        minLines = 3,
    )
}

@Composable
private fun BaselinesSection(state: AssetEditorUiState, vm: AssetEditorViewModel) {
    val form = state.form
    SectionTitle("Usage")
    Text(
        "These let KronoKeep predict when service is due. You can update the odometer any time.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.padding(top = 8.dp))
    FormTextField(
        value = form.odometerKm,
        onValueChange = { v -> vm.edit(AssetField.ODOMETER) { it.copy(odometerKm = v) } },
        label = "Current odometer",
        suffix = "km",
        error = state.errors[AssetField.ODOMETER]?.message(),
        keyboardType = KeyboardType.Number,
        testTag = AssetEditorTags.ODOMETER,
    )
    FormTextField(
        value = form.weeklyKm,
        onValueChange = { v -> vm.edit(AssetField.WEEKLY_KM) { it.copy(weeklyKm = v) } },
        label = "Typical distance per week",
        suffix = "km/week",
        hint = "A rough guess is fine. It's replaced by real data after a few odometer updates.",
        error = state.errors[AssetField.WEEKLY_KM]?.message(),
        keyboardType = KeyboardType.Number,
        testTag = AssetEditorTags.WEEKLY_KM,
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

private val AssetCategory.description: String
    get() = when (this) {
        AssetCategory.VEHICLE -> "Motorcycle, scooter or car. Tracks oil, PMS and odometer."
        AssetCategory.APPLIANCE -> "Aircon, ref, washer, water filter. Tracks cleaning and parts."
        AssetCategory.ELECTRONIC -> "Phone, laptop, TV. Keeps the receipt and warranty."
    }

private val AssetCategory.namePlaceholder: String
    get() = when (this) {
        AssetCategory.VEHICLE -> "e.g. Honda Click 125i"
        AssetCategory.APPLIANCE -> "e.g. Bedroom aircon"
        AssetCategory.ELECTRONIC -> "e.g. Work laptop"
    }
