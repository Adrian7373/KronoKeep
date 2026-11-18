package com.kronokeep.app.ui.auth

import com.kronokeep.app.domain.repository.AuthFailureReason
import com.kronokeep.app.domain.repository.AuthState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordResetTest {

    @Test fun `email codes are 6 to 10 digits`() {
        assertTrue(AuthValidation.isValidEmailCode("123456"))
        assertTrue(AuthValidation.isValidEmailCode("1234567890"))
        assertFalse(AuthValidation.isValidEmailCode("12345"))
        assertFalse(AuthValidation.isValidEmailCode("12345678901"))
        assertFalse(AuthValidation.isValidEmailCode("12a456"))
        assertFalse(AuthValidation.isValidEmailCode(""))
    }

    @Test fun `pasted codes keep only digits and are capped`() {
        assertEquals("123456", AuthValidation.normalizeCode(" 123 456 "))
        assertEquals("123456", AuthValidation.normalizeCode("123-456"))
        assertEquals("1234567890", AuthValidation.normalizeCode("123456789012"))
        assertEquals("", AuthValidation.normalizeCode("abc"))
    }

    @Test fun `each reset step needs its own input`() {
        val request = AuthUiState(mode = AuthMode.RESET_REQUEST)
        assertFalse(request.canSubmit)
        assertTrue(request.copy(email = "rider@example.ph").canSubmit)
        assertFalse(request.copy(email = "rider@example.ph", isBusy = true).canSubmit)

        val code = AuthUiState(mode = AuthMode.RESET_CODE, email = "rider@example.ph")
        assertFalse(code.copy(code = "12345").canSubmit)
        assertTrue(code.copy(code = "123456").canSubmit)

        val newPassword = AuthUiState(mode = AuthMode.NEW_PASSWORD)
        assertFalse(newPassword.copy(password = "short", confirmPassword = "short").canSubmit)
        assertFalse(newPassword.copy(password = "longenough", confirmPassword = "longenougH").canSubmit)
        assertTrue(newPassword.copy(password = "longenough", confirmPassword = "longenough").canSubmit)
    }

    @Test fun `mismatch shows only once the confirm field has text`() {
        assertFalse(AuthUiState(password = "longenough").passwordsMismatch)
        assertTrue(AuthUiState(password = "longenough", confirmPassword = "long").passwordsMismatch)
        assertFalse(AuthUiState(password = "longenough", confirmPassword = "longenough").passwordsMismatch)
    }

    @Test fun `a recovery session is not shown as signed in`() {
        val signedIn = AuthState.SignedIn("user-1", "rider@example.ph")
        assertEquals(AuthState.PasswordRecovery("rider@example.ph"), AuthState.withRecovery(signedIn, recovering = true))
        assertSame(signedIn, AuthState.withRecovery(signedIn, recovering = false))
        // Raising the flag before the code is checked must not change anything while signed out.
        assertSame(AuthState.SignedOut, AuthState.withRecovery(AuthState.SignedOut, recovering = true))
        assertSame(AuthState.Loading, AuthState.withRecovery(AuthState.Loading, recovering = true))
    }

    @Test fun `an accepted code moves the screen to the new-password step`() {
        val typing = AuthUiState(mode = AuthMode.RESET_CODE, email = "rider@example.ph", code = "123456", isBusy = true, info = "sent")
        val next = typing.withAuthState(AuthState.PasswordRecovery("rider@example.ph"))
        assertEquals(AuthMode.NEW_PASSWORD, next.mode)
        assertEquals("", next.code)
        assertEquals(null, next.info)
        // Further emissions of the same state don't wipe what the user is typing.
        val typed = next.copy(password = "longenough")
        assertSame(typed, typed.withAuthState(AuthState.PasswordRecovery("rider@example.ph")))
    }

    @Test fun `cancelling recovery returns to sign-in, other screens ignore sign-out`() {
        val recovering = AuthUiState(mode = AuthMode.NEW_PASSWORD, password = "longenough", confirmPassword = "longenough")
        val out = recovering.withAuthState(AuthState.SignedOut)
        assertEquals(AuthMode.SIGN_IN, out.mode)
        assertEquals("", out.password)

        val requesting = AuthUiState(mode = AuthMode.RESET_REQUEST, email = "rider@example.ph")
        assertSame(requesting, requesting.withAuthState(AuthState.SignedOut))
    }

    @Test fun `recovery error codes map to reasons`() {
        assertEquals(AuthFailureReason.INVALID_CODE, AuthFailureReason.fromCode("otp_expired"))
        assertEquals(AuthFailureReason.SAME_PASSWORD, AuthFailureReason.fromCode("same_password"))
        assertEquals(AuthFailureReason.RATE_LIMITED, AuthFailureReason.fromCode("over_email_send_rate_limit"))
    }

    @Test fun `code-sent copy doesn't reveal whether the account exists`() {
        val shown = AuthMessages.codeSent("  rider@example.ph ")
        assertTrue(shown.startsWith("If an account exists for rider@example.ph,"))
    }
}
