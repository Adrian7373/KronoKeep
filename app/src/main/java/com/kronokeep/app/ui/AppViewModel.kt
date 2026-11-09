package com.kronokeep.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kronokeep.app.domain.repository.AuthRepository
import com.kronokeep.app.domain.repository.AuthState
import com.kronokeep.app.domain.repository.SettingsRepository
import com.kronokeep.app.domain.settings.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class AppViewModel @Inject constructor(
    authRepository: AuthRepository,
    settingsRepository: SettingsRepository,
) : ViewModel() {
    val authState: StateFlow<AuthState> = authRepository.state

    /** Null until the stored choice has been read, so nothing acts on a placeholder value. */
    val themeMode: StateFlow<ThemeMode?> = settingsRepository.themeMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
}
