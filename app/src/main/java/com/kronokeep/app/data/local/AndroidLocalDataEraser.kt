package com.kronokeep.app.data.local

import android.content.Context
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.work.WorkManager
import com.kronokeep.app.data.settings.DataStoreSettingsRepository
import com.kronokeep.app.data.sync.DocumentUploadWorker
import com.kronokeep.app.data.sync.SyncWorker
import com.kronokeep.app.domain.repository.LocalDataEraser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Removes every preference except the device ones ([DataStoreSettingsRepository.DEVICE_PREFERENCE_NAMES]). */
suspend fun DataStore<Preferences>.eraseAccountPreferences() {
    edit { prefs ->
        prefs.asMap().keys
            .filter { it.name !in DataStoreSettingsRepository.DEVICE_PREFERENCE_NAMES }
            .forEach { prefs.remove(it) }
    }
}

@Singleton
class AndroidLocalDataEraser @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: KronoDatabase,
    private val dataStore: DataStore<Preferences>,
) : LocalDataEraser {

    override suspend fun eraseAll() = withContext(Dispatchers.IO) {
        // Stop workers first so a sync or upload can't run against half-erased data.
        step("work") {
            val wm = WorkManager.getInstance(context)
            listOf(SyncWorker.UNIQUE_NAME, SyncWorker.PERIODIC_NAME, DocumentUploadWorker.UNIQUE_NAME)
                .forEach { wm.cancelUniqueWork(it) }
        }
        step("database") { db.clearAllTables() }
        step("files") {
            listOf(File(context.filesDir, "documents"), File(context.cacheDir, "capture"), File(context.cacheDir, "viewer"))
                .forEach { it.deleteRecursively() }
        }
        step("preferences") { dataStore.eraseAccountPreferences() }
        step("notifications") { NotificationManagerCompat.from(context).cancelAll() }
    }

    private inline fun step(name: String, block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "erase step '$name' failed", e)
        }
    }

    private companion object {
        const val TAG = "KronoErase"
    }
}
