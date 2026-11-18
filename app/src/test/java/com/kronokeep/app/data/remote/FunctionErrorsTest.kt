package com.kronokeep.app.data.remote

import com.kronokeep.app.domain.repository.AuthFailureReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FunctionErrorsTest {
    @Test fun `reads the error code from a function body`() {
        assertEquals("invalid_password", FunctionErrors.code("""{"error":"invalid_password","message":"no"}"""))
        assertNull(FunctionErrors.code("not json"))
        assertNull(FunctionErrors.code("""{"message":"no code"}"""))
        assertNull(FunctionErrors.code(null))
    }

    @Test fun `maps delete-account failures to reasons`() {
        assertEquals(
            AuthFailureReason.INVALID_CREDENTIALS,
            FunctionErrors.deleteAccountReason(403, """{"error":"invalid_password","message":"That password isn't right."}"""),
        )
        assertEquals(AuthFailureReason.INVALID_CREDENTIALS, FunctionErrors.deleteAccountReason(403, "invalid_password"))
        assertEquals(AuthFailureReason.RATE_LIMITED, FunctionErrors.deleteAccountReason(429, null))
        assertEquals(AuthFailureReason.RATE_LIMITED, FunctionErrors.deleteAccountReason(400, """{"error":"rate_limited"}"""))
        assertEquals(AuthFailureReason.UNKNOWN, FunctionErrors.deleteAccountReason(500, """{"error":"internal"}"""))
        assertEquals(AuthFailureReason.UNKNOWN, FunctionErrors.deleteAccountReason(502, "<html>Bad gateway</html>"))
    }
}
