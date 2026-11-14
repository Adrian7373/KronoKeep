package com.kronokeep.app.domain.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentRulesTest {
    @Test fun allowedTypesMatchTheBucket() {
        assertEquals(setOf("image/jpeg", "image/png", "image/heic", "application/pdf"), DocumentRules.ALLOWED_MIME_TYPES)
        assertTrue(DocumentRules.isAllowed("IMAGE/JPEG"))
        assertFalse(DocumentRules.isAllowed("image/gif"))
        assertFalse(DocumentRules.isAllowed("application/zip"))
    }

    @Test fun limitsMatchTheSpec() {
        assertEquals(15_728_640L, DocumentRules.MAX_BYTES)
        assertEquals(2048, DocumentRules.MAX_IMAGE_SIDE_PX)
        assertEquals(85, DocumentRules.JPEG_QUALITY)
        assertTrue(DocumentRules.SIGNED_URL_TTL_SECONDS in 1..600)
    }

    @Test fun storagePathStartsWithOwner() {
        assertEquals(
            "u-1/a-1/d-1.pdf",
            DocumentRules.storagePath("u-1", "a-1", "d-1", "application/pdf"),
        )
        assertEquals("u/a/d.jpg", DocumentRules.storagePath("u", "a", "d", "image/jpeg"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun storagePathRejectsTraversal() {
        DocumentRules.storagePath("u-1/../u-2", "a", "d", "image/jpeg")
    }

    @Test(expected = IllegalArgumentException::class)
    fun storagePathRejectsUnknownType() {
        DocumentRules.storagePath("u", "a", "d", "image/gif")
    }
}
