package org.monogram.presentation.features.profile.admin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.domain.models.ChatPermissionsModel

class ChatEditPermissionMutationTest {

    @Test
    fun `send messages toggle flips basic message permission`() {
        val initial = ChatPermissionsModel(canSendBasicMessages = true)

        val updated = initial.toggle(ChatEditComponent.Permission.SEND_MESSAGES)

        assertFalse(updated.canSendBasicMessages)
    }

    @Test
    fun `manage topics toggle flips create topics permission`() {
        val initial = ChatPermissionsModel(canCreateTopics = false)

        val updated = initial.toggle(ChatEditComponent.Permission.MANAGE_TOPICS)

        assertTrue(updated.canCreateTopics)
    }
}
