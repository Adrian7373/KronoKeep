package com.kronokeep.app.data.sync

import com.kronokeep.app.data.local.SyncTables
import com.kronokeep.app.data.local.TransactionRunner
import com.kronokeep.app.data.local.dao.OutboxDao
import com.kronokeep.app.data.local.entity.OutboxEntity
import com.kronokeep.app.data.local.record
import com.kronokeep.app.data.remote.DocumentStorage
import com.kronokeep.app.data.remote.PullCursor
import com.kronokeep.app.data.remote.SyncRejectedException
import com.kronokeep.app.data.remote.SyncRemote
import com.kronokeep.app.data.repository.ScheduleRecomputer
import com.kronokeep.app.data.repository.UploadScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import java.time.Clock
import javax.inject.Inject

data class SyncOutcome(
    val pushed: Int = 0,
    val pulled: Int = 0,
    /** Rows the server refused this run (they're retried up to [SyncEngine.MAX_ATTEMPTS] times). */
    val rejected: Int = 0,
    /** Local edits discarded because the server copy was written later. */
    val conflictsLost: Int = 0,
    /** A transient failure (offline, timeout, expired session) stopped the run; WorkManager should retry. */
    val shouldRetry: Boolean = false,
)

/**
 * Offline-first sync against Supabase. Room stays the source of truth; this only moves rows.
 *
 * 1. **Claim**: rows made before sign-in (`userId = null`) take the signed-in owner and get queued.
 * 2. **Push** the outbox, parents first. Before each batch it reads the server copies: last-write-wins on
 *    `updated_at`, so a server row written after the local edit (or a log locked by a report) beats it.
 *    The local edit time comes from the phone's clock, so a badly skewed clock makes conflicts approximate.
 *    The local row only turns SYNCED if its outbox entry is unchanged, so edits made mid-push aren't lost.
 * 3. **Pull** each table by keyset (`updated_at`, `id`) since the stored cursor. Pending local rows keep
 *    their edit unless the server copy is newer. Soft deletes arrive as ordinary rows with `deleted_at`.
 */
