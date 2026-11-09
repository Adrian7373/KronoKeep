package com.kronokeep.app.ui

import androidx.lifecycle.ViewModel
import com.kronokeep.app.domain.repository.AuthRepository
import com.kronokeep.app.domain.repository.AuthState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class AppViewModel @Inject constructor(authRepository: AuthRepository) : ViewModel() {
    val authState: StateFlow<AuthState> = authRepository.state
}
