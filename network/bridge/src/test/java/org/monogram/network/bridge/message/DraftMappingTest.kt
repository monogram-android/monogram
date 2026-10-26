package org.monogram.network.bridge.message

import org.junit.Assert.assertEquals
import org.junit.Test
import org.monogram.core.models.CloudDraft
import org.monogram.core.models.PeerId
import org.monogram.network.bridge.MtprotoUpdate
import org.monogram.network.bridge.updates.toMtprotoUpdates
import uniffi.monogram_mtproto.UpdateEventDto

class DraftMappingTest {
    @Test
    fun draftAndDurableEventPreserveAllFields() {
        val draft = CloudDraft(
            chatId = PeerId(-1000000000007), threadId = 99, text = "fixture",
            entitiesJson = """[{"kind":"bold","offset":0,"length":7}]""",
            replyToMessageId = 123, date = 456, noWebpage = true,
            hasUnsupportedContent = true,
        )
        val native = draft.toNative()
        assertEquals(draft, native.toModel())
        assertEquals(
            listOf(MtprotoUpdate.DraftChanged(draft)),
            UpdateEventDto.DraftChanged(native).toMtprotoUpdates()
        )
    }
}
