package org.monogram.presentation.features.chats.conversation.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.monogram.domain.models.ChatViewportCacheEntry

class ChatViewportReturnStackTest {
    @Test
    fun `push appends distinct return target and trims to max size`() {
        val viewport = ChatViewportCacheEntry(returnToMessageIds = listOf(1L, 2L, 3L))

        val updated = pushViewportReturnTarget(
            viewport = viewport,
            returnTargetMessageId = 4L,
            maxSize = 3
        )

        assertEquals(listOf(2L, 3L, 4L), updated?.returnToMessageIds)
    }

    @Test
    fun `push does not duplicate existing return target`() {
        val viewport = ChatViewportCacheEntry(returnToMessageIds = listOf(1L, 2L, 3L))

        val updated = pushViewportReturnTarget(
            viewport = viewport,
            returnTargetMessageId = 2L,
            maxSize = 5
        )

        assertEquals(listOf(1L, 3L, 2L), updated?.returnToMessageIds)
    }

    @Test
    fun `pop returns last target and removes it from viewport`() {
        val viewport = ChatViewportCacheEntry(returnToMessageIds = listOf(10L, 20L, 30L))

        val result = popViewportReturnTarget(viewport)

        assertEquals(30L, result.targetMessageId)
        assertEquals(listOf(10L, 20L), result.viewport?.returnToMessageIds)
    }

    @Test
    fun `pop from empty stack keeps viewport and returns null target`() {
        val viewport = ChatViewportCacheEntry(returnToMessageIds = emptyList())

        val result = popViewportReturnTarget(viewport)

        assertNull(result.targetMessageId)
        assertEquals(emptyList<Long>(), result.viewport?.returnToMessageIds)
    }
}
