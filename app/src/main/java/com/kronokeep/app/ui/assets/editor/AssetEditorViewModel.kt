package com.kronokeep.app.ui.assets.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kronokeep.app.domain.asset.AssetError
import com.kronokeep.app.domain.asset.AssetField
import com.kronokeep.app.domain.asset.AssetValidator
import com.kronokeep.app.domain.asset.CreateAssetUseCase
import com.kronokeep.app.domain.asset.SaveAssetResult
import com.kronokeep.app.domain.asset.UpdateAssetUseCase
import com.kronokeep.app.domain.model.AssetCategory
import com.kronokeep.app.domain.repository.AssetRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate

enum class WizardStep { CATEGORY, DETAILS, BASELINES }

data class AssetEditorUiState(
    val isEdit: Boolean,
    val step: WizardStep,
    val form: AssetForm = AssetForm(),
    val errors: Map<AssetField, AssetError> = emptyMap(),
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val notFound: Boolean = false,
    /** One-shot: set after a successful save, cleared by [AssetEditorViewModel.onSavedHandled]. */
    val savedAssetId: String? = null,
) {
    val isVehicle: Boolean get() = form.category == AssetCategory.VEHICLE

    /** Steps the add wizard walks through. Only vehicles have usage baselines. */
    val steps: List<WizardStep>
        get() = if (isVehicle || form.category == null) WizardStep.entries else listOf(WizardStep.CATEGORY, WizardStep.DETAILS)

    val stepNumber: Int get() = steps.indexOf(step) + 1
    val isLastStep: Boolean get() = isEdit || step == steps.last()
}

/** Add wizard (assetId = null) or edit form (assetId set) for one asset. */
@HiltViewModel(assistedFactory = AssetEditorViewModel.Factory::class)
class AssetEditorViewModel @AssistedInject constructor(
    @Assisted private val assetId: String?,
    private val createAsset: CreateAssetUseCase,
    private val updateAsset: UpdateAssetUseCase,
    private val repository: AssetRepository,
    private val clock: Clock,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(assetId: String?): AssetEditorViewModel
    }

    private val _state = MutableStateFlow(
        AssetEditorUiState(
            isEdit = assetId != null,
            step = if (assetId != null) WizardStep.DETAILS else WizardStep.CATEGORY,
            isLoading = assetId != null,
        ),
    )
    val state: StateFlow<AssetEditorUiState> = _state.asStateFlow()

    init {
        if (assetId != null) {
            viewModelScope.launch {
                val asset = repository.get(assetId)
                _state.update {
                    if (asset == null) it.copy(isLoading = false, notFound = true)
                    else it.copy(isLoading = false, form = AssetForm.from(asset))
                }
            }
        }
    }

    fun selectCategory(category: AssetCategory) {
        if (_state.value.isEdit) return
        _state.update { it.copy(form = it.form.copy(category = category), step = WizardStep.DETAILS, errors = emptyMap()) }
    }

    /** Applies a field edit and clears that field's error. */
    fun edit(field: AssetField?, change: (AssetForm) -> AssetForm) {
        _state.update { it.copy(form = change(it.form), errors = if (field == null) it.errors else it.errors - field) }
    }

    /** Next in the wizard, or save on the last step. */
    fun next() {
        val s = _state.value
        if (s.isSaving) return
        if (s.isLastStep) return save()
        if (s.step == WizardStep.DETAILS) {
            val errors = validate(s.form) - AssetForm.BASELINE_FIELDS
            if (errors.isNotEmpty()) return _state.update { it.copy(errors = errors) }
        }
        val nextStep = s.steps.getOrNull(s.steps.indexOf(s.step) + 1) ?: return
        _state.update { it.copy(step = nextStep, errors = emptyMap()) }
    }

    /** Returns false when there's no previous step, so the screen should close. */
    fun back(): Boolean {
        val s = _state.value
        if (s.isEdit) return false
        val index = s.steps.indexOf(s.step)
        if (index <= 0) return false
        _state.update { it.copy(step = s.steps[index - 1], errors = emptyMap()) }
        return true
    }

    fun save() {
        val s = _state.value
        if (s.isSaving || s.form.category == null) return
        val draft = when (val parsed = s.form.parse()) {
            is AssetForm.Parsed.Invalid -> return _state.update { it.copy(errors = parsed.errors) }
            is AssetForm.Parsed.Ok -> parsed.draft
        }
        _state.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            val result = if (assetId == null) createAsset(draft) else updateAsset(assetId, draft)
            _state.update { current ->
                when (result) {
                    is SaveAssetResult.Saved -> current.copy(isSaving = false, savedAssetId = result.id)
                    is SaveAssetResult.Invalid -> current.copy(
                        isSaving = false,
                        errors = result.errors,
                        // Send the user back to the step that holds the first error.
                        step = if (!current.isEdit && result.errors.keys.none { it in AssetForm.BASELINE_FIELDS }) {
                            WizardStep.DETAILS
                        } else {
                            current.step
                        },
                    )
                    SaveAssetResult.NotFound -> current.copy(isSaving = false, notFound = true)
                }
            }
        }
    }

    fun onSavedHandled() = _state.update { it.copy(savedAssetId = null) }

    private fun validate(form: AssetForm): Map<AssetField, AssetError> = when (val parsed = form.parse()) {
        is AssetForm.Parsed.Invalid -> parsed.errors
        is AssetForm.Parsed.Ok -> AssetValidator.validate(parsed.draft.normalized(), LocalDate.now(clock))
    }
}
