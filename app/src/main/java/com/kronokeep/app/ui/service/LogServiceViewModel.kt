package com.kronokeep.app.ui.service

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kronokeep.app.domain.common.Dates
import com.kronokeep.app.domain.maintenance.LogServiceResult
import com.kronokeep.app.domain.maintenance.LogServiceUseCase
import com.kronokeep.app.domain.maintenance.MaintenanceError
import com.kronokeep.app.domain.maintenance.ObserveAssetMaintenanceUseCase
import com.kronokeep.app.domain.maintenance.VisitField
import com.kronokeep.app.domain.model.AssetCategory
import com.kronokeep.app.domain.model.MaintenanceStatus
import com.kronokeep.app.domain.model.Peso
import com.kronokeep.app.domain.model.ServiceVisit
import com.kronokeep.app.domain.repository.AssetRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

data class TaskOption(val id: String, val task: String, val kmBased: Boolean, val status: MaintenanceStatus)

data class VisitForm(
    val date: LocalDate,
    val ticked: Set<String> = emptySet(),
    val odometer: String = "",
    val cost: String = "",
    val shop: String = "",
    val notes: String = "",
)

data class LogServiceUiState(
    val isLoading: Boolean = true,
    val notFound: Boolean = false,
    val assetName: String = "",
    val category: AssetCategory? = null,
    val currentKm: Int? = null,
    val tasks: List<TaskOption> = emptyList(),
    val form: VisitForm,
    val errors: Map<VisitField, MaintenanceError> = emptyMap(),
    val isSaving: Boolean = false,
    val savedCount: Int? = null,
) {
    val isVehicle: Boolean get() = category == AssetCategory.VEHICLE
}

@HiltViewModel(assistedFactory = LogServiceViewModel.Factory::class)
class LogServiceViewModel @AssistedInject constructor(
    @Assisted("assetId") private val assetId: String,
    @Assisted("scheduleId") preselected: String?,
    assets: AssetRepository,
    observeMaintenance: ObserveAssetMaintenanceUseCase,
    private val logService: LogServiceUseCase,
    private val clock: Clock,
    private val zone: ZoneId,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(@Assisted("assetId") assetId: String, @Assisted("scheduleId") scheduleId: String?): LogServiceViewModel
    }

    private val _state = MutableStateFlow(LogServiceUiState(form = VisitForm(date = Dates.localDate(clock.instant(), zone))))
    val state: StateFlow<LogServiceUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val asset = assets.get(assetId)
            val m = observeMaintenance(assets.observe(assetId), assetId).first()
            if (asset == null || m == null) return@launch _state.update { it.copy(isLoading = false, notFound = true) }
            val tasks = m.schedules.map {
                TaskOption(it.schedule.id, it.schedule.task, it.schedule.intervalKm != null, it.prediction.status)
            }
            _state.update { s ->
                s.copy(
                    isLoading = false,
                    assetName = asset.name,
                    category = asset.category,
                    currentKm = m.currentKm,
                    tasks = tasks,
                    form = s.form.copy(ticked = setOfNotNull(preselected?.takeIf { id -> tasks.any { it.id == id } })),
                )
            }
        }
    }

    /** Ticking changes whether "nothing logged" and "odometer needed" apply, so both errors are cleared. */
    fun toggle(scheduleId: String) {
        _state.update { s ->
            val ticked = if (scheduleId in s.form.ticked) s.form.ticked - scheduleId else s.form.ticked + scheduleId
            s.copy(form = s.form.copy(ticked = ticked), errors = s.errors - VisitField.NOTES - VisitField.ODOMETER)
        }
    }

    fun edit(field: VisitField?, change: (VisitForm) -> VisitForm) {
        _state.update { s -> s.copy(form = change(s.form), errors = if (field == null) s.errors else s.errors - field) }
    }

    fun save() {
        val s = _state.value
        if (s.isSaving || s.isLoading || s.notFound) return
        val f = s.form
        val errors = mutableMapOf<VisitField, MaintenanceError>()
        val km = f.odometer.replace(",", "").replace(" ", "").lowercase().removeSuffix("km").takeIf { it.isNotEmpty() }?.let {
            it.toIntOrNull() ?: null.also { errors[VisitField.ODOMETER] = MaintenanceError.InvalidNumber }
        }
        val cost = f.cost.takeIf { it.isNotBlank() }?.let { Peso.parse(it) ?: null.also { errors[VisitField.COST] = MaintenanceError.InvalidNumber } }
        if (errors.isNotEmpty()) return _state.update { it.copy(errors = errors) }

        val visit = ServiceVisit(
            performedAt = Dates.instantFor(f.date, clock.instant(), zone),
            odometerKm = km,
            cost = cost,
            shop = f.shop,
            notes = f.notes,
            // Keep the on-screen order so the cost lands on the first task listed.
            scheduleIds = s.tasks.map { it.id }.filter { it in f.ticked },
        )
        _state.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            val result = logService(assetId, visit)
            _state.update {
                when (result) {
                    is LogServiceResult.Saved -> it.copy(isSaving = false, savedCount = result.ids.size)
                    is LogServiceResult.Invalid -> it.copy(isSaving = false, errors = result.errors)
                    LogServiceResult.NotFound -> it.copy(isSaving = false, notFound = true)
                }
            }
        }
    }
}
