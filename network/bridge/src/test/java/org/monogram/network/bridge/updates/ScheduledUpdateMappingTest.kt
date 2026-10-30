package org.monogram.network.bridge.updates

import org.junit.Assert.*
import org.junit.Test
import org.monogram.core.models.PeerId
import org.monogram.network.bridge.MtprotoUpdate
import uniffi.monogram_mtproto.UpdateEventDto

class ScheduledUpdateMappingTest {
    @Test
    fun pollInvalidationPreservesFullWidthIdentity() {
        val updates = UpdateEventDto.PollChanged(Long.MAX_VALUE).toMtprotoUpdates()
        assertEquals(listOf(MtprotoUpdate.PollChanged(Long.MAX_VALUE)), updates)
    }

    @Test
    fun queueDeletionsPreserveDeliveryMetadataWithoutProducingHistoryDeletions() {
        for (delivered in listOf(null, emptyList(), listOf(500))) {
            val updates =
                UpdateEventDto.ScheduledMessagesDeleted(7, listOf(37), delivered).toMtprotoUpdates()
            assertEquals(1, updates.size)
            val deletion = updates.single() as MtprotoUpdate.ScheduledMessagesDeleted
            assertEquals(PeerId(7), deletion.chatId)
            assertEquals(listOf(37), deletion.messageIds)
            assertEquals(delivered, deletion.sentMessageIds)
            assertFalse(updates.any { it is MtprotoUpdate.MessagesDeleted || it is MtprotoUpdate.NewMessage })
        }
    }
}
