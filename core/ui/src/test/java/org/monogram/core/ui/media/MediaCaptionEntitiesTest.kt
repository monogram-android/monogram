package org.monogram.core.ui.media

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaCaptionEntitiesTest {
    @Test
    fun utf16RangesPreserveTextAndRejectMalformedEntities() {
        val text = "😀 caption"
        val annotated = captionText(
            text,
            listOf(
                CaptionEntity(0, 1, CaptionEntity.Type.BOLD),
                CaptionEntity(1, 2, CaptionEntity.Type.BOLD),
                CaptionEntity(-1, 2, CaptionEntity.Type.URL),
                CaptionEntity(3, Int.MAX_VALUE, CaptionEntity.Type.MENTION),
                CaptionEntity(3, 7, CaptionEntity.Type.BOLD),
            ),
            Color.Blue,
            MediaViewerActions(),
        )
        assertEquals(text, annotated.text)
        assertEquals(1, annotated.spanStyles.size)
        assertEquals(3, annotated.spanStyles.single().start)
        assertEquals(10, annotated.spanStyles.single().end)
        assertEquals(FontWeight.Bold, annotated.spanStyles.single().item.fontWeight)
        assertTrue(annotated.getLinkAnnotations(0, text.length).isEmpty())
    }

    @Test
    fun linksAndMentionsDispatchThroughTheirOwnCallbacks() {
        val urls = mutableListOf<String>()
        val mentions = mutableListOf<String>()
        val text = "site @alice Name"
        val annotated = captionText(
            text,
            listOf(
                CaptionEntity(0, 4, CaptionEntity.Type.URL, "https://example.org"),
                CaptionEntity(5, 6, CaptionEntity.Type.MENTION),
                CaptionEntity(12, 4, CaptionEntity.Type.MENTION, "tg://user?id=42"),
                CaptionEntity(0, 4, CaptionEntity.Type.BOLD),
            ),
            Color.Blue,
            MediaViewerActions(onCaptionUrl = urls::add, onCaptionMention = mentions::add),
        )
        annotated.getLinkAnnotations(0, text.length).forEach {
            val link = it.item as LinkAnnotation.Clickable
            link.linkInteractionListener?.onClick(link)
        }
        assertEquals(listOf("https://example.org"), urls)
        assertEquals(listOf("https://t.me/alice", "tg://user?id=42"), mentions)
        assertEquals(text, annotated.text)
    }

    @Test
    fun unusableLinkTargetsRemainPlainText() {
        val annotated = captionText(
            "plain text",
            listOf(
                CaptionEntity(0, 5, CaptionEntity.Type.URL, "bad target"),
                CaptionEntity(6, 4, CaptionEntity.Type.MENTION, "invalid"),
            ),
            Color.Blue,
            MediaViewerActions(),
        )
        assertEquals("plain text", annotated.text)
        assertTrue(annotated.getLinkAnnotations(0, annotated.length).isEmpty())
    }
}
