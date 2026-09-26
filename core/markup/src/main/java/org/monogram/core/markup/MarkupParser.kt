package org.monogram.core.markup

import org.monogram.core.models.StyledText
import org.monogram.core.models.RichBlock
import org.monogram.core.models.TaskItem
import org.monogram.core.models.splitRichText
import org.monogram.core.models.TextEntity
import org.monogram.core.models.parseMarkdownToStyled
import org.monogram.markup.MarkupNative
import org.monogram.markup.NativeHighlightSpan
import org.monogram.markup.NativeMarkupEntity
import org.monogram.markup.NativeMathSpan
import org.monogram.markup.NativeStyledMarkup
import org.monogram.markup.NativeMarkupBlock

data class CodeHighlight(
    val start: Int,
    val end: Int,
    val scope: String,
)

data class MathSpan(
    val start: Int,
    val end: Int,
    val display: Boolean,
    val source: String,
)

/** Parses message markup and exposes UTF-16 ranges for Android text rendering. */
interface MarkupParser {
    fun parseTelegramMarkdown(raw: String): StyledText
    fun highlightCode(code: String, language: String): List<CodeHighlight>
    fun extractMath(raw: String): List<MathSpan>
    fun supportedHighlightLanguages(): List<String>
    fun renderBlocks(text: String, entities: List<TextEntity>, parseMarkdown: Boolean): List<RichBlock>
}

class NativeMarkupParser : MarkupParser {
    override fun renderBlocks(text: String, entities: List<TextEntity>, parseMarkdown: Boolean): List<RichBlock> =
        MarkupNative.renderBlocks(
            text,
            entities.map { NativeMarkupEntity(it.kind, it.offset, it.length, it.url) },
            parseMarkdown,
        ).map { it.toRichBlock() }

    override fun parseTelegramMarkdown(raw: String): StyledText =
        MarkupNative.parseTelegramMarkdown(raw).toStyledText()

    override fun highlightCode(code: String, language: String): List<CodeHighlight> =
        MarkupNative.highlightCode(code, language).map { it.toCodeHighlight() }

    override fun extractMath(raw: String): List<MathSpan> =
        MarkupNative.extractMath(raw).map { it.toMathSpan() }

    override fun supportedHighlightLanguages(): List<String> =
        MarkupNative.supportedHighlightLanguages()
}

/** Portable parser for previews and callers without a native runtime. */
class KotlinMarkupParser : MarkupParser {
    override fun renderBlocks(text: String, entities: List<TextEntity>, parseMarkdown: Boolean): List<RichBlock> =
        if (parseMarkdown && entities.isEmpty()) splitRichText(text)
        else if (text.isEmpty()) emptyList() else listOf(RichBlock.Paragraph(text, entities))
    override fun parseTelegramMarkdown(raw: String): StyledText = parseMarkdownToStyled(raw)
    override fun highlightCode(code: String, language: String): List<CodeHighlight> = emptyList()
    override fun extractMath(raw: String): List<MathSpan> = extractMathSpans(raw)
    override fun supportedHighlightLanguages(): List<String> = emptyList()
}

internal fun extractMathSpans(raw: String): List<MathSpan> {
    val spans = mutableListOf<MathSpan>()
    var i = 0
    var inFence = false
    var inInlineCode = false
    while (i < raw.length) {
        if (!inInlineCode && raw.startsWith("```", i)) {
            inFence = !inFence
            i += 3
            continue
        }
        if (inFence) {
            i++
            continue
        }
        if (raw[i] == '`') {
            inInlineCode = !inInlineCode
            i++
            continue
        }
        if (inInlineCode) {
            i++
            continue
        }
        if (raw.startsWith("$$", i)) {
            val end = raw.indexOf("$$", i + 2)
            if (end > i + 2) {
                val inner = raw.substring(i + 2, end)
                if (inner.isNotBlank()) {
                    spans += MathSpan(i, end + 2, display = true, source = inner)
                    i = end + 2
                    continue
                }
            }
        }
        if (raw[i] == '$') {
            val end = raw.indexOf('$', i + 1)
            if (end > i + 1) {
                val inner = raw.substring(i + 1, end)
                if (inner.isNotEmpty() && '\n' !in inner) {
                    spans += MathSpan(i, end + 1, display = false, source = inner)
                    i = end + 1
                    continue
                }
            }
        }
        i++
    }
    return spans
}

/** Kinds Telegram accepts on send. Heading/table/details stay client-view. */
val SENDABLE_ENTITY_KINDS = setOf(
    "bold",
    "italic",
    "underline",
    "strike",
    "code",
    "pre",
    "spoiler",
    "blockquote",
    "text_url",
    "custom_emoji",
    "mention_name",
)

