package com.kronokeep.app.data.push

import com.kronokeep.app.domain.auth.SignOutUseCase
import com.kronokeep.app.domain.repository.AuthState
import com.kronokeep.app.testing.FakeAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class PushTokenRegistrarTest {
    private val source = FakeTokenSource()
    private val remote = FakeTokenRemote()
    private val store = MemoryRegistrationStore()
    private val auth = FakeAuth()
    private val registrar = PushTokenRegistrar(source, remote, store, auth, CoroutineScope(Dispatchers.Unconfined))

    @Test fun `registers a new token and remembers it`() = runBlocking {
        assertTrue(registrar.ensureRegistered("user-1"))
        assertEquals(listOf("tok-1"), remote.registered)
        assertEquals(PushRegistrationRecord("tok-1", "user-1"), store.record)
    }

    @Test fun `same token and account skips the call`() = runBlocking {
        registrar.ensureRegistered("user-1")
        assertTrue(registrar.ensureRegistered("user-1"))
        assertEquals(1, remote.registered.size)
    }

    @Test fun `a changed token or a different account registers again`() = runBlocking {
        registrar.ensureRegistered("user-1")
        source.token = "tok-2"
        registrar.ensureRegistered("user-1")
        registrar.ensureRegistered("user-2")
        assertEquals(listOf("tok-1", "tok-2", "tok-2"), remote.registered)
        assertEquals(PushRegistrationRecord("tok-2", "user-2"), store.record)
    }

    @Test fun `a failure leaves nothing recorded so the next attempt retries`() = runBlocking {
        remote.offline = true
        assertFalse(registrar.ensureRegistered("user-1"))
        assertNull(store.record)

        remote.offline = false
        assertTrue(registrar.ensureRegistered("user-1"))
        assertEquals(listOf("tok-1"), remote.registered)
    }

    @Test fun `no token (push not configured) is a quiet no-op`() = runBlocking {
        source.token = null
        assertFalse(registrar.ensureRegistered("user-1"))
        source.failing = true
        assertFalse(registrar.ensureRegistered("user-1"))
        assertTrue(remote.registered.isEmpty())
    }

    @Test fun `a rotated token registers for the signed-in account only`() = runBlocking {
        assertTrue(registrar.onNewToken("tok-9"))
        assertEquals(PushRegistrationRecord("tok-9", "user-1"), store.record)

        auth.state.value = AuthState.SignedOut
        assertFalse(registrar.onNewToken("tok-10"))
        assertEquals(listOf("tok-9"), remote.registered)
    }

    @Test fun `sign-in triggers registration`() {
        auth.state.value = AuthState.SignedOut
        registrar.start()
        assertTrue(remote.registered.isEmpty())
        auth.state.value = AuthState.SignedIn("user-1", null)
        assertEquals(listOf("tok-1"), remote.registered)
    }

    @Test fun `unregister drops the stored token and clears the record, even offline`() = runBlocking {
        registrar.ensureRegistered("user-1")
        registrar.unregister()
        assertEquals(listOf("tok-1"), remote.unregistered)
        assertNull(store.record)

        registrar.ensureRegistered("user-1")
        remote.offline = true
        registrar.unregister()
        assertNull(store.record)

        remote.offline = false
        registrar.ensureRegistered("user-1")
        assertEquals("re-registers after sign-out", 3, remote.registered.size)
    }

    @Test fun `sign out unregisters before the session goes`() = runBlocking {
        registrar.ensureRegistered("user-1")
        remote.onUnregister = { assertTrue(auth.state.value is AuthState.SignedIn) }
        SignOutUseCase(auth, registrar)()
        assertEquals(listOf("tok-1"), remote.unregistered)
        assertEquals(AuthState.SignedOut, auth.state.value)
    }

    private class FakeTokenSource(var token: String? = "tok-1") : PushTokenSource {
        var failing = false
        override suspend fun currentToken(): String? {
            if (failing) throw IllegalStateException("no Play services")
            return token
        }
    }

    private class FakeTokenRemote : PushTokenRemote {
        var offline = false
        val registered = mutableListOf<String>()
        val unregistered = mutableListOf<String>()
        var onUnregister: () -> Unit = {}

        override suspend fun register(token: String) {
            if (offline) throw IOException("offline")
            registered += token
        }

        override suspend fun unregister(token: String) {
            onUnregister()
            if (offline) throw IOException("offline")
            unregistered += token
        }
    }

    private class MemoryRegistrationStore : PushRegistrationStore {
        var record: PushRegistrationRecord? = null
        override suspend fun load() = record
        override suspend fun save(record: PushRegistrationRecord) {
            this.record = record
        }
        override suspend fun clear() {
            record = null
        }
    }
}
