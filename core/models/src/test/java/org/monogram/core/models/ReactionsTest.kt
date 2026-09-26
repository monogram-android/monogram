package org.monogram.core.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReactionsTest {
    @Test
    fun parsesEmojiAndCustom() {
        val rows = parseReactionsJson(
            """[{"e":"👍","c":2,"me":true},{"d":99,"c":1,"me":false}]""",
        )
        assertEquals(2, rows.size)
        assertEquals("👍", rows[0].emoticon)
        assertTrue(rows[0].chosen)
        assertEquals(99L, rows[1].documentId)
        assertEquals(1, rows[1].count)
    }

    @Test
    fun togglesChosenEmoji() {
        val added = toggleChosenReaction(null, "👍", 0L)
        assertEquals("👍", parseReactionsJson(added).single().emoticon)
        assertEquals(true, parseReactionsJson(added).single().chosen)
        val cleared = toggleChosenReaction(added, "👍", 0L)
        assertEquals(0, parseReactionsJson(cleared).size)
    }

    @Test
    fun pickerUsesRecentOnceWithoutHardcodedRow() {
        val recent = listOf(
            ReactionChoice(emoticon = "😂"),
            ReactionChoice(emoticon = "👍"),
            ReactionChoice(documentId = 7L),
            ReactionChoice(emoticon = "😂"),
        )
        val choices = reactionPickerChoices(recent)
        assertEquals(3, choices.size)
        assertEquals("😂", choices[0].emoticon)
        assertEquals("👍", choices[1].emoticon)
        assertEquals(7L, choices[2].documentId)
    }

    @Test
    fun pickerFallsBackToQuickWhenRecentEmpty() {
        val choices = reactionPickerChoices(emptyList())
        assertEquals(DEFAULT_QUICK_REACTIONS, choices.map { it.emoticon })
    }
}
