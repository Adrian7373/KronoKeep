package com.kronokeep.app.domain.auth

import com.kronokeep.app.domain.repository.AuthFailure
import com.kronokeep.app.domain.repository.AuthFailureReason
import com.kronokeep.app.domain.repository.AuthRepository
import com.kronokeep.app.domain.repository.AuthState
import com.kronokeep.app.domain.repository.LocalDataEraser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Deletes the account on the server first, then erases this phone and drops the session. If the server
 * refuses (wrong password, offline), nothing local is touched. Once the server has deleted the account the
 * rest runs to completion even if the caller is cancelled, so the phone never keeps a dead account's vault.
 */
class DeleteAccountUseCase @Inject constructor(
    private val auth: AuthRepository,
    private val eraser: LocalDataEraser,
) {
    suspend operator fun invoke(password: String): Result<Unit> {
        if (auth.state.value !is AuthState.SignedIn) return Result.failure(AuthFailure(AuthFailureReason.UNKNOWN))
        if (password.isEmpty()) return Result.failure(AuthFailure(AuthFailureReason.INVALID_CREDENTIALS))

        auth.deleteAccount(password).onFailure { return Result.failure(it) }

        withContext(NonCancellable) {
            try {
                eraser.eraseAll()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The account is gone either way; leftover local rows can't sync anywhere.
            }
            auth.forgetSession()
        }
        return Result.success(Unit)
    }
}
