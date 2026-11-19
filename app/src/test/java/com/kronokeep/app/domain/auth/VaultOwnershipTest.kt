package com.kronokeep.app.domain.auth

import com.kronokeep.app.domain.repository.AuthState
import com.kronokeep.app.domain.repository.LocalDataEraser
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultOwnershipTest {
    /** A phone vault: rows stamped with their owner, plus the recorded owner. Erasing clears both. */
    private class FakeVault(var owner: String? = null, val rowOwners: MutableList<String?> = mutableListOf()) :
        LocalVault, LocalDataEraser {
        var erased = 0
        var failErase = false
        override suspend fun owner() = owner
        override suspend fun setOwner(userId: String) { owner = userId }
        override suspend fun clearOwner() { owner = null }
        override suspend fun holdsRowsOfAnotherAccount(userId: String) = rowOwners.any { it != null && it != userId }
        override suspend fun eraseAll() {
            erased++
            if (failErase) error("disk")
            rowOwners.clear()
            owner = null
        }
    }

    private val vault = FakeVault()
    private val ownership = VaultOwnership(vault, vault)

    @Test fun `the owner signing back in keeps the vault`() = runBlocking {
        vault.owner = "alice"
        vault.rowOwners += "alice"

        ownership.prepareFor("alice")

        assertEquals(0, vault.erased)
        assertEquals(listOf<String?>("alice"), vault.rowOwners)
    }

    @Test fun `another account signing in erases the previous one's vault`() = runBlocking {
        vault.owner = "alice"
        vault.rowOwners += listOf("alice", "alice")

        ownership.prepareFor("bob")

        assertEquals(1, vault.erased)
        assertTrue(vault.rowOwners.isEmpty())
        assertEquals("bob", vault.owner)
    }

    @Test fun `an install from before ownership erases rows stamped with another account`() = runBlocking {
        vault.rowOwners += listOf("alice", null)

        ownership.prepareFor("bob")

        assertEquals(1, vault.erased)
        assertEquals("bob", vault.owner)
    }

    @Test fun `an install from before ownership keeps the same account's rows and unclaimed ones`() = runBlocking {
        vault.rowOwners += listOf("alice", null)

        ownership.prepareFor("alice")

        assertEquals(0, vault.erased)
        assertEquals(listOf("alice", null), vault.rowOwners)
        assertEquals("alice", vault.owner)
    }

    @Test fun `a failed erase still records the new owner so sign-in isn't stuck`() = runBlocking {
        vault.owner = "alice"
        vault.failErase = true

        ownership.prepareFor("bob")

        assertEquals("bob", vault.owner)
    }

    @Test fun `release erases and forgets the owner`() = runBlocking {
        vault.owner = "alice"
        vault.rowOwners += "alice"

        ownership.release()

        assertEquals(1, vault.erased)
        assertNull(vault.owner)
        assertTrue(vault.rowOwners.isEmpty())
    }

    @Test fun `the gate shows a new account as loading until its vault is ready`() = runBlocking {
        vault.owner = "alice"
        vault.rowOwners += "alice"
        val bob = AuthState.SignedIn("bob", "bob@example.com")

        val states = flowOf(AuthState.Loading, AuthState.SignedOut, bob).gatedBy(ownership).toList()

        assertEquals(listOf(AuthState.Loading, AuthState.SignedOut, AuthState.Loading, bob), states)
        assertTrue(vault.rowOwners.isEmpty())
        assertEquals("bob", vault.owner)
    }

    @Test fun `the gate lets the owner straight through and ignores repeated sessions`() = runBlocking {
        vault.owner = "alice"
        val alice = AuthState.SignedIn("alice", null)

        val states = flowOf(alice, alice, alice).gatedBy(ownership).toList()

        assertEquals(listOf<AuthState>(alice), states)
        assertEquals(0, vault.erased)
    }

    @Test fun `switching accounts back and forth erases on every switch`() = runBlocking {
        val alice = AuthState.SignedIn("alice", null)
        val bob = AuthState.SignedIn("bob", null)

        flowOf(alice).gatedBy(ownership).toList()
        vault.rowOwners += "alice"
        flowOf(bob).gatedBy(ownership).toList()
        assertTrue(vault.rowOwners.isEmpty())
        vault.rowOwners += "bob"
        flowOf(alice).gatedBy(ownership).toList()

        assertEquals(2, vault.erased)
        assertTrue(vault.rowOwners.isEmpty())
        assertEquals("alice", vault.owner)
    }

    @Test fun `recovery and offline states pass through untouched`() = runBlocking {
        vault.owner = "alice"
        val states = listOf(AuthState.Unconfigured, AuthState.PasswordRecovery("bob@example.com"))

        assertEquals(states, states.asFlowList().gatedBy(ownership).toList())
        assertEquals(0, vault.erased)
    }

    private fun List<AuthState>.asFlowList() = flowOf(*toTypedArray())
}
