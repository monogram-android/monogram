package org.monogram.feature.chats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.core.models.Chat
import org.monogram.core.models.PeerId

class ChatTypingMergeTest {
    @Test
    fun mergeKeepsLiveAction() {
        val current = listOf(
            Chat(
                id = PeerId(1),
                title = "Ada",
                typing = true,
                typingName = "Bob",
                typingAction = "record_audio",
            ),
        )
        val incoming = listOf(
            Chat(id = PeerId(1), title = "Ada", lastMessagePreview = "hi"),
        )
        val next = mergeChats(current, incoming)
        assertTrue(next.single().typing)
        assertEquals("record_audio", next.single().typingAction)
        assertEquals("Bob", next.single().typingName)
        assertEquals("hi", next.single().lastMessagePreview)
    }
}
