package com.kronokeep.app.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kronokeep.app.domain.repository.AuthFailure
import com.kronokeep.app.domain.repository.AuthFailureReason
import com.kronokeep.app.domain.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AuthUiState(
    val email: String = "",
    val password: String = "",
    val isSignUp: Boolean = false,
    val isBusy: Boolean = false,
    val error: String? = null,
    val info: String? = null,
) {
    val canSubmit: Boolean get() = !isBusy && AuthValidation.isValidEmail(email) && AuthValidation.isValidPassword(password)
}

object AuthValidation {
    private val EMAIL = Regex("""^[^\s@]+@[^\s@]+\.[^\s@]+$""")
    const val MIN_PASSWORD_LENGTH = 8

    fun isValidEmail(email: String): Boolean = EMAIL.matches(email.trim())
    fun isValidPassword(password: String): Boolean = password.length >= MIN_PASSWORD_LENGTH
}

/** User-facing copy for auth failures. Never shows exception text (it can carry URLs and headers). */
object AuthMessages {
    fun forFailure(e: Throwable): String = forReason((e as? AuthFailure)?.reason ?: AuthFailureReason.UNKNOWN)

    fun forReason(reason: AuthFailureReason): String = when (reason) {
        AuthFailureReason.INVALID_CREDENTIALS -> "That email and password don't match. Check them and try again."
        AuthFailureReason.EMAIL_NOT_CONFIRMED -> "Confirm your email first. The link is in your inbox."
        AuthFailureReason.ACCOUNT_EXISTS -> "An account with this email already exists. Sign in instead."
        AuthFailureReason.WEAK_PASSWORD -> "Choose a stronger password."
        AuthFailureReason.INVALID_EMAIL -> "That email address can't be used. Check it and try again."
        AuthFailureReason.RATE_LIMITED -> "Too many attempts. Wait a minute, then try again."
        AuthFailureReason.SIGNUP_DISABLED -> "New sign-ups are turned off right now."
        AuthFailureReason.NETWORK -> "Can't reach the server. Check your connection and try again."
        AuthFailureReason.UNCONFIGURED -> "Cloud sync isn't configured in this build."
        AuthFailureReason.UNKNOWN -> "Something went wrong. Try again."
    }
}

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AuthUiState())
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    fun onEmailChange(value: String) = _state.update { it.copy(email = value, error = null) }
    fun onPasswordChange(value: String) = _state.update { it.copy(password = value, error = null) }
    fun toggleMode() = _state.update { it.copy(isSignUp = !it.isSignUp, error = null, info = null) }

    fun submit() {
        val current = _state.value
        if (!current.canSubmit) return
        _state.update { it.copy(isBusy = true, error = null, info = null) }
        viewModelScope.launch {
            val result = if (current.isSignUp) {
                authRepository.signUp(current.email, current.password)
            } else {
                authRepository.signIn(current.email, current.password)
            }
            _state.update { state ->
                result.fold(
                    onSuccess = {
                        // On sign-up with email confirmation on, no session arrives; tell the user why.
                        state.copy(
                            isBusy = false,
                            info = if (current.isSignUp) "Check your inbox to confirm your email, then sign in." else null,
                        )
                    },
                    onFailure = { e -> state.copy(isBusy = false, error = AuthMessages.forFailure(e)) },
                )
            }
        }
    }
}
