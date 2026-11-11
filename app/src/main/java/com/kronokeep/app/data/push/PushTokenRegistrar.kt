package com.kronokeep.app.data.push

import com.kronokeep.app.di.ApplicationScope
import com.kronokeep.app.domain.repository.AuthRepository
import com.kronokeep.app.domain.repository.AuthState
import com.kronokeep.app.domain.repository.PushRegistration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

/**
 * Keeps the server's `device_tokens` row for this phone pointing at the signed-in account.
 *
 * Registers on every sign-in (which includes the session restore on app start), skipping the RPC when the
 * stored record already matches, and whenever FCM rotates the token. A failure leaves the record untouched, so
 * the next sign-in or app start retries. All calls are serialised and never throw.
 */
@Singleton
class PushTokenRegistrar @Inject constructor(
    private val source: PushTokenSource,
    private val remote: PushTokenRemote,
    private val store: PushRegistrationStore,
    private val auth: AuthRepository,
    @ApplicationScope private val scope: CoroutineScope,
) : PushRegistration {
    private val mutex = Mutex()

    /** Watches auth state; call once from Application.onCreate. */
    fun start() {
        scope.launch {
            auth.state
                .filterIsInstance<AuthState.SignedIn>()
                .map { it.userId }
                .distinctUntilChanged()
                .collect { ensureRegistered(it) }
        }
    }

    /** Returns true when the server is known to have [token] (or the current token) for [userId]. */
    suspend fun ensureRegistered(userId: String, token: String? = null): Boolean = mutex.withLock {
        val current = token ?: safely { source.currentToken() } ?: return@withLock false
        val record = PushRegistrationRecord(current, userId)
        if (store.load() == record) return@withLock true
        val ok = safely { remote.register(current); true } ?: false
        if (ok) store.save(record)
        ok
    }

    /** FCM rotated the token. Without a signed-in account there's nothing to register it for yet. */
    suspend fun onNewToken(token: String): Boolean {
        val user = auth.state.value as? AuthState.SignedIn ?: return false
        return ensureRegistered(user.userId, token)
    }

    fun onNewTokenInBackground(token: String) {
        scope.launch { onNewToken(token) }
    }

    override suspend fun unregister() {
        mutex.withLock {
            val token = store.load()?.token ?: safely { source.currentToken() }
            if (token != null) withTimeoutOrNull(UNREGISTER_TIMEOUT) { safely { remote.unregister(token) } }
            // Cleared even on failure: the next sign-in must register again (the RPC reassigns the token), and
            // pushes that still arrive while signed out are dropped on the phone.
            store.clear()
        }
    }

    private suspend fun <T> safely(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // Offline or server error: retried on the next sign-in or app start.
        null
    }

    private companion object {
        val UNREGISTER_TIMEOUT = 5.seconds
    }
}
