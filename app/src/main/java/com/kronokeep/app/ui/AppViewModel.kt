package com.kronokeep.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kronokeep.app.domain.repository.AuthRepository
import com.kronokeep.app.domain.repository.AuthState
import com.kronokeep.app.domain.repository.MaintenanceRepository
import com.kronokeep.app.domain.repository.SettingsRepository
import com.kronokeep.app.domain.settings.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AppViewModel @Inject constructor(
    authRepository: AuthRepository,
    private val settingsRepository: SettingsRepository,
    maintenance: MaintenanceRepository,
) : ViewModel() {
    val authState: StateFlow<AuthState> = authRepository.state

    /** Null until the stored choice has been read, so nothing acts on a placeholder value. */
    val themeMode: StateFlow<ThemeMode?> = settingsRepository.themeMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * True once the first schedule exists and we haven't asked for notification permission yet.
     * Asking then, in context, beats a cold prompt on first launch.
     */
    val shouldAskForNotifications: StateFlow<Boolean> = combine(
        maintenance.observeAllSchedules().map { it.isNotEmpty() }.distinctUntilChanged(),
        settingsRepository.notificationPromptShown,
        settingsRepository.remindersEnabled,
    ) { hasSchedules, shown, enabled -> hasSchedules && !shown && enabled }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun onNotificationPromptShown() {
        viewModelScope.launch { settingsRepository.markNotificationPromptShown() }
    }
}
