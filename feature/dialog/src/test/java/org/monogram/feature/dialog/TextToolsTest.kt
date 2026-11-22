package org.monogram.feature.dialog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.core.models.AiTextTone
import org.monogram.core.models.Message
import org.monogram.core.models.MessageId
import org.monogram.core.models.PeerId
import org.monogram.core.models.Poll
import org.monogram.core.models.PollAnswer
import org.monogram.core.models.TextEntity
import org.monogram.core.models.TextToolText
import org.monogram.feature.dialog.store.isExcludedLanguage

class TextToolsEligibilityTest {
    private fun message(
        text: String? = "hello",
        mediaKind: String? = null,
        outgoing: Boolean = false,
        pending: Boolean = false,
        noforwards: Boolean = false,
        summaryFromLanguage: String? = null,
        fileName: String? = null,
    ) = Message(
        MessageId(PeerId(7), 11),
        PeerId(9),
        text,
        100,
        outgoing = outgoing,
        mediaKind = mediaKind,
        pending = pending,
        noforwards = noforwards,
        summaryFromLanguage = summaryFromLanguage,
        fileName = fileName,
    )

    @Test
    fun incomingTextAndSupportedMediaAreTranslatable() {
        assertTrue(message().canUseTextTools())
        assertTrue(message(mediaKind = "photo").canUseTextTools())
        assertTrue(message(mediaKind = "voice").canUseTextTools())
    }

    @Test
    fun outgoingPendingRestrictedAndBlankMessagesAreNotTranslatable() {
        assertFalse(message(outgoing = true).canUseTextTools())
        assertFalse(message(pending = true).canUseTextTools())
        assertFalse(message(noforwards = true).canUseTextTools())
        assertFalse(message(text = "   ").canUseTextTools())
        assertFalse(message(mediaKind = "sticker").canUseTextTools())
        assertFalse(message().copy(id = MessageId(PeerId(7), 0)).canUseTextTools())
    }

    @Test
    fun summaryNeedsLongIncomingTextWithDetectedSource() {
        val long = "a".repeat(101)
        assertFalse(message(text = long).canUseTextTools(summary = true))
        assertFalse(
            message(text = long, summaryFromLanguage = "en").canUseTextTools(summary = true).not()
        )
        assertFalse(
            message(text = "a".repeat(100), summaryFromLanguage = "en").canUseTextTools(
                summary = true
            )
        )
        assertFalse(
            message(
                text = long,
                summaryFromLanguage = "en",
                outgoing = true
            ).canUseTextTools(summary = true)
        )
        assertTrue(
            message(
                text = long,
                mediaKind = "photo",
                summaryFromLanguage = "en"
            ).canUseTextTools(summary = true)
        )
    }

    @Test
    fun pollIsTranslatableWithoutMessageTextButNeverSummarized() {
        val poll = message(text = null, mediaKind = "poll", fileName = pollPayload()).apply { }
        assertNull(poll.text)
        assertTrue(poll.canUseTextTools())
        assertFalse(poll.canUseTextTools(summary = true))
    }

    private fun pollPayload() =
        """{"q":"Question","a":[{"t":"First"},{"t":"Second"}],"n":3,"z":false,"m":false,"x":false,"o":false}"""
}

class TextToolsBatchTest {
    private fun message(
        id: Int,
        text: String,
    ) = Message(
        MessageId(PeerId(7), id),
        PeerId(9),
        text,
        100,
        outgoing = false,
    )

    @Test
    fun batchesCapAtTwentyMessages() {
        val messages = (1..45).map { message(it, "word") }
        val batches = translationBatches(messages)
        assertEquals(listOf(20, 20, 5), batches.map { it.size })
        assertEquals(45, batches.sumOf { it.size })
    }

    @Test
    fun batchesRespectUtf16BudgetAndSkipOversize() {
        val half = "x".repeat(12_500)
        val batches =
            translationBatches(listOf(message(1, half), message(2, half), message(3, half)))
        assertEquals(listOf(2, 1), batches.map { it.size })
        assertTrue(translationBatches(listOf(message(4, "x".repeat(25_001)))).isEmpty())
        assertTrue(translationBatches(listOf(message(5, ""))).isEmpty())
    }

    @Test
    fun duplicateIdsCollapseIntoOneBatch() {
        val batches = translationBatches(listOf(message(1, "a"), message(1, "a")))
        assertEquals(1, batches.single().size)
    }
}

class TextToolsSelectionTest {
    private val original =
        TextToolText(
            "Hello bold world",
            listOf(
                TextEntity("bold", 6, 4),
                TextEntity("mention_name", 11, 5, "42"),
            ),
        )

