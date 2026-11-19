package com.kronokeep.app.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kronokeep.app.domain.repository.AuthFailure
import com.kronokeep.app.domain.repository.AuthFailureReason
import com.kronokeep.app.domain.repository.AuthRepository
import com.kronokeep.app.domain.repository.AuthState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class AuthMode {
    SIGN_IN,
    SIGN_UP,
    /** After sign-up (or signing in unconfirmed): enter the emailed confirmation code. */
    SIGNUP_CODE,
    /** Forgot password, step 1: enter the email to send a code to. */
    RESET_REQUEST,
    /** Step 2: enter the emailed code. */
    RESET_CODE,
    /** Step 3: the code was accepted; choose a new password. */
    NEW_PASSWORD,
}

data class AuthUiState(
    val mode: AuthMode = AuthMode.SIGN_IN,
    val email: String = "",
    val password: String = "",
    val confirmPassword: String = "",
    val code: String = "",
    val isBusy: Boolean = false,
    val error: String? = null,
    val info: String? = null,
) {
    val isSignUp: Boolean get() = mode == AuthMode.SIGN_UP

    /** Shown under the confirm field once the user has typed something that doesn't match. */
    val passwordsMismatch: Boolean get() = confirmPassword.isNotEmpty() && confirmPassword != password

    /** The checklist tick: both fields filled in and identical. */
    val passwordsMatch: Boolean get() = password.isNotEmpty() && confirmPassword == password

    val canSubmit: Boolean get() = !isBusy && when (mode) {
        AuthMode.SIGN_IN -> AuthValidation.isValidEmail(email) && AuthValidation.isValidPassword(password)
        AuthMode.SIGN_UP -> AuthValidation.isValidEmail(email) &&
            AuthValidation.isValidPassword(password) && password == confirmPassword
        AuthMode.RESET_REQUEST -> AuthValidation.isValidEmail(email)
        AuthMode.SIGNUP_CODE, AuthMode.RESET_CODE -> AuthValidation.isValidEmailCode(code)
        AuthMode.NEW_PASSWORD -> AuthValidation.isValidPassword(password) && password == confirmPassword
    }

    fun withSignUp(signUp: Boolean): AuthUiState {
        val target = if (signUp) AuthMode.SIGN_UP else AuthMode.SIGN_IN
        if (mode == target || (mode != AuthMode.SIGN_IN && mode != AuthMode.SIGN_UP)) return this
        return copy(mode = target, confirmPassword = "", error = null, info = null)
    }

    /** Moves to the confirmation-code step; the passwords aren't needed any more, since the code signs in. */
    fun toSignUpCode(info: String?, error: String? = null): AuthUiState = copy(
        mode = AuthMode.SIGNUP_CODE,
        password = "",
        confirmPassword = "",
        code = "",
        isBusy = false,
        info = info,
        error = error,
    )

    /** An unconfirmed account goes to the code step instead of a dead-end error; null keeps the usual error. */
    fun afterSignInFailure(e: Throwable): AuthUiState? =
        if ((e as? AuthFailure)?.reason == AuthFailureReason.EMAIL_NOT_CONFIRMED) {
            toSignUpCode(info = AuthMessages.NOT_CONFIRMED_YET)
        } else {
            null
        }

    fun withAuthState(auth: AuthState): AuthUiState = when {
        // The view model is activity-scoped and outlives the session, so drop everything typed once one opens:
        // otherwise signing out (or deleting the account) shows the last email and password again.
        auth is AuthState.SignedIn -> if (this == BLANK) this else BLANK
        auth is AuthState.PasswordRecovery && mode != AuthMode.NEW_PASSWORD -> copy(
            mode = AuthMode.NEW_PASSWORD,
            email = auth.email ?: email,
            password = "",
            confirmPassword = "",
            code = "",
            error = null,
            info = null,
        )
        // Recovery cancelled (or the session dropped): start over at sign-in.
        auth == AuthState.SignedOut && mode == AuthMode.NEW_PASSWORD ->
            copy(mode = AuthMode.SIGN_IN, password = "", confirmPassword = "", isBusy = false)
        else -> this
    }

    private companion object {
        val BLANK = AuthUiState()
    }
}

