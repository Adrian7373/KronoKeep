package com.kronokeep.app.domain.model

import java.time.Instant

data class Document(
    val id: String,
    val assetId: String,
    val kind: DocumentKind,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: String,
    /** Set once the bytes are in the private bucket. */
    val storagePath: String?,
    /** App-private copy, kept only until the upload succeeds. */
    val localPath: String?,
    val createdAt: Instant,
    val syncState: SyncState,
) {
    val isUploaded: Boolean get() = storagePath != null
    val isImage: Boolean get() = mimeType.startsWith("image/")
}

/** A file already compressed and hashed on the device, ready to be recorded as a document. */
data class PreparedFile(
    val localPath: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: String,
)

/** Where the viewer reads a document's bytes from. Remote URLs are short-lived and must not be cached. */
sealed interface DocumentSource {
    data class Local(val path: String) : DocumentSource
    data class SignedUrl(val url: String, val expiresAt: Instant) : DocumentSource
}
