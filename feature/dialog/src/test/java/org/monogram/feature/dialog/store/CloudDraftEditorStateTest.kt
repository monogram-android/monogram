package org.monogram.feature.dialog.store

import org.junit.Assert.*
import org.junit.Test
import org.monogram.core.models.CloudDraft
import org.monogram.core.models.Message
import org.monogram.core.models.MessageId
import org.monogram.core.models.PeerId
import org.monogram.core.models.TextEntities
import org.monogram.core.models.TextEntity
import org.monogram.feature.dialog.DialogStore

class CloudDraftEditorStateTest {
    private val peer = PeerId(7)
    private fun restore(draft: CloudDraft, state: DialogStore.State = DialogStore.State(peer)) =
        with(DialogReducer) { state.reduce(Msg.CloudDraftRestored(draft)) }

    @Test
    fun namedMentionsAndCollapsedQuotesRoundTripWithoutDuplicateEntities() {
        val entities = listOf(
            TextEntity("mention_name", 0, 5, "42"),
            TextEntity("blockquote", 6, 4, "collapsed")
        )
        val draft = CloudDraft(
            peer,
            text = "Alice test",
            entitiesJson = TextEntities.serialize(entities),
            noWebpage = true
        )
        val state = restore(draft)
        assertEquals(42L, state.draftMentions.single().peerId.value)
        assertEquals(listOf(entities.last()), state.draftEntities)
        assertEquals(
            entities.toSet(),
            TextEntities.parse(state.cloudDraft(draft).entitiesJson).toSet()
        )
        assertTrue(state.linkPreviewHidden)
        assertEquals(draft, state.cloudDraft(draft))
    }

    @Test
    fun anUnloadedReplyRetainsItsTargetAndDoesNotRequestFocus() {
        val state = restore(CloudDraft(peer, text = "reply", replyToMessageId = 123))
        assertNull(state.replyTo)
        assertEquals(123, state.composerReplyId)
        assertEquals(123, state.cloudDraft(CloudDraft(peer)).replyToMessageId)
        assertEquals(0, state.composerFocusSeq)
        val cleared = with(DialogReducer) { state.reduce(Msg.ReplyTo(null)) }
        assertNull(cleared.composerReplyId)
    }

    @Test
    fun aLoadedReplySuppliesItsPreviewAndLegacyStateStillRetainsItsTarget() {
        val reply = Message(
            id = MessageId(peer, 123),
            senderId = null,
            text = "original",
            date = 1L,
            outgoing = false
        )
        val state = restore(
            CloudDraft(peer, replyToMessageId = 123),
            DialogStore.State(peer, messages = listOf(reply))
        )
        assertEquals(reply, state.replyTo)
        assertEquals(123, DialogStore.State(peer, replyTo = reply).composerReplyId)
    }

    @Test
    fun unsupportedDraftsKeepTheirGuardAndTopicWhenEditorFieldsChange() {
        val draft = CloudDraft(peer, threadId = 9, text = "original", hasUnsupportedContent = true)
        val state = restore(draft).copy(draft = "local edit")
        assertTrue(state.draftUnsupported)
        val payload = state.cloudDraft(draft)
        assertTrue(payload.hasUnsupportedContent)
        assertEquals(9, payload.threadId)
        assertEquals("local edit", payload.text)
    }
}
