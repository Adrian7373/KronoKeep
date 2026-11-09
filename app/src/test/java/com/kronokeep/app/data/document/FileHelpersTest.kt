package com.kronokeep.app.data.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FileHelpersTest {

    @Test fun sha256MatchesKnownVectors() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Sha256.hex(ByteArray(0)))
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Sha256.hex("abc".toByteArray()),
        )
    }

    @Test fun sha256StreamEqualsBytesForLargeInput() {
        val data = ByteArray(200_000) { (it % 251).toByte() }
        assertEquals(Sha256.hex(data), Sha256.hex(data.inputStream()))
    }

    @Test fun fitCapsLongestSideAndKeepsAspect() {
        assertEquals(ImageScaling.Size(2048, 1536), ImageScaling.fit(4032, 3024, 2048))
        assertEquals(ImageScaling.Size(1536, 2048), ImageScaling.fit(3024, 4032, 2048))
        assertEquals(ImageScaling.Size(2048, 2048), ImageScaling.fit(5000, 5000, 2048))
    }

    @Test fun fitNeverUpscales() {
        assertEquals(ImageScaling.Size(1200, 800), ImageScaling.fit(1200, 800, 2048))
        assertEquals(ImageScaling.Size(2048, 10), ImageScaling.fit(2048, 10, 2048))
    }

    @Test fun fitKeepsThinImagesAtLeastOnePixel() {
        assertEquals(ImageScaling.Size(2048, 1), ImageScaling.fit(20_000, 2, 2048))
    }

    @Test fun sampleSizeKeepsEnoughPixelsForTheFinalScale() {
        assertEquals(1, ImageScaling.sampleSize(2048, 1000, 2048))
        assertEquals(1, ImageScaling.sampleSize(4000, 3000, 2048)) // 4000/2 = 2000 < 2048
        assertEquals(2, ImageScaling.sampleSize(4096, 3072, 2048))
        assertEquals(4, ImageScaling.sampleSize(9000, 12000, 2048))
    }

    @Test fun mimeTypesNormalize() {
        assertEquals("image/jpeg", MimeTypes.normalize("image/jpg"))
        assertEquals("image/jpeg", MimeTypes.normalize("IMAGE/JPEG; charset=binary"))
        assertEquals("image/heic", MimeTypes.normalize("image/heif"))
        assertEquals("application/pdf", MimeTypes.normalize("application/pdf"))
        assertNull(MimeTypes.normalize(""))
        assertNull(MimeTypes.normalize(null))
    }

    @Test fun mimeTypesFromFileName() {
        assertEquals("application/pdf", MimeTypes.fromFileName("OR-2026.PDF"))
        assertEquals("image/jpeg", MimeTypes.fromFileName("receipt.jpeg"))
        assertEquals("image/heic", MimeTypes.fromFileName("IMG_0001.HEIC"))
        assertNull(MimeTypes.fromFileName("notes.txt"))
        assertNull(MimeTypes.fromFileName("noextension"))
    }
}
