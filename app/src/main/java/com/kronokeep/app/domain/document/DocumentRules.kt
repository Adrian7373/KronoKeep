package com.kronokeep.app.domain.document

/** Mirrors the `documents` table and bucket constraints in supabase/migrations. */
object DocumentRules {
    const val MAX_BYTES: Long = 15L * 1024 * 1024
    const val MAX_IMAGE_SIDE_PX = 2048
    const val JPEG_QUALITY = 85
    const val BUCKET = "documents"

    /** Signed download URLs live at most this long (rule: ≤ 10 minutes). */
    const val SIGNED_URL_TTL_SECONDS = 10L * 60

    private val EXTENSIONS = mapOf(
        "image/jpeg" to "jpg",
        "image/png" to "png",
        "image/heic" to "heic",
        "application/pdf" to "pdf",
    )

    val ALLOWED_MIME_TYPES: Set<String> = EXTENSIONS.keys

    fun isAllowed(mimeType: String): Boolean = mimeType.lowercase() in EXTENSIONS

    fun extensionFor(mimeType: String): String =
        EXTENSIONS[mimeType.lowercase()] ?: throw IllegalArgumentException("Unsupported type: $mimeType")

    /** `{user_id}/{asset_id}/{document_id}.{ext}`. The document id doubles as the file uuid, so retries are idempotent. */
    fun storagePath(userId: String, assetId: String, documentId: String, mimeType: String): String {
        require(userId.isNotBlank() && assetId.isNotBlank() && documentId.isNotBlank())
        require('/' !in userId && '/' !in assetId && '/' !in documentId) { "Path segments must not contain '/'" }
        return "$userId/$assetId/$documentId.${extensionFor(mimeType)}"
    }
}
