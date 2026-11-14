package com.kronokeep.app.ui.assets.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kronokeep.app.domain.asset.AssetError
import com.kronokeep.app.domain.asset.AssetField
import com.kronokeep.app.domain.asset.AssetValidator
import com.kronokeep.app.domain.asset.CreateAssetUseCase
import com.kronokeep.app.domain.asset.SaveAssetResult
import com.kronokeep.app.domain.asset.UpdateAssetUseCase
import com.kronokeep.app.domain.common.Dates
import com.kronokeep.app.domain.maintenance.AddSchedulesUseCase
import com.kronokeep.app.domain.maintenance.MaintenanceError
import com.kronokeep.app.domain.maintenance.MaintenanceValidator
import com.kronokeep.app.domain.maintenance.ScheduleField
import com.kronokeep.app.domain.model.AssetCategory
import com.kronokeep.app.domain.model.AssetDraft
import com.kronokeep.app.domain.model.ScheduleDraft
import com.kronokeep.app.domain.presets.Preset
import com.kronokeep.app.domain.presets.PresetTask
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
import java.time.ZoneId

enum class WizardStep { CATEGORY, DETAILS, BASELINES, SCHEDULES }

/** One preset task offered in the wizard's last step. Blank "last done" means today / the current odometer. */
data class SuggestedTask(
    val task: PresetTask,
    val selected: Boolean = true,
    val lastDoneOn: LocalDate? = null,
    val lastDoneKm: String = "",
)

data class AssetEditorUiState(
    val isEdit: Boolean,
    val step: WizardStep,
    val form: AssetForm = AssetForm(),
    val errors: Map<AssetField, AssetError> = emptyMap(),
    /** The preset whose tasks are listed, or null for "none". */
    val preset: Preset? = null,
    /** False until the schedules step has picked (or the user chose) a preset. */
    val presetChosen: Boolean = false,
    val suggestions: List<SuggestedTask> = emptyList(),
    /** Errors keyed by index into [suggestions]. */
    val scheduleErrors: Map<Int, Map<ScheduleField, MaintenanceError>> = emptyMap(),
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val notFound: Boolean = false,
    /** One-shot: set after a successful save, cleared by [AssetEditorViewModel.onSavedHandled]. */
    val savedAssetId: String? = null,
) {
    val isVehicle: Boolean get() = form.category == AssetCategory.VEHICLE

    /** Steps the add wizard walks through. Only vehicles have usage baselines; electronics have no schedules. */
    val steps: List<WizardStep>
        get() = when (form.category) {
            null, AssetCategory.VEHICLE -> WizardStep.entries
            AssetCategory.APPLIANCE -> listOf(WizardStep.CATEGORY, WizardStep.DETAILS, WizardStep.SCHEDULES)
            AssetCategory.ELECTRONIC -> listOf(WizardStep.CATEGORY, WizardStep.DETAILS)
        }

    val stepNumber: Int get() = steps.indexOf(step) + 1
    val isLastStep: Boolean get() = isEdit || step == steps.last()

    val presetOptions: List<Preset> get() = form.category?.let(Preset::forCategory).orEmpty()
}

