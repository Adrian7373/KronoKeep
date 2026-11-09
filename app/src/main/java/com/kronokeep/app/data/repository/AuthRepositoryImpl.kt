package com.kronokeep.app.data.repository

import com.kronokeep.app.data.remote.SupabaseProvider
import com.kronokeep.app.di.ApplicationScope
import com.kronokeep.app.domain.repository.AuthRepository
import com.kronokeep.app.domain.repository.AuthState
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val provider: SupabaseProvider,
    @ApplicationScope scope: CoroutineScope,
) : AuthRepository {

    override val state: StateFlow<AuthState> = provider.client
        ?.auth
        ?.sessionStatus
        ?.map { it.toAuthState() }
        ?.stateIn(scope, SharingStarted.Eagerly, AuthState.Loading)
        ?: MutableStateFlow<AuthState>(AuthState.Unconfigured).asStateFlow()

    override suspend fun signIn(email: String, password: String): Result<Unit> = withClient {
        auth.signInWith(Email) {
            this.email = email.trim()
            this.password = password
        }
    }

    override suspend fun signUp(email: String, password: String): Result<Unit> = withClient {
        auth.signUpWith(Email) {
            this.email = email.trim()
            this.password = password
        }
    }

    override suspend fun signOut(): Result<Unit> = withClient { auth.signOut() }

    private suspend fun withClient(block: suspend io.github.jan.supabase.SupabaseClient.() -> Unit): Result<Unit> {
        val client = provider.client ?: return Result.failure(IllegalStateException("Cloud sync isn't configured"))
        return runCatching { client.block() }
    }

    private fun SessionStatus.toAuthState(): AuthState = when (this) {
        is SessionStatus.Authenticated -> AuthState.SignedIn(
            userId = session.user?.id.orEmpty(),
            email = session.user?.email,
        )
        is SessionStatus.NotAuthenticated -> AuthState.SignedOut
        is SessionStatus.Initializing -> AuthState.Loading
        // Keep the user in their vault offline; the refresh is retried automatically.
        is SessionStatus.RefreshFailure -> AuthState.Loading
    }
}
