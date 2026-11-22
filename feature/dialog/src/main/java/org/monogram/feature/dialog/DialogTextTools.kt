package org.monogram.feature.dialog

import org.monogram.core.common.telegram.TelegramError
import org.monogram.core.models.AiTextResult
import org.monogram.core.models.AiTextTone
import org.monogram.core.models.Message
import org.monogram.core.models.Poll
import org.monogram.core.models.TextEntity
import org.monogram.core.models.TextToolText
import org.monogram.core.models.TextToolsAvailability
import org.monogram.core.models.poll
import java.util.Locale

sealed interface TextToolsAction {
    data object Initialize : TextToolsAction

    data class MessageTool(
        val messageId: Int,
        val summary: Boolean = false,
        val tone: String? = null,
        val manual: Boolean = false,
    ) : TextToolsAction

    data object CloseResult : TextToolsAction

    data object ProviderChanged : TextToolsAction

    data object Retry : TextToolsAction

    data object RetryChat : TextToolsAction

    data object RetryAvailability : TextToolsAction

    data object CancelRequest : TextToolsAction

    data class ComposerOptions(
        val tab: AiTextTab,
        val tone: String? = null,
        val emojify: Boolean = false,
    ) : TextToolsAction

    data class SummaryTone(
        val key: String?,
    ) : TextToolsAction

    data class Language(
        val code: String,
    ) : TextToolsAction

    data class ChatEnabled(
        val enabled: Boolean,
    ) : TextToolsAction

    data class PeerDisabled(
        val disabled: Boolean,
    ) : TextToolsAction

    data class Visible(
        val ids: Set<Int>,
    ) : TextToolsAction

    data class DismissTranslation(
        val id: Int,
    ) : TextToolsAction

    data class OpenComposer(
        val start: Int,
        val end: Int,
        val source: TextToolText? = null,
        val markdown: Boolean? = null,
    ) : TextToolsAction

    data object CloseComposer : TextToolsAction

    data class Compose(
        val tab: AiTextTab,
        val tone: String? = null,
        val emojify: Boolean = false,
    ) : TextToolsAction

    data object Apply : TextToolsAction

    data object Undo : TextToolsAction

    data object LoadTones : TextToolsAction

    data class ResolveTone(
        val key: String,
    ) : TextToolsAction

    data class ToneExample(
        val key: String,
    ) : TextToolsAction

    data class SaveTone(
        val key: String,
        val unsave: Boolean = false,
    ) : TextToolsAction

    data class DeleteTone(
        val key: String,
    ) : TextToolsAction

    data class WriteTone(
        val key: String?,
        val title: String,
        val prompt: String,
        val emojiId: Long,
        val displayAuthor: Boolean,
    ) : TextToolsAction
}

enum class AiTextTab { Translate, Style, Proofread }

data class TextToolMessageResult(
    val source: Message,
    val summary: Boolean,
    val tone: String? = null,
    val loading: Boolean = true,
    val result: TextToolText? = null,
    val poll: PollTranslation? = null,
    val error: TelegramError? = null,
)

/** Translated poll question/answers/solution; the original poll keeps rendering and voting as-is. */
data class PollTranslation(
    val question: TextToolText,
    val answers: List<TextToolText>,
    val solution: TextToolText? = null,
)

data class InlineTranslation(
    val source: Message,
    val result: TextToolText? = null,
    val poll: PollTranslation? = null,
    val summary: Boolean = false,
    val manual: Boolean = false,
    val loading: Boolean = false,
    val error: TelegramError? = null,
)

data class AiComposerState(
    val original: TextToolText,
    val editingId: Int?,
    val start: Int,
    val end: Int,
    val markdown: Boolean,
    val editorOwned: Boolean = false,
    val tab: AiTextTab = AiTextTab.Style,
    val tone: String? = null,
    val emojify: Boolean = false,
    val loading: Boolean = false,
    val result: AiTextResult? = null,
    val error: TelegramError? = null,
    val stale: Boolean = false,
) {
    val input: TextToolText get() = original.slice(start, end)

    /** False once the draft or the edited message changed under the open tools. */
    fun matches(
        current: TextToolText,
        currentEditingId: Int?,
        currentMarkdown: Boolean = markdown,
    ): Boolean = original == current && editingId == currentEditingId && markdown == currentMarkdown
}

data class AiComposerUndo(
    val original: AiComposerState,
    val applied: TextToolText,
)

