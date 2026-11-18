package com.kronokeep.app.domain.repository

import kotlinx.coroutines.flow.StateFlow

sealed interface AuthState {
    /** No Supabase URL/key in this build: the vault works fully offline, nothing syncs. */
    data object Unconfigured : AuthState
    data object Loading : AuthState
    data object SignedOut : AuthState
    data class SignedIn(val userId: String, val email: String?) : AuthState
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
}
