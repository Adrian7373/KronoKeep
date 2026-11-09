package com.kronokeep.app.ui.assets.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kronokeep.app.data.document.CaptureFiles
import com.kronokeep.app.domain.asset.DeleteAssetUseCase
import com.kronokeep.app.domain.asset.SetAssetArchivedUseCase
import com.kronokeep.app.domain.document.AddDocumentResult
import com.kronokeep.app.domain.document.AddDocumentUseCase
import com.kronokeep.app.domain.maintenance.AddSchedulesUseCase
import com.kronokeep.app.domain.maintenance.AssetMaintenance
import com.kronokeep.app.domain.maintenance.DeleteScheduleUseCase
import com.kronokeep.app.domain.maintenance.DeleteServiceLogUseCase
import com.kronokeep.app.domain.maintenance.ObserveAssetMaintenanceUseCase
import com.kronokeep.app.domain.maintenance.OdometerResult
import com.kronokeep.app.domain.maintenance.SaveSchedulesResult
import com.kronokeep.app.domain.maintenance.UpdateOdometerUseCase
import com.kronokeep.app.domain.model.Asset
import com.kronokeep.app.domain.model.Document
import com.kronokeep.app.domain.model.DocumentKind
import com.kronokeep.app.domain.model.ScheduleDraft
import com.kronokeep.app.domain.model.ServiceLog
import com.kronokeep.app.domain.presets.PresetTask
import com.kronokeep.app.domain.repository.AssetRepository
import com.kronokeep.app.domain.repository.DeleteLogResult
import com.kronokeep.app.domain.repository.DocumentRepository
import com.kronokeep.app.domain.repository.MaintenanceRepository
import com.kronokeep.app.ui.components.Formats
import com.kronokeep.app.ui.components.label
import com.kronokeep.app.ui.maintenance.message
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant

sealed interface AssetDetailUiState {
    data object Loading : AssetDetailUiState
    data object Missing : AssetDetailUiState
    data class Loaded(
        val asset: Asset,
        val documents: List<Document>,
        val maintenance: AssetMaintenance,
        val logs: List<ServiceLog>,
        val isImporting: Boolean,
        /** When the predictions were computed; formatting "in N days" uses the same instant. */
        val now: Instant,
    ) : AssetDetailUiState
}

data class AssetDetailTransient(
    val isImporting: Boolean = false,
    val message: String? = null,
    /** One-shot: the asset was deleted from this screen, so close it. */
    val closed: Boolean = false,
    val odometerError: String? = null,
    /** One-shot: the odometer dialog saved and can close. */
    val odometerSaved: Boolean = false,
)