data class DialogTextToolsState(
    val customProvider: Boolean = false,
    val providerConfigured: Boolean = true,
    val availability: TextToolsAvailability? = null,
    val availabilityLoading: Boolean = false,
    val availabilityError: TelegramError? = null,
    val manualEnabled: Boolean = true,
    val chatEnabledPreference: Boolean = true,
    val excludedLanguages: Set<String> = setOf(Locale.getDefault().language),
    val targetLanguage: String = Locale.getDefault().language,
    val chatEnabled: Boolean = false,
    val chatAllowed: Boolean = false,
    val chatLoading: Boolean = false,
    val chatError: TelegramError? = null,
    val peerSaving: Boolean = false,
    val translations: Map<Int, InlineTranslation> = emptyMap(),
    val messageResult: TextToolMessageResult? = null,
    val composer: AiComposerState? = null,
    val undo: AiComposerUndo? = null,
    val tones: List<AiTextTone> = emptyList(),
    val tonesLoading: Boolean = false,
    val tonesLoaded: Boolean = false,
    val toneBusy: Boolean = false,
    val toneError: TelegramError? = null,
    val resolvedTone: AiTextTone? = null,
    val toneExample: TextToolText? = null,
) {
    val canTranslateManually get() = providerConfigured && manualEnabled && availability?.manualTranslation == true
    val showChatTranslation
        get() = chatEnabledPreference && availability?.chatTranslation == true && availability.peerTranslationsDisabled.not()
}

internal fun DialogStore.State.canSummarizeMessage(message: Message): Boolean =
    !isBot && textTools.providerConfigured && message.canUseTextTools(summary = true)

internal fun Message.canUseTextTools(
    summary: Boolean = false,
    customProvider: Boolean = false,
): Boolean {
    if (id.id <= 0 || pending || noforwards) return false
    if (summary) {
        return !outgoing && poll == null && mediaKind in summaryMedia && text.orEmpty().length > 100
    }
    poll?.let { return it.question.isNotBlank() || it.answers.any { answer -> answer.text.isNotBlank() } }
    return mediaKind in translationMedia && !text.isNullOrBlank()
}

/** Question, answers and quiz solution in one order; the server translates them together. */
internal fun pollTexts(poll: Poll): List<TextToolText> = buildList {
    add(TextToolText(poll.question, emptyList()))
    poll.answers.forEach { add(TextToolText(it.text, emptyList())) }
    if (poll.solution.isNotBlank()) add(TextToolText(poll.solution, emptyList()))
}

internal fun Message.textToolsText(): String =
    poll?.let { poll -> pollTexts(poll).joinToString("\n") { it.text } } ?: text.orEmpty()

internal fun pollTranslation(
    poll: Poll,
    translated: List<TextToolText>,
): PollTranslation? {
    val expected = 1 + poll.answers.size + if (poll.solution.isNotBlank()) 1 else 0
    if (translated.size != expected || translated.take(expected).any { it.text.isBlank() }) {
        return null
    }
    var index = 0
    return PollTranslation(
        question = translated[index++],
        answers = poll.answers.map { translated[index++] },
        solution = if (poll.solution.isNotBlank()) translated[index] else null,
    )
}

private val summaryMedia =
    setOf(null, "text", "photo", "video", "document", "audio", "poll", "article")
private val translationMedia = summaryMedia + setOf("voice", "round", "round_video", "article")

internal fun translationBatches(messages: List<Message>): List<List<Message>> {
    val batches = mutableListOf<List<Message>>()
    var current = mutableListOf<Message>()
    var length = 0
    for (message in messages.distinctBy { it.id }) {
        val size = message.poll?.let { poll -> pollTexts(poll).sumOf { it.text.length } }
            ?: message.text?.length ?: 0
        if (size !in 1..25_000) continue
        if (current.size == 20 || length + size > 25_000) {
            batches += current
            current = mutableListOf()
            length = 0
        }
        current += message
        length += size
    }
    if (current.isNotEmpty()) batches += current
    return batches
}

internal fun TextToolText.slice(
    start: Int,
    end: Int,
): TextToolText {
    val lo = start.coerceIn(0, text.length)
    val hi = end.coerceIn(lo, text.length)
    return TextToolText(
        text.substring(lo, hi),
        entities.mapNotNull { entity ->
            val a = maxOf(lo, entity.offset)
            val b = minOf(hi, entity.offset + entity.length)
            if (a < b) entity.copy(offset = a - lo, length = b - a) else null
        },
    )
}

internal fun replaceTextToolSelection(
    original: TextToolText,
    start: Int,
    end: Int,
    replacement: TextToolText,
): TextToolText {
    val before = original.slice(0, start)
    val after = original.slice(end, original.text.length)
    val shift = before.text.length + replacement.text.length
    return TextToolText(
        before.text + replacement.text + after.text,
        (before.entities + replacement.entities.map { it.copy(offset = it.offset + before.text.length) } + after.entities.map {
            it.copy(offset = it.offset + shift)
        }).sortedBy(TextEntity::offset),
    )
}
