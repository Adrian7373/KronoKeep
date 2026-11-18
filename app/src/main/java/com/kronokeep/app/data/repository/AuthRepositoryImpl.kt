package com.kronokeep.app.data.repository

import com.kronokeep.app.data.remote.SupabaseProvider
import com.kronokeep.app.di.ApplicationScope
import com.kronokeep.app.domain.repository.AuthFailure
import com.kronokeep.app.domain.repository.AuthFailureReason
import com.kronokeep.app.domain.repository.AuthRepository
import com.kronokeep.app.domain.repository.AuthState
import io.github.jan.supabase.auth.OtpType
import com.kronokeep.app.data.remote.FunctionErrors
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.functions.functions
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val provider: SupabaseProvider,
    @ApplicationScope scope: CoroutineScope,
) : AuthRepository {

    /** True from the moment a recovery code is submitted until the new password is saved or the reset is cancelled. */
    private val recovering = MutableStateFlow(false)

    override val state: StateFlow<AuthState> = provider.client
        ?.auth
        ?.sessionStatus
        ?.combine(recovering) { status, recovering -> AuthState.withRecovery(status.toAuthState(), recovering) }
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

    // A code instead of the link: the link's redirect lands on the Site URL with the session in the URL.
    // EMAIL covers sign-up codes on verify; resending needs the specific SIGNUP type.
    override suspend fun verifySignUpCode(email: String, code: String): Result<Unit> = withClient {
        auth.verifyEmailOtp(type = OtpType.Email.EMAIL, email = email.trim(), token = code.trim())
    }

    override suspend fun resendSignUpCode(email: String): Result<Unit> = withClient {
        auth.resendEmail(OtpType.Email.SIGNUP, email.trim(), redirectUrl = null)
    }

    override suspend fun signOut(): Result<Unit> = withClient { auth.signOut() }

    override suspend fun deleteAccount(password: String): Result<Unit> = withClient {
        val response = try {
            functions.invoke("delete-account", buildJsonObject { put("password", password) })
        } catch (e: RestException) {
            // Not e.message: supabase-kt puts the request URL and headers in it.
            throw AuthFailure(FunctionErrors.deleteAccountReason(e.statusCode, e.error))
        }
        if (!response.status.isSuccess()) {
            throw AuthFailure(FunctionErrors.deleteAccountReason(response.status.value, response.bodyAsText()))
        }
    }

    override suspend fun forgetSession() {
        provider.client?.auth?.clearSession()
    }

    // redirectUrl = null: the email carries a code typed into the app, so no deep link is involved.
    override suspend fun requestPasswordReset(email: String): Result<Unit> = withClient {
        auth.resetPasswordForEmail(email.trim(), redirectUrl = null)
    }

    override suspend fun verifyRecoveryCode(email: String, code: String): Result<Unit> {
        // Raised before the call, so the session the server returns never shows up as SignedIn.
        recovering.value = true
        return withClient {
            auth.verifyEmailOtp(type = OtpType.Email.RECOVERY, email = email.trim(), token = code.trim())
        }.onFailure { recovering.value = false }
    }

    // NonCancellable: the code is already spent, so leaving the screen mustn't abort the update halfway.
    override suspend fun setNewPassword(password: String): Result<Unit> = withContext(NonCancellable) {
        withClient { auth.updateUser { this.password = password } }
            .onSuccess { recovering.value = false }
    }

    override suspend fun cancelPasswordRecovery(): Result<Unit> = withContext(NonCancellable) {
        val result = withClient { auth.signOut() }
        // Offline the server logout fails; the recovery session must still go, or the user is stuck.
        if (result.isFailure) provider.client?.auth?.clearSession()
        recovering.value = false
        Result.success(Unit)
    }

    private suspend fun withClient(block: suspend io.github.jan.supabase.SupabaseClient.() -> Unit): Result<Unit> {
        val client = provider.client ?: return Result.failure(AuthFailure(AuthFailureReason.UNCONFIGURED))
        return try {
            client.block()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AuthFailure) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(AuthFailure(e.toAuthFailureReason(), e))
        }
    }

    /** Raw supabase-kt messages include the request URL and headers, so only the code is kept. */
    private fun Exception.toAuthFailureReason(): AuthFailureReason = when (this) {
        is RestException -> AuthFailureReason.fromCode(error)
        is HttpRequestException, is HttpRequestTimeoutException, is IOException -> AuthFailureReason.NETWORK
        else -> AuthFailureReason.UNKNOWN
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
