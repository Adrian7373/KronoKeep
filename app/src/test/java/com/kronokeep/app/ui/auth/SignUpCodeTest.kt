package com.kronokeep.app.ui.auth

import com.kronokeep.app.domain.repository.AuthFailure
import com.kronokeep.app.domain.repository.AuthFailureReason
import com.kronokeep.app.domain.repository.AuthState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SignUpCodeTest {

    private val signUp = AuthUiState(
        mode = AuthMode.SIGN_UP,
        email = "rider@example.ph",
        password = "longenough",
        confirmPassword = "longenough",
        isBusy = true,
    )

    @Test fun `the code step keeps the email and drops the passwords`() {
        val next = signUp.toSignUpCode(info = "sent")
        assertEquals(AuthMode.SIGNUP_CODE, next.mode)
        assertEquals("rider@example.ph", next.email)
        assertEquals("", next.password)
        assertEquals("", next.confirmPassword)
        assertFalse(next.isBusy)
        assertEquals("sent", next.info)
    }

    @Test fun `the code step needs a 6 to 10 digit code`() {
        val step = signUp.toSignUpCode(info = null)
        assertFalse(step.canSubmit)
        assertFalse(step.copy(code = "12345").canSubmit)
        assertTrue(step.copy(code = "123456").canSubmit)
        assertFalse(step.copy(code = "123456", isBusy = true).canSubmit)
    }

    @Test fun `signing in unconfirmed goes to the code step`() {
        val signIn = AuthUiState(mode = AuthMode.SIGN_IN, email = "rider@example.ph", password = "longenough")
        val next = signIn.afterSignInFailure(AuthFailure(AuthFailureReason.EMAIL_NOT_CONFIRMED))!!
        assertEquals(AuthMode.SIGNUP_CODE, next.mode)
        assertEquals("rider@example.ph", next.email)
        assertEquals("", next.password)
        assertEquals(AuthMessages.NOT_CONFIRMED_YET, next.info)
    }

    @Test fun `other sign-in failures keep the usual error`() {
        val signIn = AuthUiState(mode = AuthMode.SIGN_IN, email = "rider@example.ph", password = "longenough")
        assertNull(signIn.afterSignInFailure(AuthFailure(AuthFailureReason.INVALID_CREDENTIALS)))
        assertNull(signIn.afterSignInFailure(IllegalStateException("boom")))
    }

    @Test fun `the tab switch and sign-out leave the code step alone`() {
        val step = signUp.toSignUpCode(info = null).copy(code = "123")
        assertSame(step, step.withSignUp(false))
        assertSame(step, step.withSignUp(true))
        assertSame(step, step.withAuthState(AuthState.SignedOut))
    }

    @Test fun `the sent message names the address`() {
        assertTrue(AuthMessages.signUpCodeSent(" rider@example.ph ").contains("rider@example.ph."))
    }
}
