package com.kronokeep.app.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kronokeep.app.domain.common.Dates
import com.kronokeep.app.domain.dashboard.DashboardSummary
import com.kronokeep.app.domain.maintenance.ObserveAllMaintenanceUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject

sealed interface DashboardUiState {
    data object Loading : DashboardUiState
    data class Ready(val summary: DashboardSummary, val now: Instant) : DashboardUiState
}

@HiltViewModel
class DashboardViewModel @Inject constructor(
    observeAll: ObserveAllMaintenanceUseCase,
    clock: Clock,
    zone: ZoneId,
) : ViewModel() {
    val state: StateFlow<DashboardUiState> = observeAll()
        .map { data ->
            val now = clock.instant()
            DashboardUiState.Ready(DashboardSummary.from(data, Dates.localDate(now, zone), now), now)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState.Loading)
}
