package com.kronokeep.app.ui.assets.editor

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kronokeep.app.data.document.CaptureFiles
import com.kronokeep.app.domain.capture.ReceiptScan
import com.kronokeep.app.domain.capture.ReceiptScanResult
import com.kronokeep.app.domain.capture.ScanReceiptUseCase
import com.kronokeep.app.domain.document.AddDocumentResult
import com.kronokeep.app.domain.document.AddDocumentUseCase
import com.kronokeep.app.domain.model.DocumentKind
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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

/**
 * A receipt photo picked or taken in the editor. It's read on the device, reviewed, and (unless the user
 * opts out) attached to the asset as a RECEIPT document once the asset is saved.
 */
data class ReceiptState(
    val uri: String,
    /** Our own FileProvider capture, which we delete once it's attached or dropped. */
    val isCapture: Boolean,
    val scanning: Boolean = true,
    val scan: ReceiptScan? = null,
    val selected: Set<ReceiptField> = emptySet(),
    /** Found fields whose current form value would be overwritten. */
    val conflicts: Set<ReceiptField> = emptySet(),
    val reviewOpen: Boolean = false,
    val applied: Boolean = false,
    val attach: Boolean = true,
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
    val receipt: ReceiptState? = null,
    /** One-shot snackbar text, cleared by [AssetEditorViewModel.onMessageShown]. */
    val message: String? = null,
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
    private val scanReceipt: ScanReceiptUseCase,
    private val addDocument: AddDocumentUseCase,
    private val captures: CaptureFiles,
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

    // --- Receipt scan --------------------------------------------------------------------------------

    private var scanJob: Job? = null

    fun newCaptureUri(): String = captures.newCaptureUri()

    fun onCaptureCancelled(uri: String) = captures.delete(uri)

    /** A new receipt replaces any previous one; its capture file is dropped. */
    fun onReceiptPicked(uri: String, isCapture: Boolean) {
        scanJob?.cancel()
        dropReceiptFile()
        _state.update { it.copy(receipt = ReceiptState(uri = uri, isCapture = isCapture)) }
        scanJob = viewModelScope.launch {
            val result = scanReceipt(uri)
            _state.update { s ->
                val receipt = s.receipt?.takeIf { it.uri == uri } ?: return@update s
                when (result) {
                    is ReceiptScanResult.Found -> s.copy(
                        receipt = receipt.copy(
                            scanning = false,
                            scan = result.scan,
                            selected = ReceiptFill.defaultSelection(s.form, result.scan),
                            conflicts = ReceiptFill.conflicts(s.form, result.scan),
                            reviewOpen = true,
                        ),
                    )
                    ReceiptScanResult.NothingFound -> s.copy(
                        receipt = receipt.copy(scanning = false),
                        message = "Couldn't find any details on that receipt. It will still be attached.",
                    )
                    is ReceiptScanResult.Failed -> s.copy(
                        receipt = receipt.copy(scanning = false),
                        message = "Couldn't read the photo: ${result.message}",
                    )
                }
            }
        }
    }

    fun toggleReceiptField(field: ReceiptField) = updateReceipt {
        it.copy(selected = if (field in it.selected) it.selected - field else it.selected + field)
    }

    /** Reopens the review; conflicts are recomputed because the form may have changed since the scan. */
    fun reviewReceipt() = _state.update { s ->
        val receipt = s.receipt ?: return@update s
        val scan = receipt.scan ?: return@update s
        s.copy(receipt = receipt.copy(reviewOpen = true, conflicts = ReceiptFill.conflicts(s.form, scan)))
    }

    fun dismissReceiptReview() = updateReceipt { it.copy(reviewOpen = false) }

    fun applyReceipt() {
        _state.update { s ->
            val receipt = s.receipt ?: return@update s
            val scan = receipt.scan ?: return@update s
            s.copy(
                form = ReceiptFill.apply(s.form, scan, receipt.selected),
                errors = s.errors - receipt.selected.map { it.assetField }.toSet(),
                receipt = receipt.copy(reviewOpen = false, applied = receipt.selected.isNotEmpty()),
            )
        }
    }

    fun setAttachReceipt(attach: Boolean) = updateReceipt { it.copy(attach = attach) }

    fun removeReceipt() {
        scanJob?.cancel()
        dropReceiptFile()
        _state.update { it.copy(receipt = null) }
    }

    fun showMessage(text: String) = _state.update { it.copy(message = text) }

    fun onMessageShown() = _state.update { it.copy(message = null) }

    private fun updateReceipt(change: (ReceiptState) -> ReceiptState) {
        _state.update { s -> s.receipt?.let { s.copy(receipt = change(it)) } ?: s }
    }

    private fun dropReceiptFile() {
        _state.value.receipt?.takeIf { it.isCapture }?.let { captures.delete(it.uri) }
    }

    /** Best effort: the asset is already saved, so a failed attach is logged rather than blocking the user. */
    private suspend fun attachReceipt(assetId: String) {
        val receipt = _state.value.receipt?.takeIf { it.attach } ?: return
        val result = try {
            addDocument(assetId, DocumentKind.RECEIPT, receipt.uri, if (receipt.isCapture) "image/jpeg" else null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AddDocumentResult.Failed(e.message ?: "error")
        }
        if (result !is AddDocumentResult.Added) Log.w(TAG, "Receipt not attached: $result")
        dropReceiptFile()
        _state.update { it.copy(receipt = null) }
    }

    override fun onCleared() {
        dropReceiptFile()
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
            if (result is SaveAssetResult.Saved) attachReceipt(result.id)
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

    private companion object {
        const val TAG = "AssetEditor"
    }
}