class SyncEngine @Inject constructor(
    private val registry: SyncTableRegistry,
    private val outboxDao: OutboxDao,
    private val tx: TransactionRunner,
    private val remote: SyncRemote,
    private val storage: DocumentStorage,
    private val store: SyncStateStore,
    private val recomputer: ScheduleRecomputer,
    private val uploads: UploadScheduler,
    private val clock: Clock,
) {
    private val tables get() = registry.ordered

    suspend fun sync(userId: String): SyncOutcome {
        if (claim(userId)) uploads.schedule()
        val stats = Stats()
        return try {
            push(userId, stats)
            pull(userId, stats)
            store.setLastSyncedAt(clock.instant())
            stats.outcome(retry = false)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            stats.outcome(retry = true)
        }
    }

    /** Returns true if any document was claimed, since the uploader only takes rows with an owner. */
    suspend fun claim(userId: String): Boolean = tx.inTransaction {
        val now = clock.instant()
        var documents = false
        for (table in tables) {
            for (id in table.unownedIds()) {
                if (!table.claim(id, userId)) continue
                outboxDao.record(table.name, id, now)
                if (table.name == SyncTables.DOCUMENTS) documents = true
            }
        }
        documents
    }

    // ---------------------------------------------------------------- push

    private suspend fun push(userId: String, stats: Stats) {
        val byTable = outboxDao.pending(MAX_ATTEMPTS).groupBy { it.tableName }
        for (table in tables) {
            byTable[table.name]?.chunked(PUSH_BATCH)?.forEach { pushBatch(table, it, userId, stats) }
        }
    }

    private suspend fun pushBatch(table: SyncTable<*, *>, entries: List<OutboxEntity>, userId: String, stats: Stats) {
        val candidates = mutableListOf<Pair<OutboxEntity, LocalRow>>()
        for (entry in entries) {
            val row = table.loadForPush(entry.rowId)
            when {
                row == null -> dropIfUnchanged(table, entry)
                // Unclaimed, or another account's rows left on this phone: not ours to push.
                row.userId != userId -> Unit
                row.readiness == PushReadiness.WAIT -> Unit
                row.readiness == PushReadiness.LOCAL_ONLY -> dropIfUnchanged(table, entry)
                else -> candidates += entry to row
            }
        }
        if (candidates.isEmpty()) return

        val serverCopies = remote.fetchByIds(table.name, candidates.map { it.second.id }).associateBy { it.rowId() }
        val toPush = candidates.filter { (entry, row) ->
            val server = serverCopies[row.id] ?: return@filter true
            val serverWins = table.remoteAlwaysWins(server) || remoteIsNewer(server.rowUpdatedAt(), row.updatedAt)
            if (serverWins) {
                applyIfUnchanged(table, entry, server, recompute = true)
                stats.conflictsLost++
            }
            !serverWins
        }
        if (toPush.isNotEmpty()) upsert(table, toPush, stats)
    }

    /** Sends a batch; if the server rejects it, retries row by row so one bad row can't block the rest. */
    private suspend fun upsert(table: SyncTable<*, *>, batch: List<Pair<OutboxEntity, LocalRow>>, stats: Stats) {
        val stored = try {
            remote.upsert(table.name, batch.map { it.second.json })
        } catch (e: SyncRejectedException) {
            if (batch.size > 1) {
                batch.forEach { upsert(table, listOf(it), stats) }
            } else {
                reject(table, batch.single().first, e)
                stats.rejected++
            }
            return
        }
        val byId = stored.associateBy { it.rowId() }
        for ((entry, row) in batch) {
            val server = byId[row.id] ?: continue
            applyIfUnchanged(table, entry, server, recompute = false)
            stats.pushed++
            // Best effort: a leftover object is only wasted space, and RLS still hides it from everyone else.
            row.orphanedObject?.let { path -> runCatching { storage.delete(path) } }
        }
    }

    /**
     * Takes the server's copy (canonical `updated_at`, `locked_at`) and clears the outbox entry, but only if
     * nobody edited the row since this run read it. A newer edit keeps its entry and is pushed next time.
     * [recompute] only when the server's version replaced ours; a pushed row was recomputed when it was written.
     */
    private suspend fun applyIfUnchanged(
        table: SyncTable<*, *>,
        entry: OutboxEntity,
        server: JsonObject,
        recompute: Boolean,
    ) = tx.inTransaction {
        if (outboxDao.entryFor(table.name, entry.rowId)?.seq != entry.seq) return@inTransaction
        val result = table.applyRemote(server)
        outboxDao.remove(entry.seq)
        if (recompute && result is ApplyResult.Applied) result.assetId?.let { recomputer.recompute(it, clock.instant()) }
    }

    private suspend fun dropIfUnchanged(table: SyncTable<*, *>, entry: OutboxEntity) = tx.inTransaction {
        if (outboxDao.entryFor(table.name, entry.rowId)?.seq == entry.seq) outboxDao.remove(entry.seq)
    }

    private suspend fun reject(table: SyncTable<*, *>, entry: OutboxEntity, e: SyncRejectedException) = tx.inTransaction {
        val current = outboxDao.entryFor(table.name, entry.rowId)?.takeIf { it.seq == entry.seq } ?: return@inTransaction
        outboxDao.markAttempt(current.seq, e.message?.take(500))
        if (current.attempts + 1 >= MAX_ATTEMPTS) table.markFailed(entry.rowId)
    }

    // ---------------------------------------------------------------- pull

    private suspend fun pull(userId: String, stats: Stats) {
        // A child can land between reading its table's parents and itself; another pass picks the parent up.
        repeat(MAX_PULL_PASSES) {
            if (!pullPass(userId, stats)) return
        }
    }

    /** Returns true if some table stopped at a row whose parent isn't local yet. */
    private suspend fun pullPass(userId: String, stats: Stats): Boolean {
        var blocked = false
        for (table in tables) {
            var cursor = store.cursor(userId, table.name)
            while (true) {
                val page = remote.pullPage(table.name, cursor, PULL_PAGE)
                if (page.isEmpty()) break
                val result = tx.inTransaction { applyPage(table, page) }
                stats.pulled += result.applied
                result.cursor?.let {
                    store.saveCursor(userId, table.name, it)
                    cursor = it
                }
                if (result.blocked) {
                    blocked = true
                    break
                }
                if (page.size < PULL_PAGE) break
            }
        }
        return blocked
    }

    private data class PageResult(val cursor: PullCursor?, val applied: Int, val blocked: Boolean)

    private suspend fun applyPage(table: SyncTable<*, *>, page: List<JsonObject>): PageResult {
        var cursor: PullCursor? = null
        var applied = 0
        var blocked = false
        val touchedAssets = linkedSetOf<String>()
        for (server in page) {
            val id = server.rowId()
            val serverUpdatedAt = server.rowUpdatedAt()
            val entry = outboxDao.entryFor(table.name, id)
            val keepLocal = entry != null && !table.remoteAlwaysWins(server) &&
                table.localUpdatedAt(id)?.let { !remoteIsNewer(serverUpdatedAt, it) } == true
            if (!keepLocal) {
                val result = table.applyRemote(server)
                if (result is ApplyResult.MissingParent) {
                    blocked = true
                    break
                }
                entry?.let { outboxDao.remove(it.seq) }
                (result as ApplyResult.Applied).assetId?.let(touchedAssets::add)
                applied++
            }
            cursor = PullCursor(serverUpdatedAt, id)
        }
        // Readings or schedules from another phone move due dates; recompute with the same rules as local edits.
        val now = clock.instant()
        touchedAssets.forEach { recomputer.recompute(it, now) }
        return PageResult(cursor, applied, blocked)
    }

    private class Stats {
        var pushed = 0
        var pulled = 0
        var rejected = 0
        var conflictsLost = 0
        fun outcome(retry: Boolean) = SyncOutcome(pushed, pulled, rejected, conflictsLost, retry)
    }

    companion object {
        /** A row the server rejects this many times is marked FAILED and waits for the next local edit. */
        const val MAX_ATTEMPTS = 5
        const val PUSH_BATCH = 100
        const val PULL_PAGE = 500
        private const val MAX_PULL_PASSES = 3
    }
}
