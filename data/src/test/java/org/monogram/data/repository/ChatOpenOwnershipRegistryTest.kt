package org.monogram.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatOpenOwnershipRegistryTest {
    private val registry = ChatOpenOwnershipRegistry()

    @Test
    fun `first owner opens and last owner closes`() {
        val acquired = registry.acquire(chatId = 10L, ownerTag = "cmp1")
        val released = registry.release(chatId = 10L, ownerTag = "cmp1")

        assertEquals(ChatOpenOwnershipTransition.AcquiredFirstOwner, acquired.transition)
        assertTrue(acquired.shouldOpen)
        assertFalse(acquired.shouldClose)

        assertEquals(ChatOpenOwnershipTransition.ReleasedLastOwner, released.transition)
        assertFalse(released.shouldOpen)
        assertTrue(released.shouldClose)
    }

    @Test
    fun `second owner keeps chat open until both close`() {
        val first = registry.acquire(chatId = 10L, ownerTag = "cmp1")
        val second = registry.acquire(chatId = 10L, ownerTag = "cmp2")
        val releaseFirst = registry.release(chatId = 10L, ownerTag = "cmp1")
        val releaseSecond = registry.release(chatId = 10L, ownerTag = "cmp2")

        assertEquals(ChatOpenOwnershipTransition.AcquiredFirstOwner, first.transition)
        assertEquals(ChatOpenOwnershipTransition.AddedOwner, second.transition)
        assertFalse(second.shouldOpen)
        assertEquals(setOf("cmp1", "cmp2"), second.owners)

        assertEquals(ChatOpenOwnershipTransition.ReleasedOwner, releaseFirst.transition)
        assertFalse(releaseFirst.shouldClose)
        assertEquals(setOf("cmp2"), releaseFirst.owners)

        assertEquals(ChatOpenOwnershipTransition.ReleasedLastOwner, releaseSecond.transition)
        assertTrue(releaseSecond.shouldClose)
    }

    @Test
    fun `duplicate owner and missing owner are ignored`() {
        registry.acquire(chatId = 10L, ownerTag = "cmp1")

        val duplicate = registry.acquire(chatId = 10L, ownerTag = "cmp1")
        val missing = registry.release(chatId = 10L, ownerTag = "cmp2")

        assertEquals(ChatOpenOwnershipTransition.DuplicateOwner, duplicate.transition)
        assertEquals(1, duplicate.ownerCount)
        assertFalse(duplicate.shouldOpen)

        assertEquals(ChatOpenOwnershipTransition.MissingOwner, missing.transition)
        assertEquals(setOf("cmp1"), missing.owners)
        assertFalse(missing.shouldClose)
    }
}
