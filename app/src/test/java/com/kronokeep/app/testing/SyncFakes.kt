package com.kronokeep.app.testing

import com.kronokeep.app.data.remote.PullCursor
import com.kronokeep.app.data.remote.SyncRejectedException
import com.kronokeep.app.data.remote.SyncRemote
import com.kronokeep.app.data.remote.parsePgInstant
import com.kronokeep.app.data.repository.SyncScheduler
import com.kronokeep.app.data.sync.SyncStateStore
import com.kronokeep.app.domain.repository.AuthRepository
import com.kronokeep.app.domain.repository.AuthState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Postgres-style timestamptz text: microseconds and a `+00:00` offset. */
private val PG_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSxxx").withZone(ZoneOffset.UTC)
fun pgTimestamp(at: Instant): String = PG_TIMESTAMP.format(at)

/**
 * In-memory PostgREST: per-table rows as JSON, a server clock that owns `updated_at` (with microseconds, like
 * Postgres), RLS on `user_id`, and switches for rejections and outages.
 */
class FakeSyncRemote(var callerUserId: String = "user-1") : SyncRemote {
    /**
     * Server time, starting just after the device's [NOW] (clocks agree, as on a healthy phone). Every write
     * stamps it and moves it on by 1.000123 s, so ordering and microsecond truncation are exercised.
     */
    var serverNow: Instant = NOW.plusNanos(456_000)
    val tables = mutableMapOf<String, MutableMap<String, JsonObject>>()
    val upsertedTables = mutableListOf<String>()
    val upsertBodies = mutableListOf<JsonObject>()
    var pullCalls = 0

    var offline = false
    val rejectIds = mutableSetOf<String>()
    /** Runs before an upsert is applied: lets a test edit Room "while the request is in flight". */
    var beforeUpsert: (suspend () -> Unit)? = null

    fun rows(table: String): Map<String, JsonObject> = tables[table].orEmpty()
    fun row(table: String, id: String): JsonObject = rows(table).getValue(id)

    /** A row written by another device (or the server itself) at [updatedAt]. */
    fun seed(table: String, row: JsonObject, updatedAt: Instant = tick()) {
        if (updatedAt >= serverNow) serverNow = updatedAt.plusSeconds(1)
        val stored = JsonObject(row + ("updated_at" to JsonPrimitive(pgTimestamp(updatedAt))))
        tables.getOrPut(table) { mutableMapOf() }[stored.id] = stored
    }

    private fun tick(): Instant = serverNow.also { serverNow = serverNow.plusSeconds(1).plusNanos(123_000) }

    private fun checkOnline() {
        if (offline) throw IOException("offline")
    }

    override suspend fun fetchByIds(table: String, ids: List<String>): List<JsonObject> {
        checkOnline()
        return ids.mapNotNull { rows(table)[it] }
    }

    override suspend fun upsert(table: String, rows: List<JsonObject>): List<JsonObject> {
        checkOnline()
        beforeUpsert?.let {
            beforeUpsert = null
            it()
        }
        rows.firstOrNull { it.id in rejectIds }?.let { throw SyncRejectedException("new row violates check constraint (${it.id})") }
        rows.firstOrNull { it["user_id"]?.jsonPrimitive?.content != callerUserId }
            ?.let { throw SyncRejectedException("new row violates row-level security policy") }
        upsertedTables += table
        upsertBodies += rows
        val target = tables.getOrPut(table) { mutableMapOf() }
        return rows.map { incoming ->
            // ON CONFLICT DO UPDATE only sets the sent columns; server-owned ones (locked_at) survive.
            val merged = JsonObject(target[incoming.id].orEmpty() + incoming + ("updated_at" to JsonPrimitive(pgTimestamp(tick()))))
            target[incoming.id] = merged
            merged
        }
    }

    override suspend fun pullPage(table: String, after: PullCursor?, limit: Int): List<JsonObject> {
        checkOnline()
        pullCalls++
        return rows(table).values
            .map { (parsePgInstant(it.getValue("updated_at").jsonPrimitive.content) to it.id) to it }
            .sortedWith(compareBy({ it.first.first }, { it.first.second }))
            .filter { (key, _) ->
                after == null || key.first > after.updatedAt || (key.first == after.updatedAt && key.second > after.id)
            }
            .take(limit)
            .map { it.second }
    }

    private val JsonObject.id: String get() = getValue("id").jsonPrimitive.content
}

class FakeSyncStateStore : SyncStateStore {
    val cursors = mutableMapOf<Pair<String, String>, PullCursor>()
    private val last = MutableStateFlow<Instant?>(null)

    override suspend fun cursor(userId: String, table: String): PullCursor? = cursors[userId to table]
    override suspend fun saveCursor(userId: String, table: String, cursor: PullCursor) {
        cursors[userId to table] = cursor
    }

    override val lastSyncedAt: Flow<Instant?> = last
    val lastSyncedAtValue: Instant? get() = last.value
    override suspend fun setLastSyncedAt(at: Instant) {
        last.value = at
    }
}

class FakeSyncScheduler : SyncScheduler {
    var scheduled = 0
    var manual = 0
    var periodic = 0
    override val running = MutableStateFlow(false)
    override fun schedule() {
        scheduled++
    }
    override fun syncNow() {
        manual++
    }
    override fun schedulePeriodic() {
        periodic++
    }
}

class FakeAuth(initial: AuthState = AuthState.SignedIn("user-1", "me@example.com")) : AuthRepository {
    override val state: MutableStateFlow<AuthState> = MutableStateFlow(initial)
    override suspend fun signIn(email: String, password: String) = Result.success(Unit)
    override suspend fun signUp(email: String, password: String) = Result.success(Unit)
    override suspend fun verifySignUpCode(email: String, code: String) = Result.success(Unit)
    override suspend fun resendSignUpCode(email: String) = Result.success(Unit)
    override suspend fun signOut() = Result.success(Unit).also { state.value = AuthState.SignedOut }
    override suspend fun requestPasswordReset(email: String) = Result.success(Unit)
    override suspend fun verifyRecoveryCode(email: String, code: String) = Result.success(Unit)
    override suspend fun setNewPassword(password: String) = Result.success(Unit)
    override suspend fun cancelPasswordRecovery() = signOut()

    var deleteResult: Result<Unit> = Result.success(Unit)
    val deleteCalls = mutableListOf<String>()
    var sessionForgotten = false

    override suspend fun deleteAccount(password: String): Result<Unit> {
        deleteCalls += password
        return deleteResult
    }

    override suspend fun forgetSession() {
        sessionForgotten = true
        state.value = AuthState.SignedOut
    }
}
