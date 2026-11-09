package com.kronokeep.app.ui.schedules

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kronokeep.app.domain.common.Dates
import com.kronokeep.app.domain.maintenance.AddSchedulesUseCase
import com.kronokeep.app.domain.maintenance.MaintenanceError
import com.kronokeep.app.domain.maintenance.SaveSchedulesResult
import com.kronokeep.app.domain.maintenance.ScheduleField
import com.kronokeep.app.domain.maintenance.UpdateScheduleUseCase
import com.kronokeep.app.domain.model.AssetCategory
import com.kronokeep.app.domain.model.ScheduleDraft
import com.kronokeep.app.domain.repository.AssetRepository
import com.kronokeep.app.domain.repository.MaintenanceRepository
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

enum class IntervalUnit(val days: Int, val label: String) { DAYS(1, "Days"), MONTHS(30, "Months") }

/** Raw text the user typed; parsed on save. */
data class ScheduleForm(
    val task: String = "",
    val interval: String = "",
    val unit: IntervalUnit = IntervalUnit.MONTHS,
    val intervalKm: String = "",
    val lastDoneOn: LocalDate? = null,
    val lastDoneKm: String = "",
) {
    companion object {
        fun parseInt(text: String): Int? = text.replace(",", "").replace(" ", "").lowercase().removeSuffix("km").toIntOrNull()
    }
}

data class ScheduleEditorUiState(
    val isEdit: Boolean,
    val isLoading: Boolean = true,
    val notFound: Boolean = false,
    val category: AssetCategory? = null,
    val assetName: String = "",
    val currentKm: Int? = null,
    val form: ScheduleForm = ScheduleForm(),
    val errors: Map<ScheduleField, MaintenanceError> = emptyMap(),
    val isSaving: Boolean = false,
    val saved: Boolean = false,
) {
    val isVehicle: Boolean get() = category == AssetCategory.VEHICLE
}

@HiltViewModel(assistedFactory = ScheduleEditorViewModel.Factory::class)
class ScheduleEditorViewModel @AssistedInject constructor(
    @Assisted("assetId") private val assetId: String,
    @Assisted("scheduleId") private val scheduleId: String?,
    private val assets: AssetRepository,
    private val maintenance: MaintenanceRepository,
    private val addSchedules: AddSchedulesUseCase,
    private val updateSchedule: UpdateScheduleUseCase,
    private val clock: Clock,
    private val zone: ZoneId,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(@Assisted("assetId") assetId: String, @Assisted("scheduleId") scheduleId: String?): ScheduleEditorViewModel
    }

    private val _state = MutableStateFlow(ScheduleEditorUiState(isEdit = scheduleId != null))
    val state: StateFlow<ScheduleEditorUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val asset = assets.get(assetId)
            val schedule = scheduleId?.let { maintenance.getSchedule(it) }
            if (asset == null || (scheduleId != null && schedule == null)) {
                _state.update { it.copy(isLoading = false, notFound = true) }
                return@launch
            }
            val readings = maintenance.observeReadings(assetId).first()
            val currentKm = listOfNotNull(asset.currentOdometerKm, readings.maxByOrNull { it.recordedAt }?.km).maxOrNull()
            val form = if (schedule == null) {
                ScheduleForm(lastDoneOn = Dates.localDate(clock.instant(), zone), lastDoneKm = currentKm?.toString().orEmpty())
            } else {
                val days = schedule.intervalDays
                val months = days != null && days % 30 == 0
                ScheduleForm(
                    task = schedule.task,
                    interval = days?.let { if (months) (it / 30).toString() else it.toString() }.orEmpty(),
                    unit = if (days == null || months) IntervalUnit.MONTHS else IntervalUnit.DAYS,
                    intervalKm = schedule.intervalKm?.toString().orEmpty(),
                    lastDoneOn = schedule.lastDoneAt?.let { Dates.localDate(it, zone) },
                    lastDoneKm = schedule.lastDoneKm?.toString().orEmpty(),
                )
            }
            _state.update {
                it.copy(isLoading = false, category = asset.category, assetName = asset.name, currentKm = currentKm, form = form)
            }
        }
    }

    fun edit(field: ScheduleField?, change: (ScheduleForm) -> ScheduleForm) {
        _state.update { s ->
            // The interval fields share the "set days, km, or both" error, so editing either clears both.
            val cleared = when (field) {
                null -> s.errors
                ScheduleField.INTERVAL_DAYS, ScheduleField.INTERVAL_KM -> s.errors - ScheduleField.INTERVAL_DAYS - ScheduleField.INTERVAL_KM
                else -> s.errors - field
            }
            s.copy(form = change(s.form), errors = cleared)
        }
    }

    fun save() {
        val s = _state.value
        if (s.isSaving || s.isLoading || s.category == null) return
        val f = s.form
        val parseErrors = mutableMapOf<ScheduleField, MaintenanceError>()
        fun num(text: String, field: ScheduleField): Int? =
            if (text.isBlank()) null else ScheduleForm.parseInt(text).also { if (it == null) parseErrors[field] = MaintenanceError.InvalidNumber }
        val interval = num(f.interval, ScheduleField.INTERVAL_DAYS)
        val km = num(f.intervalKm, ScheduleField.INTERVAL_KM)
        val lastKm = num(f.lastDoneKm, ScheduleField.LAST_DONE_KM)
        if (parseErrors.isNotEmpty()) return _state.update { it.copy(errors = parseErrors) }

        val now = clock.instant()
        val draft = ScheduleDraft(
            task = f.task,
            intervalDays = interval?.let { it * f.unit.days },
            intervalKm = km,
            lastDoneAt = f.lastDoneOn?.let { Dates.instantFor(it, now, zone) },
            lastDoneKm = lastKm,
        )
        _state.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            val result = if (scheduleId == null) addSchedules(assetId, listOf(draft)) else updateSchedule(scheduleId, draft)
            _state.update {
                when (result) {
                    is SaveSchedulesResult.Saved -> it.copy(isSaving = false, saved = true)
                    is SaveSchedulesResult.Invalid -> it.copy(isSaving = false, errors = result.errors[0].orEmpty())
                    SaveSchedulesResult.NotFound, SaveSchedulesResult.NotSupported -> it.copy(isSaving = false, notFound = true)
                }
            }
        }
    }
}