object AuthValidation {
    private val EMAIL = Regex("""^[^\s@]+@[^\s@]+\.[^\s@]+$""")
    const val MIN_PASSWORD_LENGTH = 8

    /** Supabase email OTPs (sign-up and recovery) are 6 digits by default; a project can raise that to 10. */
    private val EMAIL_CODE = Regex("""^\d{6,10}$""")
    const val MAX_CODE_LENGTH = 10

    fun isValidEmail(email: String): Boolean = EMAIL.matches(email.trim())
    fun isValidPassword(password: String): Boolean = password.length >= MIN_PASSWORD_LENGTH
    fun isValidEmailCode(code: String): Boolean = EMAIL_CODE.matches(code)

    /** Keeps only digits, so a pasted "123 456" or "123-456" still works. */
    fun normalizeCode(input: String): String = input.filter(Char::isDigit).take(MAX_CODE_LENGTH)
}

/** User-facing copy for auth failures. Never shows exception text (it can carry URLs and headers). */
object AuthMessages {
    fun forFailure(e: Throwable): String = forReason((e as? AuthFailure)?.reason ?: AuthFailureReason.UNKNOWN)

    fun forReason(reason: AuthFailureReason): String = when (reason) {
        AuthFailureReason.INVALID_CREDENTIALS -> "That email and password don't match. Check them and try again."
        AuthFailureReason.EMAIL_NOT_CONFIRMED -> "Confirm your email first with the code we sent you."
        AuthFailureReason.ACCOUNT_EXISTS -> "An account with this email already exists. Sign in instead."
        AuthFailureReason.WEAK_PASSWORD -> "Choose a stronger password."
        AuthFailureReason.INVALID_EMAIL -> "That email address can't be used. Check it and try again."
        AuthFailureReason.RATE_LIMITED -> "Too many attempts. Wait a minute, then try again."
        AuthFailureReason.SIGNUP_DISABLED -> "New sign-ups are turned off right now."
        AuthFailureReason.INVALID_CODE -> "That code is wrong or has expired. Check it, or send a new one."
        AuthFailureReason.SAME_PASSWORD -> "That's your current password. Choose a different one."
        AuthFailureReason.NETWORK -> "Can't reach the server. Check your connection and try again."
        AuthFailureReason.UNCONFIGURED -> "Cloud sync isn't configured in this build."
        AuthFailureReason.UNKNOWN -> "Something went wrong. Try again."
    }

    /** Deliberately the same whether or not the address has an account. */
    fun codeSent(email: String): String =
        "If an account exists for ${email.trim()}, we've emailed it a code. It can take a minute to arrive."

    const val NOT_CONFIRMED_YET =
        "This email isn't confirmed yet. Enter the code from your sign-up email, or send a new one."

