package com.kronokeep.app.domain.auth

import com.kronokeep.app.domain.repository.AuthFailure
import com.kronokeep.app.domain.repository.AuthFailureReason
import com.kronokeep.app.domain.repository.AuthState
import com.kronokeep.app.domain.repository.LocalDataEraser
import com.kronokeep.app.domain.repository.PushRegistration
import com.kronokeep.app.testing.FakeAuth
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignOutUseCaseTest {
    private val auth = FakeAuth()
    private var erased = 0
    private var owner: String? = "user-1"
    private val vault = object : LocalVault {
        override suspend fun owner() = owner
        override suspend fun setOwner(userId: String) { owner = userId }
        override suspend fun clearOwner() { owner = null }
        override suspend fun holdsRowsOfAnotherAccount(userId: String) = false
    }
    private val eraser = object : LocalDataEraser {
        override suspend fun eraseAll() { erased++ }
    }
    private val push = NoPush()

    private class NoPush : PushRegistration {
        var unregistered = 0
        override suspend fun unregister() { unregistered++ }
    }

    @Test fun `signs out, then erases the vault`() = runBlocking {
        val result = SignOutUseCase(auth, push, VaultOwnership(vault, eraser))()

        assertTrue(result.isSuccess)
        assertEquals(1, push.unregistered)
        assertEquals(AuthState.SignedOut, auth.state.value)
        assertEquals(1, erased)
        assertNull(owner)
    }

    @Test fun `a failed sign-out keeps the vault`() = runBlocking {
        val failing = object : com.kronokeep.app.domain.repository.AuthRepository by auth {
            override suspend fun signOut() = Result.failure<Unit>(AuthFailure(AuthFailureReason.NETWORK))
        }

        assertTrue(SignOutUseCase(failing, push, VaultOwnership(vault, eraser))().isFailure)
        assertEquals(0, erased)
        assertEquals("user-1", owner)
    }
}