fun StyledText.forSend(
    isPremium: Boolean = false,
    maxCustomEmoji: Int? = null,
    freeCustomEmojiUrls: Set<String> = emptySet(),
): StyledText {
    var customEmoji = 0
    return copy(
        entities = entities.mapNotNull { entity ->
            if (entity.kind !in SENDABLE_ENTITY_KINDS || entity.length <= 0 ||
                entity.offset < 0 || entity.offset > text.length ||
                entity.length > text.length - entity.offset
            ) return@mapNotNull null
            val end = entity.offset + entity.length
            if ((entity.offset > 0 && text[entity.offset].isLowSurrogate() &&
                    text[entity.offset - 1].isHighSurrogate()) ||
                (end < text.length && text[end].isLowSurrogate() && text[end - 1].isHighSurrogate())
            ) return@mapNotNull null
            if (entity.kind == "custom_emoji") {
                if (entity.url?.toLongOrNull()?.let { it != 0L } != true) return@mapNotNull null
                val free = entity.url != null && entity.url in freeCustomEmojiUrls
                if (!isPremium && !free) return@mapNotNull null
                if (maxCustomEmoji != null) {
                    if (customEmoji >= maxCustomEmoji) return@mapNotNull null
                    customEmoji += 1
                }
            }
            entity
        },
    )
}

class FakeMarkupParser(
    private val parse: (String) -> StyledText = { StyledText(it) },
    private val highlight: (String, String) -> List<CodeHighlight> = { _, _ -> emptyList() },
    private val math: (String) -> List<MathSpan> = { emptyList() },
    private val languages: List<String> = emptyList(),
) : MarkupParser {
    override fun renderBlocks(text: String, entities: List<TextEntity>, parseMarkdown: Boolean): List<RichBlock> {
        val styled = if (parseMarkdown && entities.isEmpty()) parse(text) else StyledText(text, entities)
        return listOf(RichBlock.Paragraph(styled.text, styled.entities))
    }
    override fun parseTelegramMarkdown(raw: String): StyledText = parse(raw)
    override fun highlightCode(code: String, language: String): List<CodeHighlight> =
        highlight(code, language)
    override fun extractMath(raw: String): List<MathSpan> = math(raw)
    override fun supportedHighlightLanguages(): List<String> = languages
}

fun NativeMarkupBlock.toRichBlock(): RichBlock = when (kind) {
    "code" -> RichBlock.Code(text, language)
    "quote" -> RichBlock.Quote(
        text = text,
        entities = entities.map { it.toTextEntity() },
        collapsed = language == "collapsed",
        level = level.coerceAtLeast(1),
    )
    "photo" -> {
        val parts = (language ?: "").split(':')
        val id = parts.getOrNull(1).orEmpty()
        val dims = parts.getOrNull(2).orEmpty().split('x')
        RichBlock.Photo(
            cacheKey = if (id.isNotEmpty()) "photo:$id" else language.orEmpty(),
            width = dims.getOrNull(0)?.toIntOrNull() ?: 0,
            height = dims.getOrNull(1)?.toIntOrNull() ?: 0,
        )
    }
    "tasks" -> RichBlock.TaskList(
        items = rows.mapNotNull { row ->
            val done = row.getOrNull(0) == "1"
            val item = row.getOrNull(1) ?: return@mapNotNull null
            TaskItem(item, done)
        },
    )
    "heading" -> RichBlock.Heading(text, level.coerceIn(1, 6), entities.map { it.toTextEntity() })
    "table" -> RichBlock.Table(headers, rows)
    "details" -> {
        val split = text.indexOf('\n')
        if (split < 0) RichBlock.Details(text, emptyList())
        else RichBlock.Details(text.substring(0, split), listOf(RichBlock.Paragraph(text.substring(split + 1))))
    }
    "rule" -> RichBlock.Rule
    else -> RichBlock.Paragraph(text, entities.map { it.toTextEntity() })
}

fun NativeStyledMarkup.toStyledText(): StyledText = StyledText(
    text = text,
    entities = entities.map { it.toTextEntity() },
)

fun NativeMarkupEntity.toTextEntity(): TextEntity = TextEntity(
    kind = kind,
    offset = offset,
    length = length,
    url = extra,
)

fun NativeHighlightSpan.toCodeHighlight(): CodeHighlight = CodeHighlight(
    start = start,
    end = end,
    scope = scope,
)

fun NativeMathSpan.toMathSpan(): MathSpan = MathSpan(
    start = start,
    end = end,
    display = display,
    source = source,
)
