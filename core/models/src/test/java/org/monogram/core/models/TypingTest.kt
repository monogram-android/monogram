package org.monogram.core.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TypingTest {
    @Test
    fun emptyIsGeneric() {
        assertEquals(TypingLabel.Generic, typingLabel(emptyList()))
        assertEquals(TypingLabel.Generic, typingLabel(listOf(" ", "")))
    }

    @Test
    fun oneAndTwoNames() {
        assertEquals(TypingLabel.One("Ada"), typingLabel(listOf("Ada")))
        assertEquals(TypingLabel.Two("Ada", "Bob"), typingLabel(listOf("Ada", "Bob")))
    }

    @Test
    fun moreThanTwoIsCount() {
        assertEquals(TypingLabel.Many(3), typingLabel(listOf("Ada", "Bob", "Cyd")))
    }

    @Test
    fun unknownActionFallsBackToTyping() {
        assertEquals(ChatActionKind.Typing, ChatActionKind.fromWire(null))
        assertEquals(ChatActionKind.Typing, ChatActionKind.fromWire(""))
        assertEquals(ChatActionKind.RecordAudio, ChatActionKind.fromWire("record_audio"))
    }

    @Test
    fun privateChatHidesNames() {
        val shown = displayedChatAction(
            listOf(TypingPresence("Ada", "record_audio")),
            named = false,
        )
        assertEquals(DisplayedChatAction(emptyList(), "record_audio"), shown)
    }

    @Test
    fun groupShowsLatestActionNames() {
        val shown = displayedChatAction(
            listOf(
                TypingPresence("Ada", "typing"),
                TypingPresence("Bob", "record_audio"),
                TypingPresence("Cyd", "record_audio"),
            ),
            named = true,
        )
        assertEquals(DisplayedChatAction(listOf("Bob", "Cyd"), "record_audio"), shown)
    }

    @Test
    fun emptyUsersAreHidden() {
        assertNull(displayedChatAction(emptyList(), named = true))
    }

    @Test
    fun packAndUnpackNames() {
        assertEquals(null, packTypingNames(listOf(" ", "")))
        assertEquals("Ada\u001fBob", packTypingNames(listOf("Ada", "Bob")))
        assertEquals(listOf("Ada", "Bob"), unpackTypingNames("Ada\u001fBob"))
        assertEquals(emptyList<String>(), unpackTypingNames(null))
    }
}