    @Test
    fun sliceClipsEntitiesToTheSelection() {
        val sliced = original.slice(6, 10)
        assertEquals("bold", sliced.text)
        assertEquals(listOf(TextEntity("bold", 0, 4)), sliced.entities)
    }

    @Test
    fun replaceKeepsTrailingEntityOffsets() {
        val replacement = TextToolText("loud", listOf(TextEntity("bold", 0, 4)))
        val applied = replaceTextToolSelection(original, 6, 10, replacement)
        assertEquals("Hello loud world", applied.text)
        assertEquals(
            listOf(TextEntity("bold", 6, 4), TextEntity("mention_name", 11, 5, "42")),
            applied.entities
        )
    }

    @Test
    fun replaceOfWholeDraftDropsOriginalEntities() {
        val applied = replaceTextToolSelection(
            original,
            0,
            original.text.length,
            TextToolText("Fresh", emptyList())
        )
        assertEquals("Fresh", applied.text)
        assertTrue(applied.entities.isEmpty())
    }

    @Test
    fun replaceKeepsEntitiesBeforeTheSelectionUntouched() {
        val applied = replaceTextToolSelection(original, 11, 16, TextToolText("there", emptyList()))
        assertEquals("Hello bold there", applied.text)
        assertEquals(listOf(TextEntity("bold", 6, 4)), applied.entities)
    }

    @Test
    fun replaceShiftedSelectionKeepsSurroundingEntities() {
        val text = TextToolText(
            "aa bold cc tail",
            listOf(TextEntity("bold", 3, 4), TextEntity("italic", 11, 4))
        )
        val applied =
            replaceTextToolSelection(text, 3, 7, TextToolText("very bold indeed", emptyList()))
        assertEquals("aa very bold indeed cc tail", applied.text)
        assertEquals(listOf(TextEntity("italic", 23, 4)), applied.entities)
    }

    @Test
    fun composerGoesStaleWhenDraftOrEditingTargetChanges() {
        val composer = AiComposerState(
            original,
            editingId = null,
            start = 0,
            end = original.text.length,
            markdown = false
        )
        assertTrue(composer.matches(original, null))
        assertFalse(composer.matches(original.copy(text = "Hello bold world!"), null))
        assertFalse(composer.matches(original, 55))
        assertEquals(original, composer.input)
        assertEquals("bold", composer.copy(start = 6, end = 10).input.text)
    }
}

class TextToolsExclusionTest {
    @Test
    fun confidentDetectionMatchingAnExcludedLanguageIsSkipped() {
        assertTrue(isExcludedLanguage("ru", "ru-RU", 0.95f, setOf("ru")))
        assertTrue(isExcludedLanguage("pt", "pt-BR", 0.9f, setOf("pt-BR")))
    }

    @Test
    fun lowConfidenceUnknownOrUnlistedLanguagesAreTranslated() {
        assertFalse(isExcludedLanguage("ru", "ru-RU", 0.5f, setOf("ru")))
        assertFalse(isExcludedLanguage("en", "en-US", 0.99f, setOf("ru")))
        assertFalse(isExcludedLanguage("ru", "ru-RU", 0.99f, emptySet()))
        assertFalse(isExcludedLanguage(null, null, 0.99f, setOf("ru")))
    }
}

class TextToolsPollTest {
    private val poll =
        Poll(
            question = "Pick one",
            answers = listOf(PollAnswer("First"), PollAnswer("Second")),
            totalVoters = 2,
            solution = "Because",
        )

    @Test
    fun pollTextsFollowQuestionAnswersSolutionOrder() {
        assertEquals(
            listOf("Pick one", "First", "Second", "Because"),
            pollTexts(poll).map { it.text })
        assertEquals(
            listOf("Pick one", "First", "Second"),
            pollTexts(poll.copy(solution = "")).map { it.text })
    }

    @Test
    fun pollTranslationReassemblesMatchingPayload() {
        val translated = listOf("Q", "A1", "A2", "S").map { TextToolText(it, emptyList()) }
        val result = pollTranslation(poll, translated)!!
        assertEquals("Q", result.question.text)
        assertEquals(listOf("A1", "A2"), result.answers.map { it.text })
        assertEquals("S", result.solution?.text)
        assertNull(pollTranslation(poll, translated.take(3)))
    }

    @Test
    fun builtInTonesKeepServerKeysVerbatim() {
        val tone = AiTextTone("default:formal", "Formal", emojiId = 0, creator = false)
        assertTrue(tone.key.startsWith("default:"))
        assertFalse(tone.creator)
    }
}
