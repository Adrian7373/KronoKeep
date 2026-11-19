package com.kronokeep.app.domain.auth

import com.kronokeep.app.domain.repository.AuthRepository
import com.kronokeep.app.domain.repository.PushRegistration
import javax.inject.Inject

/**
 * Drops this phone's push token first (the RPC needs the session that sign-out clears), signs out, then
 * erases the local vault so the next account starts empty. Unsynced changes go with it: Settings warns first.
 */
class SignOutUseCase @Inject constructor(
    private val auth: AuthRepository,
    private val push: PushRegistration,
    private val ownership: VaultOwnership,
) {
    suspend operator fun invoke(): Result<Unit> {
        push.unregister()
        return auth.signOut().onSuccess { ownership.release() }
    }
}
