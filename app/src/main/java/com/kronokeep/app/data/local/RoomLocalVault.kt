package com.kronokeep.app.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.kronokeep.app.data.local.dao.AssetDao
import com.kronokeep.app.domain.auth.LocalVault
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/** The owner is an account preference, so [eraseAccountPreferences] clears it along with the vault. */
class RoomLocalVault @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val assets: AssetDao,
) : LocalVault {
    override suspend fun owner(): String? = dataStore.data.first()[OWNER]

    override suspend fun setOwner(userId: String) {
        dataStore.edit { it[OWNER] = userId }
    }

    override suspend fun clearOwner() {
        dataStore.edit { it.remove(OWNER) }
    }

    override suspend fun holdsRowsOfAnotherAccount(userId: String): Boolean = assets.hasRowsOwnedByOtherThan(userId)

    private companion object {
        val OWNER = stringPreferencesKey("vault_owner_id")
    }
}