/** Add wizard (assetId = null) or edit form (assetId set) for one asset. */
@HiltViewModel(assistedFactory = AssetEditorViewModel.Factory::class)
class AssetEditorViewModel @AssistedInject constructor(
    @Assisted private val assetId: String?,
    private val createAsset: CreateAssetUseCase,
    private val updateAsset: UpdateAssetUseCase,
    private val addSchedules: AddSchedulesUseCase,
    private val repository: AssetRepository,
    private val clock: Clock,
    private val zone: ZoneId,
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
        _state.update {
            val changed = it.form.category != category
            it.copy(
                form = it.form.copy(category = category),
                step = WizardStep.DETAILS,
                errors = emptyMap(),
                // A different category means different presets.
                presetChosen = it.presetChosen && !changed,
                preset = if (changed) null else it.preset,
                suggestions = if (changed) emptyList() else it.suggestions,
                scheduleErrors = emptyMap(),
            )
        }
    }

    /** Applies a field edit and clears that field's error. */
    fun edit(field: AssetField?, change: (AssetForm) -> AssetForm) {
        _state.update { it.copy(form = change(it.form), errors = if (field == null) it.errors else it.errors - field) }
    }

    fun choosePreset(preset: Preset?) {
        _state.update {
            it.copy(
                preset = preset,
                presetChosen = true,
                suggestions = preset?.tasks?.map(::SuggestedTask).orEmpty(),
                scheduleErrors = emptyMap(),
            )
        }
    }

    fun toggleTask(index: Int) = editTask(index) { it.copy(selected = !it.selected) }
    fun setLastDoneOn(index: Int, date: LocalDate?) = editTask(index) { it.copy(lastDoneOn = date) }
    fun setLastDoneKm(index: Int, km: String) = editTask(index) { it.copy(lastDoneKm = km) }

    private fun editTask(index: Int, change: (SuggestedTask) -> SuggestedTask) {
        _state.update { s ->
            s.copy(
                suggestions = s.suggestions.mapIndexed { i, t -> if (i == index) change(t) else t },
                scheduleErrors = s.scheduleErrors - index,
            )
        }
    }

    /** Next in the wizard, or save on the last step. */
    fun next() {
        val s = _state.value
        if (s.isSaving) return
        if (s.isLastStep) return save()
        when (s.step) {
            WizardStep.DETAILS -> {
                val errors = validate(s.form) - AssetForm.BASELINE_FIELDS
                if (errors.isNotEmpty()) return _state.update { it.copy(errors = errors) }
            }
            WizardStep.BASELINES -> {
                val errors = validate(s.form).filterKeys { it in AssetForm.BASELINE_FIELDS }
                if (errors.isNotEmpty()) return _state.update { it.copy(errors = errors) }
            }
            else -> Unit
        }
        val nextStep = s.steps.getOrNull(s.steps.indexOf(s.step) + 1) ?: return
        _state.update { it.copy(step = nextStep, errors = emptyMap()) }
        if (nextStep == WizardStep.SCHEDULES && !_state.value.presetChosen) {
            val f = _state.value.form
            choosePreset(Preset.suggest(f.category!!, f.name, f.brand, f.model))
        }
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
        val schedules = if (s.isEdit || WizardStep.SCHEDULES !in s.steps) {
            emptyList()
        } else {
            when (val built = scheduleDrafts(s, draft.normalized())) {
                is Built.Invalid -> return _state.update { it.copy(scheduleErrors = built.errors) }
                is Built.Ok -> built.drafts
            }
        }
        _state.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            val result = if (assetId == null) createAsset(draft) else updateAsset(assetId, draft)
            if (result is SaveAssetResult.Saved && schedules.isNotEmpty()) {
                // Pre-validated above, so this only fails if the asset vanished in between.
                addSchedules(result.id, schedules)
            }
            _state.update { current ->
                when (result) {
                    is SaveAssetResult.Saved -> current.copy(isSaving = false, savedAssetId = result.id)
                    is SaveAssetResult.Invalid -> current.copy(
                        isSaving = false,
                        errors = result.errors,
                        // Send the user back to the step that holds the first error.
                        step = when {
                            current.isEdit -> current.step
                            result.errors.keys.any { it !in AssetForm.BASELINE_FIELDS } -> WizardStep.DETAILS
                            else -> WizardStep.BASELINES
                        },
                    )
                    SaveAssetResult.NotFound -> current.copy(isSaving = false, notFound = true)
                }
            }
        }
    }

    fun onSavedHandled() = _state.update { it.copy(savedAssetId = null) }

    private sealed interface Built {
        data class Ok(val drafts: List<ScheduleDraft>) : Built
        data class Invalid(val errors: Map<Int, Map<ScheduleField, MaintenanceError>>) : Built
    }

    /** Turns ticked suggestions into schedule drafts and validates them before anything is written. */
    private fun scheduleDrafts(s: AssetEditorUiState, asset: AssetDraft): Built {
        val now = clock.instant()
        val errors = mutableMapOf<Int, Map<ScheduleField, MaintenanceError>>()
        val drafts = s.suggestions.mapIndexedNotNull { index, t ->
            if (!t.selected) return@mapIndexedNotNull null
            val typedKm = t.lastDoneKm.replace(",", "").replace(" ", "").removeSuffix("km").trim()
            val km = if (typedKm.isEmpty()) asset.currentOdometerKm else typedKm.toIntOrNull()
            if (typedKm.isNotEmpty() && km == null) {
                errors[index] = mapOf(ScheduleField.LAST_DONE_KM to MaintenanceError.InvalidNumber)
                return@mapIndexedNotNull null
            }
            val draft = ScheduleDraft(
                task = t.task.task,
                intervalDays = t.task.intervalDays,
                intervalKm = t.task.intervalKm,
                lastDoneAt = t.lastDoneOn?.let { Dates.instantFor(it, now, zone) } ?: now,
                lastDoneKm = km.takeIf { t.task.intervalKm != null },
            ).normalized(asset.category)
            MaintenanceValidator.validateSchedule(draft, asset.category, asset.currentOdometerKm, now)
                .takeIf { it.isNotEmpty() }
                ?.let { errors[index] = it }
            draft
        }
        return if (errors.isEmpty()) Built.Ok(drafts) else Built.Invalid(errors)
    }

    private fun validate(form: AssetForm): Map<AssetField, AssetError> = when (val parsed = form.parse()) {
        is AssetForm.Parsed.Invalid -> parsed.errors
        is AssetForm.Parsed.Ok -> AssetValidator.validate(parsed.draft.normalized(), LocalDate.now(clock))
    }
}
