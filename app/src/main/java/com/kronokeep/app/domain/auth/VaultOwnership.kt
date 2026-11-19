package com.kronokeep.app.domain.auth

import com.kronokeep.app.domain.repository.AuthState
import com.kronokeep.app.domain.repository.LocalDataEraser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Records which account the data on this phone belongs to. */
interface LocalVault {
    suspend fun owner(): String?
    suspend fun setOwner(userId: String)
    suspend fun clearOwner()

    /** True if any local row is stamped with an account other than [userId] (rows not yet claimed don't count). */
    suspend fun holdsRowsOfAnotherAccount(userId: String): Boolean
}

/**
 * Room isn't partitioned by account, so the phone holds one account's vault at a time. Signing out erases
 * it; a session for a different account (after a session expiry, a crash mid sign-out, or an install from
 * before this existed) erases it before that account sees anything. Signing back in as the owner keeps it.
 */
@Singleton
class VaultOwnership @Inject constructor(
    private val vault: LocalVault,
    private val eraser: LocalDataEraser,
) {
    // One lock for both paths, so a sign-in right after a sign-out waits for the erase to finish.
    private val mutex = Mutex()

    suspend fun isOwnedBy(userId: String): Boolean = mutex.withLock { vault.owner() == userId }

    suspend fun prepareFor(userId: String) = withContext(NonCancellable) {
        mutex.withLock {
            val owner = vault.owner()
            if (owner == userId) return@withLock
            if (owner != null || vault.holdsRowsOfAnotherAccount(userId)) eraseQuietly()
            vault.setOwner(userId)
        }
    }

    /** After sign-out: nothing of the account stays on the phone. */
    suspend fun release() = withContext(NonCancellable) {
        mutex.withLock {
            eraseQuietly()
            vault.clearOwner()
        }
    }

    private suspend fun eraseQuietly() {
        try {
            eraser.eraseAll()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The eraser is best effort per step; never leave the user stuck on a spinner over it.
        }
    }
}

/**
 * Holds a new session back as [AuthState.Loading] until the vault belongs to it, so sync, push and the UI
 * never see another account's rows. A session the vault already belongs to passes straight through.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun Flow<AuthState>.gatedBy(ownership: VaultOwnership): Flow<AuthState> =
    distinctUntilChanged().transformLatest { state ->
        if (state is AuthState.SignedIn && state.userId.isNotBlank() && !ownership.isOwnedBy(state.userId)) {
            emit(AuthState.Loading)
            ownership.prepareFor(state.userId)
        }
        emit(state)
    }.distinctUntilChanged()
