package com.kronokeep.app.ui.settings

import com.kronokeep.app.domain.repository.AuthFailure
import com.kronokeep.app.domain.repository.AuthFailureReason
import com.kronokeep.app.ui.auth.AuthMessages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeleteAccountMessagesTest {
    @Test fun `a wrong password talks about the password, not the email`() {
        assertEquals(
            "That password isn't right.",
            DeleteAccountMessages.forFailure(AuthFailure(AuthFailureReason.INVALID_CREDENTIALS)),
        )
    }

    @Test fun `other reasons reuse the sign-in copy`() {
        assertEquals(
            AuthMessages.forReason(AuthFailureReason.NETWORK),
            DeleteAccountMessages.forFailure(AuthFailure(AuthFailureReason.NETWORK)),
        )
    }

    @Test fun `raw exceptions never reach the dialog`() {
        val shown = DeleteAccountMessages.forFailure(
            IllegalStateException("https://x.supabase.co/functions/v1/delete-account apikey=sb_x"),
        )
        assertFalse(shown.contains("supabase"))
        assertEquals(AuthMessages.forReason(AuthFailureReason.UNKNOWN), shown)
    }

    @Test fun `submit needs a password and no request in flight`() {
        assertFalse(DeleteAccountUi().canSubmit)
        assertTrue(DeleteAccountUi(password = "x").canSubmit)
        assertFalse(DeleteAccountUi(password = "x", busy = true).canSubmit)
    }
}
