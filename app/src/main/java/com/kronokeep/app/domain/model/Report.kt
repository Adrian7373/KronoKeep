package com.kronokeep.app.domain.model

import java.time.Instant

/**
 * A verified maintenance report, generated server-side by `generate-report`. Reports live only on the server
 * (they aren't mirrored in Room): generating, sharing and revoking all need a connection anyway.
 */
data class Report(
    val id: String,
    val assetId: String,
    /** Object path in the private `reports` bucket. */
    val storagePath: String,
    /** SHA-256 (hex) of the canonical JSON snapshot printed on the PDF. */
    val contentHash: String,
    val generatedAt: Instant,
    val revokedAt: Instant?,
    /** When the current share link stops working; null when the report was never shared. */
    val shareExpiresAt: Instant?,
    val logCount: Int,
) {
    val isRevoked: Boolean get() = revokedAt != null

    fun isShareActive(now: Instant): Boolean = !isRevoked && shareExpiresAt?.isAfter(now) == true

    /** Grouped for reading aloud or comparing with the printout: "3f2a 9c01 …". */
    val shortHash: String get() = contentHash.take(16).chunked(4).joinToString(" ")
}

/** A revocable 7-day link. It goes through `verify-report`, which redirects to a short-lived signed URL. */
data class ShareLink(val url: String, val expiresAt: Instant)
