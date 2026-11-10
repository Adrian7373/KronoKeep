package com.kronokeep.app.data.remote

import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Keyset position for pulls: rows strictly after (`updated_at`, `id`). Many rows can share one `now()`. */
data class PullCursor(val updatedAt: Instant, val id: String)

/** The server refused a row (RLS, CHECK, locked log...). Retrying the same payload won't help. */
class SyncRejectedException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Row-level access to the synced Postgres tables, as raw JSON so one engine can drive every table.
 * Anything other than [SyncRejectedException] is treated as transient (offline, timeout, expired JWT).
 */
interface SyncRemote {
    suspend fun fetchByIds(table: String, ids: List<String>): List<JsonObject>

    /** Upserts by primary key and returns the stored rows, including the server's `updated_at`. */
    suspend fun upsert(table: String, rows: List<JsonObject>): List<JsonObject>

    /** Rows after [after] in (`updated_at`, `id`) order. RLS already limits this to the caller's rows. */
    suspend fun pullPage(table: String, after: PullCursor?, limit: Int): List<JsonObject>
}

@Singleton
class SupabaseSyncRemote @Inject constructor(private val provider: SupabaseProvider) : SyncRemote {

    private fun db() = (provider.client ?: throw CloudNotConfiguredException()).postgrest

    override suspend fun fetchByIds(table: String, ids: List<String>): List<JsonObject> = call {
        if (ids.isEmpty()) return@call emptyList()
        db().from(table).select(Columns.ALL) { filter { isIn("id", ids) } }.decodeList<JsonObject>()
    }

    override suspend fun upsert(table: String, rows: List<JsonObject>): List<JsonObject> = call {
        if (rows.isEmpty()) return@call emptyList()
        db().from(table).upsert(JsonArray(rows)) {
            onConflict = "id"
            select()
        }.decodeList<JsonObject>()
    }

    override suspend fun pullPage(table: String, after: PullCursor?, limit: Int): List<JsonObject> = call {
        db().from(table).select(Columns.ALL) {
            if (after != null) {
                val ts = after.updatedAt.toString()
                filter {
                    or {
                        gt("updated_at", ts)
                        and {
                            eq("updated_at", ts)
                            gt("id", after.id)
                        }
                    }
                }
            }
            order("updated_at", Order.ASCENDING)
            order("id", Order.ASCENDING)
            limit(limit.toLong())
        }.decodeList<JsonObject>()
    }

    private suspend fun <T> call(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: RestException) {
        if (isPermanent(e.statusCode)) throw SyncRejectedException(e.error, e) else throw e
    }

    /** 4xx means the payload itself is wrong, except auth expiry, timeouts and rate limits. */
    private fun isPermanent(status: Int) = status in 400..499 && status !in setOf(401, 408, 429)
}
