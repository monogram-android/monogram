package org.monogram.data.mapper

import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.data.db.model.ChatEntity
import org.monogram.domain.models.ChatPermissionsModel

class ChatPermissionsMapperTest {

    @Test
    fun `entity round trip preserves canReactToMessages`() {
        val entity = ChatEntity(
            id = 1L,
            title = "Chat",
            unreadCount = 0,
            avatarPath = null,
            lastMessageText = "",
            lastMessageTime = "0",
            order = 0L,
            isPinned = false,
            isMuted = false,
            isChannel = false,
            isGroup = true,
            type = "BASIC_GROUP",
            isArchived = false,
            memberCount = 0,
            onlineCount = 0,
            permissionCanReactToMessages = true
        )

        assertTrue(entity.toDomainChatPermissionsModel().canReactToMessages)
        assertTrue(
            entity.toDomainChatPermissionsModel()
                .toEntityPermissionValues()
                .canReactToMessages
        )
    }

    @Test
    fun `null td permissions falls back to default react permission`() {
        assertFalse((null as TdApi.ChatPermissions?).toDomainChatPermissions().canReactToMessages)
    }

    @Test
    fun `withPermissions persists canReactToMessages`() {
        val entity = ChatEntity(
            id = 1L,
            title = "Chat",
            unreadCount = 0,
            avatarPath = null,
            lastMessageText = "",
            lastMessageTime = "0",
            order = 0L,
            isPinned = false,
            isMuted = false,
            isChannel = false,
            isGroup = true,
            type = "BASIC_GROUP",
            isArchived = false,
            memberCount = 0,
            onlineCount = 0
        )

        val updated = entity.withPermissions(ChatPermissionsModel(canReactToMessages = true))

        assertTrue(updated.permissionCanReactToMessages)
    }
}
