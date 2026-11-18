package com.kronokeep.app.data.remote

import com.kronokeep.app.BuildConfig
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.storage.storage
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

/** A `reports` row as the owner can read it (RLS: owner select only). */
@Serializable
data class ReportDto(
    val id: String,
    @SerialName("asset_id") val assetId: String,
    @SerialName("storage_path") val storagePath: String,
    @SerialName("content_hash") val contentHash: String,
    @SerialName("generated_at") val generatedAt: String,
    @SerialName("revoked_at") val revokedAt: String? = null,
    @SerialName("share_expires_at") val shareExpiresAt: String? = null,
    @SerialName("log_count") val logCount: Int,
)

/** `generate-report` 200 response. */
@Serializable
data class GeneratedReportDto(
    val id: String,
    @SerialName("asset_id") val assetId: String,
    @SerialName("storage_path") val storagePath: String,
    @SerialName("content_hash") val contentHash: String,
    @SerialName("generated_at") val generatedAt: String,
    @SerialName("log_count") val logCount: Int,
    val reused: Boolean = false,
)

/** `share_report` RPC result. The token is shown once; the server keeps only its hash. */
@Serializable
data class ShareTokenDto(val token: String, @SerialName("expires_at") val expiresAt: String)

/**
 * A non-2xx answer from the backend. [code] is the `error` field of the JSON body (generate-report) or the
 * Postgres error code (RPCs), when one could be read.
 */
class ReportRemoteException(val status: Int, val code: String?, message: String?) : Exception(message)

interface ReportRemote {
    /** Project base URL, used to build verify/share links. */
    val projectUrl: String

    suspend fun listForAsset(assetId: String): List<ReportDto>
    suspend fun generate(assetId: String): GeneratedReportDto
    suspend fun share(reportId: String): ShareTokenDto
    suspend fun revoke(reportId: String)
    suspend fun signedPdfUrl(path: String, ttlSeconds: Long): String
}

@Singleton
class SupabaseReportRemote @Inject constructor(private val provider: SupabaseProvider) : ReportRemote {

    private fun client() = provider.client ?: throw CloudNotConfiguredException()

    override val projectUrl: String get() = BuildConfig.SUPABASE_URL.trimEnd('/')

    override suspend fun listForAsset(assetId: String): List<ReportDto> = call {
        client().postgrest.from(TABLE).select(Columns.list(COLUMNS)) {
            filter { eq("asset_id", assetId) }
            order("generated_at", Order.DESCENDING)
        }.decodeList<ReportDto>()
    }

    override suspend fun generate(assetId: String): GeneratedReportDto = call {
        val response = client().functions.invoke("generate-report", buildJsonObject { put("asset_id", assetId) })
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) throw errorFromBody(response.status.value, text)
        json.decodeFromString<GeneratedReportDto>(text)
    }

    override suspend fun share(reportId: String): ShareTokenDto = call {
        client().postgrest.rpc("share_report", buildJsonObject { put("p_report_id", reportId) }).decodeAs<ShareTokenDto>()
    }

    override suspend fun revoke(reportId: String) {
        call { client().postgrest.rpc("revoke_report", buildJsonObject { put("p_report_id", reportId) }) }
    }

    override suspend fun signedPdfUrl(path: String, ttlSeconds: Long): String = call {
        client().storage.from(BUCKET).createSignedUrl(path, ttlSeconds.seconds)
    }

    private suspend fun <T> call(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: PostgrestRestException) {
        throw ReportRemoteException(e.statusCode, e.code, e.error)
    } catch (e: RestException) {
        // Not e.message: supabase-kt puts the request URL and headers in it.
        throw errorFromBody(e.statusCode, e.error, e.description)
    }

    companion object {
        const val TABLE = "reports"
        const val BUCKET = "reports"
        private const val COLUMNS =
            "id,asset_id,storage_path,content_hash,generated_at,revoked_at,share_expires_at,log_count"
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Edge Functions answer `{"error": code, "message": text}`; PostgREST answers `{"code": "P0002", "message": …}`.
         * supabase-kt sometimes hands us the raw body and sometimes just the message, so both are tried.
         */
        fun errorFromBody(status: Int, body: String?, fallback: String? = null): ReportRemoteException {
            val obj = runCatching { json.parseToJsonElement(body.orEmpty()).jsonObject }.getOrNull()
            val code = obj?.get("error")?.jsonPrimitive?.content ?: obj?.get("code")?.jsonPrimitive?.content
            val message = obj?.get("message")?.jsonPrimitive?.content ?: fallback ?: body
            return ReportRemoteException(status, code, message)
        }
    }
}
