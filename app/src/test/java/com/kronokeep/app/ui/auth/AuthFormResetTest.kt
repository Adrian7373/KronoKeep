package com.kronokeep.app.ui.auth

import com.kronokeep.app.domain.repository.AuthState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class AuthFormResetTest {

    private val signedIn = AuthState.SignedIn(userId = "u1", email = "rider@example.ph")

    @Test fun `a session opening clears the sign-in form`() {
        val typed = AuthUiState(
            mode = AuthMode.SIGN_IN,
            email = "rider@example.ph",
            password = "longenough",
            isBusy = true,
            error = "old",
        )
        assertEquals(AuthUiState(), typed.withAuthState(signedIn))
    }

    @Test fun `a session opening clears the sign-up and code steps`() {
        val signUp = AuthUiState(
            mode = AuthMode.SIGN_UP,
            email = "rider@example.ph",
            password = "longenough",
            confirmPassword = "longenough",
        )
        assertEquals(AuthUiState(), signUp.withAuthState(signedIn))
        assertEquals(AuthUiState(), signUp.toSignUpCode(info = "sent").copy(code = "123456").withAuthState(signedIn))
    }

    @Test fun `saving a new password clears the form`() {
        val newPassword = AuthUiState(
            mode = AuthMode.NEW_PASSWORD,
            email = "rider@example.ph",
            password = "newpassword",
            confirmPassword = "newpassword",
        )
        assertEquals(AuthUiState(), newPassword.withAuthState(signedIn))
    }

    @Test fun `an already blank form is kept as is`() {
        val blank = AuthUiState()
        assertSame(blank, blank.withAuthState(signedIn))
    }

    @Test fun `signing out after a session shows an empty sign-in form`() {
        val typed = AuthUiState(email = "rider@example.ph", password = "longenough")
        val afterSignOut = typed.withAuthState(signedIn).withAuthState(AuthState.SignedOut)
        assertEquals(AuthMode.SIGN_IN, afterSignOut.mode)
        assertEquals("", afterSignOut.email)
        assertEquals("", afterSignOut.password)
    }
}
