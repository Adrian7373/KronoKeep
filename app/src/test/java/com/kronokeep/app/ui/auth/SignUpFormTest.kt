package com.kronokeep.app.ui.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SignUpFormTest {

    private val signUp = AuthUiState(mode = AuthMode.SIGN_UP, email = "rider@example.ph", password = "longenough")

    @Test
    fun signUpNeedsMatchingConfirmation() {
        assertFalse(signUp.canSubmit)
        assertFalse(signUp.copy(confirmPassword = "longenougH").canSubmit)
        assertTrue(signUp.copy(confirmPassword = "longenough").canSubmit)
    }

    @Test
    fun signInIgnoresConfirmation() {
        assertTrue(signUp.copy(mode = AuthMode.SIGN_IN).canSubmit)
    }

    @Test
    fun passwordsMatchNeedsBothFieldsFilled() {
        assertFalse(AuthUiState().passwordsMatch)
        assertFalse(AuthUiState(password = "longenough").passwordsMatch)
        assertTrue(AuthUiState(password = "longenough", confirmPassword = "longenough").passwordsMatch)
    }

    @Test
    fun switchingTabsClearsConfirmationAndMessages() {
        val filled = signUp.copy(confirmPassword = "longenough", error = "boom", info = "hi")
        val signIn = filled.withSignUp(false)
        assertEquals(AuthMode.SIGN_IN, signIn.mode)
        assertEquals("", signIn.confirmPassword)
        assertEquals("longenough", signIn.password)
        assertEquals(null, signIn.error)
        assertEquals(null, signIn.info)
        assertEquals(AuthMode.SIGN_UP, signIn.withSignUp(true).mode)
    }

    @Test
    fun switchingIsIgnoredOutsideSignInAndSignUp() {
        val reset = AuthUiState(mode = AuthMode.NEW_PASSWORD, password = "longenough", confirmPassword = "longenough")
        assertSame(reset, reset.withSignUp(true))
        assertSame(signUp, signUp.withSignUp(true))
    }
}
