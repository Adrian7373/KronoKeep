package com.kronokeep.app.domain.auth

import com.kronokeep.app.domain.repository.AuthRepository
import com.kronokeep.app.domain.repository.PushRegistration
import javax.inject.Inject

/** Drops this phone's push token first: the RPC needs the session that sign-out clears. */
class SignOutUseCase @Inject constructor(
    private val auth: AuthRepository,
    private val push: PushRegistration,
) {
    suspend operator fun invoke(): Result<Unit> {
        push.unregister()
        return auth.signOut()
    }
}
