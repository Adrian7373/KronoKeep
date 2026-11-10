package com.kronokeep.app.data.remote

import com.kronokeep.app.domain.document.DocumentRules
import io.github.jan.supabase.storage.storage
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

/** The private `documents` bucket. Only signed URLs are used; never public URLs. */
interface DocumentStorage {
    /** Uploads through a signed upload URL. Upsert, so a retried upload of the same path is harmless. */
    suspend fun upload(path: String, bytes: ByteArray, mimeType: String)

    suspend fun signedDownloadUrl(path: String, ttlSeconds: Long): String

    /** Removes an object whose row has been soft-deleted on the server. */
    suspend fun delete(path: String)
}

class CloudNotConfiguredException : IllegalStateException("Cloud sync isn't configured")

@Singleton
class SupabaseDocumentStorage @Inject constructor(
    private val provider: SupabaseProvider,
) : DocumentStorage {

    private fun bucket() = (provider.client ?: throw CloudNotConfiguredException()).storage.from(DocumentRules.BUCKET)

    override suspend fun upload(path: String, bytes: ByteArray, mimeType: String) {
        val bucket = bucket()
        val signed = bucket.createSignedUploadUrl(path, upsert = true)
        bucket.uploadToSignedUrl(path, signed.token, bytes) {
            upsert = true
            contentType = ContentType.parse(mimeType)
        }
    }

    override suspend fun signedDownloadUrl(path: String, ttlSeconds: Long): String {
        require(ttlSeconds in 1..DocumentRules.SIGNED_URL_TTL_SECONDS) { "Signed URLs must expire within 10 minutes" }
        return bucket().createSignedUrl(path, ttlSeconds.seconds)
    }

    override suspend fun delete(path: String) {
        bucket().delete(path)
    }
}

/** Fetches a signed URL into memory. The HTTP client has no cache, and nothing is written to disk here. */
@Singleton
class SignedUrlDownloader @Inject constructor(private val http: HttpClient) {
    suspend fun download(url: String): ByteArray {
        val response = http.get(url) { header(HttpHeaders.CacheControl, "no-store") }
        check(response.status.isSuccess()) { "Download failed (${response.status.value})" }
        return response.bodyAsBytes()
    }
}
