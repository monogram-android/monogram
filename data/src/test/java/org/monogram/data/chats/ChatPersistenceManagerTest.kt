package org.monogram.data.chats

import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ChatPersistenceManagerTest {

    @Test
    fun `resolvePersistPosition prefers active folder over main`() {
        val chat = TdApi.Chat().apply {
            id = 1L
            positions = arrayOf(
                TdApi.ChatPosition(TdApi.ChatListMain(), 10L, false, null),
                TdApi.ChatPosition(TdApi.ChatListFolder(42), 7L, true, null),
                TdApi.ChatPosition(TdApi.ChatListArchive(), 3L, false, null)
            )
        }

        val position = resolvePersistPosition(chat, TdApi.ChatListFolder(42), ChatListManager(ChatCache()) {})

        assertNotNull(position)
        val resolved = position!!
        assertEquals(42, (resolved.list as TdApi.ChatListFolder).chatFolderId)
        assertEquals(7L, resolved.order)
        assertEquals(true, resolved.isPinned)
    }
}