@HiltViewModel(assistedFactory = AssetDetailViewModel.Factory::class)
class AssetDetailViewModel @AssistedInject constructor(
    @Assisted private val assetId: String,
    assets: AssetRepository,
    maintenance: MaintenanceRepository,
    observeMaintenance: ObserveAssetMaintenanceUseCase,
    private val documents: DocumentRepository,
    private val setArchived: SetAssetArchivedUseCase,
    private val deleteAsset: DeleteAssetUseCase,
    private val addDocumentUseCase: AddDocumentUseCase,
    private val addSchedules: AddSchedulesUseCase,
    private val deleteScheduleUseCase: DeleteScheduleUseCase,
    private val deleteLogUseCase: DeleteServiceLogUseCase,
    private val updateOdometer: UpdateOdometerUseCase,
    private val captures: CaptureFiles,
    private val clock: Clock,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(assetId: String): AssetDetailViewModel
    }

    private val _transient = MutableStateFlow(AssetDetailTransient())
    val transient: StateFlow<AssetDetailTransient> = _transient

    private val asset = assets.observe(assetId)

    val state: StateFlow<AssetDetailUiState> = combine(
        asset,
        documents.observeForAsset(assetId),
        observeMaintenance(asset, assetId),
        maintenance.observeServiceLogs(assetId),
        _transient,
    ) { a, docs, m, logs, t ->
        if (a == null || m == null) {
            AssetDetailUiState.Missing
        } else {
            AssetDetailUiState.Loaded(a, docs, m, logs, t.isImporting, clock.instant())
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AssetDetailUiState.Loading)

    fun toggleArchived() {
        val asset = (state.value as? AssetDetailUiState.Loaded)?.asset ?: return
        viewModelScope.launch {
            setArchived(assetId, !asset.isArchived)
            showMessage(if (asset.isArchived) "Moved back to your assets" else "Archived")
        }
    }

    fun delete() {
        viewModelScope.launch {
            if (deleteAsset(assetId)) _transient.update { it.copy(closed = true) }
        }
    }

    /** Adds preset tasks as if they were just done (today, current odometer). Edit them afterwards to backdate. */
    fun addPresetTasks(tasks: List<PresetTask>) {
        val loaded = state.value as? AssetDetailUiState.Loaded ?: return
        if (tasks.isEmpty()) return
        val now = clock.instant()
        val drafts = tasks.map {
            ScheduleDraft(it.task, it.intervalDays, it.intervalKm, lastDoneAt = now, lastDoneKm = loaded.maintenance.currentKm)
        }
        viewModelScope.launch {
            showMessage(
                when (val r = addSchedules(assetId, drafts)) {
                    is SaveSchedulesResult.Saved -> if (r.ids.size == 1) "Schedule added" else "${r.ids.size} schedules added"
                    // Only km-based tasks can fail here: there's no odometer to count from.
                    is SaveSchedulesResult.Invalid -> "Set the odometer first, then add km-based tasks"
                    SaveSchedulesResult.NotFound -> "This asset no longer exists"
                    SaveSchedulesResult.NotSupported -> "Electronics track the warranty only"
                },
            )
        }
    }

    fun deleteSchedule(id: String) {
        viewModelScope.launch { if (deleteScheduleUseCase(id)) showMessage("Schedule removed") }
    }

    fun deleteLog(id: String) {
        viewModelScope.launch {
            showMessage(
                when (deleteLogUseCase(id)) {
                    DeleteLogResult.Deleted -> "Service entry removed"
                    DeleteLogResult.Locked -> "This entry is part of a verified report and can't be changed"
                    DeleteLogResult.NotFound -> "Already removed"
                },
            )
        }
    }

    fun saveOdometer(text: String) {
        val km = text.replace(",", "").replace(" ", "").lowercase().removeSuffix("km").toIntOrNull()
            ?: return _transient.update { it.copy(odometerError = "Enter a number") }
        viewModelScope.launch {
            when (val r = updateOdometer(assetId, km)) {
                OdometerResult.Saved -> {
                    _transient.update { it.copy(odometerError = null, odometerSaved = true) }
                    showMessage("Odometer updated to ${Formats.km(km)}")
                }
                is OdometerResult.Invalid -> _transient.update { it.copy(odometerError = r.error.message()) }
                OdometerResult.NotFound -> showMessage("This asset no longer exists")
            }
        }
    }

    fun onOdometerDialogClosed() = _transient.update { it.copy(odometerError = null, odometerSaved = false) }

    fun newCaptureUri(): String = captures.newCaptureUri()

    /** Camera returned without a photo: drop the empty capture file. */
    fun captureCancelled(uri: String) = captures.delete(uri)

    fun addDocument(uri: String, mimeType: String?, kind: DocumentKind, isCapture: Boolean = false) {
        _transient.update { it.copy(isImporting = true) }
        viewModelScope.launch {
            val result = try {
                addDocumentUseCase(assetId, kind, uri, mimeType)
            } catch (e: Exception) {
                AddDocumentResult.Failed(e.message ?: "Couldn't save the file")
            } finally {
                if (isCapture) captures.delete(uri)
            }
            _transient.update { it.copy(isImporting = false) }
            showMessage(
                when (result) {
                    is AddDocumentResult.Added -> "${kind.label} saved"
                    AddDocumentResult.UnsupportedType -> "Only JPEG, PNG, HEIC and PDF files are supported"
                    is AddDocumentResult.TooLarge -> "That file is ${Formats.bytes(result.sizeBytes)}. The limit is 15 MB."
                    is AddDocumentResult.Failed -> result.message
                    AddDocumentResult.AssetNotFound -> "This asset no longer exists"
                },
            )
        }
    }

    fun deleteDocument(id: String) {
        viewModelScope.launch {
            if (documents.delete(id)) showMessage("Document removed")
        }
    }

    fun onMessageShown() = _transient.update { it.copy(message = null) }
    fun onClosedHandled() = _transient.update { it.copy(closed = false) }

    fun showMessage(text: String) = _transient.update { it.copy(message = text) }
}
