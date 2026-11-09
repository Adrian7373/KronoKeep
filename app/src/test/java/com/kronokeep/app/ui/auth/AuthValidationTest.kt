package com.kronokeep.app.ui.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthValidationTest {
    @Test fun emails() {
        assertTrue(AuthValidation.isValidEmail("rider@example.ph"))
        assertTrue(AuthValidation.isValidEmail("  rider@example.ph "))
        assertFalse(AuthValidation.isValidEmail("rider@"))
        assertFalse(AuthValidation.isValidEmail("rider example@x.com"))
        assertFalse(AuthValidation.isValidEmail(""))
    }

    @Test fun passwords() {
        assertTrue(AuthValidation.isValidPassword("12345678"))
        assertFalse(AuthValidation.isValidPassword("1234567"))
    }
}
