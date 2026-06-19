package org.monogram.presentation.features.chats.conversation.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.presentation.features.share.PendingAttachment
import org.monogram.presentation.features.share.PendingAttachmentKind

class PendingAttachmentSendPlanTest {
    @Test
    fun `single attachment uses single plan`() {
        val plan = resolvePendingAttachmentSendPlan(
            listOf(attachment("/tmp/photo.jpg", PendingAttachmentKind.PHOTO))
        )

        assertTrue(plan is PendingAttachmentSendPlan.Single)
    }

    @Test
    fun `multiple photo and video attachments use album plan in original order`() {
        val attachments = listOf(
            attachment("/tmp/1.jpg", PendingAttachmentKind.PHOTO),
            attachment("/tmp/2.mp4", PendingAttachmentKind.VIDEO),
            attachment("/tmp/3.jpg", PendingAttachmentKind.PHOTO)
        )

        val plan = resolvePendingAttachmentSendPlan(attachments)

        assertTrue(plan is PendingAttachmentSendPlan.Album)
        assertEquals(attachments, (plan as PendingAttachmentSendPlan.Album).attachments)
    }

    @Test
    fun `mixed documents and gifs use individual plan in original order`() {
        val attachments = listOf(
            attachment("/tmp/1.jpg", PendingAttachmentKind.PHOTO),
            attachment("/tmp/2.pdf", PendingAttachmentKind.DOCUMENT),
            attachment("/tmp/3.gif", PendingAttachmentKind.GIF)
        )

        val plan = resolvePendingAttachmentSendPlan(attachments)

        assertTrue(plan is PendingAttachmentSendPlan.Individual)
        assertEquals(attachments, (plan as PendingAttachmentSendPlan.Individual).attachments)
    }

    private fun attachment(path: String, kind: PendingAttachmentKind) =
        PendingAttachment(localPath = path, kind = kind)
}
