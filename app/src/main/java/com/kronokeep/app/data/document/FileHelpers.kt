package com.kronokeep.app.data.document

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.roundToInt

object Sha256 {
    fun hex(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().toHex()
    }

    fun hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    fun hex(file: File): String = file.inputStream().use(::hex)

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}

/** Pure sizing maths for on-device compression (longest side capped, aspect ratio kept). */
object ImageScaling {
    data class Size(val width: Int, val height: Int)

    /** Largest power-of-two subsample that still leaves the longest side ≥ [maxSide], to save memory while decoding. */
    fun sampleSize(width: Int, height: Int, maxSide: Int): Int {
        require(width > 0 && height > 0 && maxSide > 0)
        var sample = 1
        val longest = max(width, height)
        while (longest / (sample * 2) >= maxSide) sample *= 2
        return sample
    }

    /** Final size with the longest side at most [maxSide]. Never upscales. */
    fun fit(width: Int, height: Int, maxSide: Int): Size {
        require(width > 0 && height > 0 && maxSide > 0)
        val longest = max(width, height)
        if (longest <= maxSide) return Size(width, height)
        val scale = maxSide.toDouble() / longest
        return Size(
            width = (width * scale).roundToInt().coerceIn(1, maxSide),
            height = (height * scale).roundToInt().coerceIn(1, maxSide),
        )
    }
}

/** Normalises the MIME types pickers report into the four the bucket accepts (or passes others through). */
object MimeTypes {
    fun normalize(mimeType: String?): String? = when (val m = mimeType?.lowercase()?.substringBefore(';')?.trim()) {
        null, "" -> null
        "image/jpg", "image/pjpeg" -> "image/jpeg"
        "image/heif", "image/heic-sequence", "image/heif-sequence" -> "image/heic"
        else -> m
    }

    fun fromFileName(name: String?): String? = when (name?.substringAfterLast('.', "")?.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "heic", "heif" -> "image/heic"
        "pdf" -> "application/pdf"
        else -> null
    }
}

/** Plain file access, behind an interface so the uploader can be tested without a filesystem. */
interface LocalFileStore {
    fun exists(path: String): Boolean
    fun read(path: String): ByteArray?
    fun delete(path: String)
}

class DiskFileStore @Inject constructor() : LocalFileStore {
    override fun exists(path: String): Boolean = File(path).isFile
    override fun read(path: String): ByteArray? = File(path).takeIf { it.isFile }?.readBytes()
    override fun delete(path: String) {
        File(path).delete()
    }
}
