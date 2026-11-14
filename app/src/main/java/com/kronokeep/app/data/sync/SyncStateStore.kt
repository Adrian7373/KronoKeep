package com.kronokeep.app.data.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.kronokeep.app.data.remote.PullCursor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Pull cursors and the last good sync time. Cursors are per account, so switching accounts pulls from scratch. */
interface SyncStateStore {
    suspend fun cursor(userId: String, table: String): PullCursor?
    suspend fun saveCursor(userId: String, table: String, cursor: PullCursor)
    val lastSyncedAt: Flow<Instant?>
    suspend fun setLastSyncedAt(at: Instant)
}

@Singleton
class DataStoreSyncStateStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : SyncStateStore {

    // Instant.toString keeps microseconds, which the keyset filter needs to match the server value exactly.
    override suspend fun cursor(userId: String, table: String): PullCursor? {
        val raw = dataStore.data.first()[cursorKey(userId, table)] ?: return null
        val (at, id) = raw.split('|', limit = 2).takeIf { it.size == 2 } ?: return null
        return runCatching { PullCursor(Instant.parse(at), id) }.getOrNull()
    }

    override suspend fun saveCursor(userId: String, table: String, cursor: PullCursor) {
        dataStore.edit { it[cursorKey(userId, table)] = "${cursor.updatedAt}|${cursor.id}" }
    }

    override val lastSyncedAt: Flow<Instant?> =
        dataStore.data.map { prefs -> prefs[LAST_SYNCED]?.let(Instant::ofEpochMilli) }.distinctUntilChanged()

    override suspend fun setLastSyncedAt(at: Instant) {
        dataStore.edit { it[LAST_SYNCED] = at.toEpochMilli() }
    }

    private fun cursorKey(userId: String, table: String) = stringPreferencesKey("sync_cursor_${userId}_$table")

    private companion object {
        val LAST_SYNCED = longPreferencesKey("sync_last_synced_at")
    }
}