    /** Also shown when the address already had a confirmed account (the server sends nothing then, by design). */
    fun signUpCodeSent(email: String): String =
        "We've emailed a code to ${email.trim()}. It can take a minute to arrive."
}

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AuthUiState())
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    init {
        // The new-password step follows the session, so it survives this screen being recreated.
        viewModelScope.launch {
            authRepository.state.collect { auth -> _state.update { it.withAuthState(auth) } }
        }
    }

    fun onEmailChange(value: String) = _state.update { it.copy(email = value, error = null) }
    fun onPasswordChange(value: String) = _state.update { it.copy(password = value, error = null) }
    fun onConfirmPasswordChange(value: String) = _state.update { it.copy(confirmPassword = value, error = null) }
    fun onCodeChange(value: String) = _state.update { it.copy(code = AuthValidation.normalizeCode(value), error = null) }

    /** Switches between the sign-in and create-account tabs; the confirm field starts empty each time. */
    fun selectSignUp(signUp: Boolean) = _state.update { it.withSignUp(signUp) }

    fun startPasswordReset() = _state.update {
        it.copy(mode = AuthMode.RESET_REQUEST, password = "", code = "", error = null, info = null)
    }

    /** Back to sign-in from the email or code step. The new-password step uses [cancelPasswordReset]. */
    fun backToSignIn() = _state.update {
        it.copy(mode = AuthMode.SIGN_IN, code = "", password = "", confirmPassword = "", error = null, info = null)
    }

    /** From the code step: change the address the code goes to. */
    fun changeEmail() = _state.update { it.copy(mode = AuthMode.RESET_REQUEST, code = "", error = null, info = null) }

    fun submit() {
        val current = _state.value
        if (!current.canSubmit) return
        when (current.mode) {
            // With email confirmation on, no session arrives: the user types the emailed code next. With it
            // off, the session opens the vault and this screen goes away.
            AuthMode.SIGN_UP -> launchCall(
                call = { authRepository.signUp(current.email, current.password) },
                // Skipped when a session already opened (confirmation off) and cleared the form.
                onSuccess = {
                    if (it.mode == AuthMode.SIGN_UP) it.toSignUpCode(info = AuthMessages.signUpCodeSent(current.email)) else it
                },
            )
            AuthMode.SIGN_IN -> launchCall(
                call = { authRepository.signIn(current.email, current.password) },
                onFailure = AuthUiState::afterSignInFailure,
            )
            // Success: the session becomes SignedIn and the app opens the vault.
            AuthMode.SIGNUP_CODE -> launchCall(call = { authRepository.verifySignUpCode(current.email, current.code) })
            AuthMode.RESET_REQUEST -> sendCode(current.email)
            // Success needs no UI change here: the PasswordRecovery state moves the screen on.
            AuthMode.RESET_CODE -> launchCall(call = { authRepository.verifyRecoveryCode(current.email, current.code) })
            // Success: the session becomes SignedIn and the app opens the vault.
            AuthMode.NEW_PASSWORD -> launchCall(call = { authRepository.setNewPassword(current.password) })
        }
    }

    fun resendCode() {
        val current = _state.value
        if (current.isBusy) return
        when (current.mode) {
            AuthMode.RESET_CODE -> sendCode(current.email)
            AuthMode.SIGNUP_CODE -> launchCall(
                call = { authRepository.resendSignUpCode(current.email) },
                onSuccess = { it.copy(code = "", info = AuthMessages.signUpCodeSent(current.email)) },
            )
            else -> Unit
        }
    }

    /** Leaves the new-password step; the SignedOut state that follows returns the screen to sign-in. */
    fun cancelPasswordReset() {
        if (_state.value.isBusy) return
        launchCall(call = { authRepository.cancelPasswordRecovery() })
    }

    private fun sendCode(email: String) = launchCall(
        call = { authRepository.requestPasswordReset(email) },
        onSuccess = { it.copy(mode = AuthMode.RESET_CODE, code = "", info = AuthMessages.codeSent(email)) },
    )

    /** [onFailure] may take over a failure by returning a new state; null shows the usual error. */
    private fun launchCall(
        call: suspend () -> Result<Unit>,
        onSuccess: (AuthUiState) -> AuthUiState = { it },
        onFailure: (AuthUiState, Throwable) -> AuthUiState? = { _, _ -> null },
    ) {
        _state.update { it.copy(isBusy = true, error = null, info = null) }
        viewModelScope.launch {
            val result = call()
            _state.update { state ->
                val idle = state.copy(isBusy = false)
                result.fold(
                    onSuccess = { onSuccess(idle) },
                    onFailure = { e -> onFailure(idle, e) ?: idle.copy(error = AuthMessages.forFailure(e)) },
                )
            }
        }
    }
}
