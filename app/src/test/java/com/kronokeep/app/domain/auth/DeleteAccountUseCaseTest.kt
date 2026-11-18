package com.kronokeep.app.domain.auth

import com.kronokeep.app.domain.repository.AuthFailure
import com.kronokeep.app.domain.repository.AuthFailureReason
import com.kronokeep.app.domain.repository.AuthState
import com.kronokeep.app.domain.repository.LocalDataEraser
import com.kronokeep.app.testing.FakeAuth
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeleteAccountUseCaseTest {
    private val auth = FakeAuth()
    private val eraser = RecordingEraser()
    private val useCase = DeleteAccountUseCase(auth, eraser)

    private class RecordingEraser : LocalDataEraser {
        var erased = 0
        var fail = false
        override suspend fun eraseAll() {
            erased++
            if (fail) error("disk full")
        }
    }

    @Test fun `deletes on the server, then erases the phone, then drops the session`() = runBlocking {
        val result = useCase("hunter2")

        assertTrue(result.isSuccess)
        assertEquals(listOf("hunter2"), auth.deleteCalls)
        assertEquals(1, eraser.erased)
        assertTrue(auth.sessionForgotten)
        assertEquals(AuthState.SignedOut, auth.state.value)
    }

    @Test fun `a wrong password leaves local data and the session alone`() = runBlocking {
        auth.deleteResult = Result.failure(AuthFailure(AuthFailureReason.INVALID_CREDENTIALS))

        val result = useCase("wrong")

        assertEquals(AuthFailureReason.INVALID_CREDENTIALS, (result.exceptionOrNull() as AuthFailure).reason)
        assertEquals(0, eraser.erased)
        assertFalse(auth.sessionForgotten)
        assertTrue(auth.state.value is AuthState.SignedIn)
    }

    @Test fun `offline fails without touching anything`() = runBlocking {
        auth.deleteResult = Result.failure(AuthFailure(AuthFailureReason.NETWORK))

        assertTrue(useCase("hunter2").isFailure)
        assertEquals(0, eraser.erased)
        assertFalse(auth.sessionForgotten)
    }

    @Test fun `a failed local erase still signs out, because the account is already gone`() = runBlocking {
        eraser.fail = true

        assertTrue(useCase("hunter2").isSuccess)
        assertTrue(auth.sessionForgotten)
    }

    @Test fun `an empty password never reaches the server`() = runBlocking {
        val result = useCase("")

        assertEquals(AuthFailureReason.INVALID_CREDENTIALS, (result.exceptionOrNull() as AuthFailure).reason)
        assertTrue(auth.deleteCalls.isEmpty())
    }

    @Test fun `only a signed-in account can be deleted`() = runBlocking {
        for (state in listOf(AuthState.SignedOut, AuthState.Unconfigured, AuthState.Loading, AuthState.PasswordRecovery("me@example.com"))) {
            auth.state.value = state
            assertTrue(useCase("hunter2").isFailure)
        }
        assertTrue(auth.deleteCalls.isEmpty())
        assertEquals(0, eraser.erased)
    }
}
