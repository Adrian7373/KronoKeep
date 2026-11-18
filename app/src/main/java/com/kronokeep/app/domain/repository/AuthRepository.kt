package com.kronokeep.app.domain.repository

import kotlinx.coroutines.flow.StateFlow

sealed interface AuthState {
    /** No Supabase URL/key in this build: the vault works fully offline, nothing syncs. */
    data object Unconfigured : AuthState
    data object Loading : AuthState
    data object SignedOut : AuthState
    data class SignedIn(val userId: String, val email: String?) : AuthState

    /**
     * A recovery code was accepted, so a session exists, but the user hasn't chosen a new password yet.
     * The app stays on the auth screen (and nothing syncs) until they do or cancel.
     */
    data class PasswordRecovery(val email: String?) : AuthState

    companion object {
        /** While a reset is in progress, a session is shown as [PasswordRecovery] instead of [SignedIn]. */
        fun withRecovery(state: AuthState, recovering: Boolean): AuthState =
            if (recovering && state is SignedIn) PasswordRecovery(state.email) else state
    }
}

interface AuthRepository {
    val state: StateFlow<AuthState>

    suspend fun signIn(email: String, password: String): Result<Unit>

    /** Returns success even when the project requires email confirmation (state stays SignedOut). */
    suspend fun signUp(email: String, password: String): Result<Unit>

    suspend fun signOut(): Result<Unit>

    /**
     * Permanently deletes the signed-in account and all its cloud data (rows and stored files). The server
     * re-checks [password]. The local session is left in place: call [forgetSession] once local data is erased.
     */
    suspend fun deleteAccount(password: String): Result<Unit>

    /** Drops the local session without calling the server (whose user may no longer exist). */
    suspend fun forgetSession()

    /**
     * Emails a one-time recovery code. Succeeds for unknown addresses too (the server doesn't reveal
     * which emails have accounts).
     */
    suspend fun requestPasswordReset(email: String): Result<Unit>

    /** Checks the emailed code. On success the state becomes [AuthState.PasswordRecovery]. */
    suspend fun verifyRecoveryCode(email: String, code: String): Result<Unit>

    /** Sets the new password during recovery; on success the state becomes [AuthState.SignedIn]. */
    suspend fun setNewPassword(password: String): Result<Unit>

    /** Abandons a recovery after the code was accepted: drops the session, back to [AuthState.SignedOut]. */
    suspend fun cancelPasswordRecovery(): Result<Unit>
}
