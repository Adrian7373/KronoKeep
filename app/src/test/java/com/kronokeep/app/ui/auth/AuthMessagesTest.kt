package com.kronokeep.app.ui.auth

import com.kronokeep.app.domain.repository.AuthFailure
import com.kronokeep.app.domain.repository.AuthFailureReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthMessagesTest {
    @Test fun `supabase error codes map to reasons`() {
        assertEquals(AuthFailureReason.INVALID_CREDENTIALS, AuthFailureReason.fromCode("invalid_credentials"))
        assertEquals(AuthFailureReason.INVALID_CREDENTIALS, AuthFailureReason.fromCode(" Invalid_Credentials "))
        assertEquals(AuthFailureReason.EMAIL_NOT_CONFIRMED, AuthFailureReason.fromCode("email_not_confirmed"))
        assertEquals(AuthFailureReason.ACCOUNT_EXISTS, AuthFailureReason.fromCode("user_already_exists"))
        assertEquals(AuthFailureReason.ACCOUNT_EXISTS, AuthFailureReason.fromCode("email_exists"))
        assertEquals(AuthFailureReason.WEAK_PASSWORD, AuthFailureReason.fromCode("weak_password"))
        assertEquals(AuthFailureReason.RATE_LIMITED, AuthFailureReason.fromCode("over_request_rate_limit"))
        assertEquals(AuthFailureReason.SIGNUP_DISABLED, AuthFailureReason.fromCode("signup_disabled"))
        assertEquals(AuthFailureReason.UNKNOWN, AuthFailureReason.fromCode("something_new"))
        assertEquals(AuthFailureReason.UNKNOWN, AuthFailureReason.fromCode(null))
    }

    @Test fun `every reason has copy`() {
        AuthFailureReason.entries.forEach { assertTrue(AuthMessages.forReason(it).isNotBlank()) }
    }

    @Test fun `raw exception text never reaches the screen`() {
        val leaky = IllegalStateException("invalid_credentials URL: https://x.supabase.co/auth/v1/token Headers: apikey=sb_x")
        val shown = AuthMessages.forFailure(leaky)
        assertEquals(AuthMessages.forReason(AuthFailureReason.UNKNOWN), shown)
        assertFalse(shown.contains("http"))
        assertEquals(
            AuthMessages.forReason(AuthFailureReason.INVALID_CREDENTIALS),
            AuthMessages.forFailure(AuthFailure(AuthFailureReason.INVALID_CREDENTIALS, leaky)),
        )
    }
}
