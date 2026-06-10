package org.monogram.presentation.features.chats.list

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.monogram.domain.models.ChatModel

class FolderChatsNormalizationTest {
    @Test
    fun `normalizeFolderChats removes duplicates without reordering first items`() {
        val first = chat(1L, order = 30L)
        val second = chat(2L, order = 20L)
        val duplicateFirst = chat(1L, order = 10L)

        val result = normalizeFolderChats(listOf(first, second, duplicateFirst))

        assertEquals(listOf(first, second), result)
    }

    @Test
    fun `normalizeFolderChats returns same list instance when already unique`() {
        val source = listOf(chat(1L), chat(2L))

        val result = normalizeFolderChats(source)

        assertSame(source, result)
    }

    private fun chat(id: Long, order: Long = id): ChatModel =
        ChatModel(id = id, title = "chat $id", order = order, unreadCount = 0)
}
