package com.kronokeep.app.data.push

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.firebase.messaging.FirebaseMessaging
import com.kronokeep.app.data.remote.CloudNotConfiguredException
import com.kronokeep.app.data.remote.SupabaseProvider
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/** The FCM token for this install, or null when push isn't configured or Play services can't provide one. */
interface PushTokenSource {
    suspend fun currentToken(): String?
}

/** `device_tokens` on the server, written only through RPCs (they reassign a token that changed hands). */
interface PushTokenRemote {
    suspend fun register(token: String)
    suspend fun unregister(token: String)
}

/** The token last registered, and for which account. Lets app start skip the RPC when nothing changed. */
data class PushRegistrationRecord(val token: String, val userId: String)

interface PushRegistrationStore {
    suspend fun load(): PushRegistrationRecord?
    suspend fun save(record: PushRegistrationRecord)
    suspend fun clear()
}

@Singleton
class FirebasePushTokenSource @Inject constructor(private val firebase: FirebaseSetup) : PushTokenSource {
    override suspend fun currentToken(): String? {
        if (!firebase.isInitialized) return null
        return suspendCancellableCoroutine { cont ->
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                cont.resume(if (task.isSuccessful) task.result?.takeIf { it.isNotBlank() } else null)
            }
        }
    }
}

class SupabasePushTokenRemote @Inject constructor(private val provider: SupabaseProvider) : PushTokenRemote {
    private fun db() = (provider.client ?: throw CloudNotConfiguredException()).postgrest

    override suspend fun register(token: String) {
        db().rpc("register_device_token", buildJsonObject { put("p_token", token) })
    }

    override suspend fun unregister(token: String) {
        db().rpc("unregister_device_token", buildJsonObject { put("p_token", token) })
    }
}

class DataStorePushRegistrationStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : PushRegistrationStore {
    override suspend fun load(): PushRegistrationRecord? {
        val prefs = dataStore.data.first()
        val token = prefs[TOKEN] ?: return null
        val user = prefs[USER] ?: return null
        return PushRegistrationRecord(token, user)
    }

    override suspend fun save(record: PushRegistrationRecord) {
        dataStore.edit {
            it[TOKEN] = record.token
            it[USER] = record.userId
        }
    }

    override suspend fun clear() {
        dataStore.edit {
            it.remove(TOKEN)
            it.remove(USER)
        }
    }

    private companion object {
        val TOKEN = stringPreferencesKey("push_registered_token")
        val USER = stringPreferencesKey("push_registered_user")
    }
}
