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
}
