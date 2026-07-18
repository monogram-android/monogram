package org.monogram.presentation.features.chats.conversation.ui.inputbar

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.monogram.domain.models.MessageEntityType
import org.monogram.domain.repository.RichTextParseMode

internal const val LATEX_TAG = "latex_expression"
internal const val EDITOR_HEADING_TAG = "editor_heading"
internal const val EDITOR_DIVIDER_TAG = "editor_divider"

internal enum class EditorParseMode {
    Plain,
    Markdown,
    Html;

    fun next(): EditorParseMode {
        return when (this) {
            Plain -> Markdown
            Markdown -> Html
            Html -> Plain
        }
    }
}

internal fun applyEditorFormatting(value: TextFieldValue, mode: EditorParseMode): TextFieldValue {
    val annotated = when (mode) {
        EditorParseMode.Plain -> value.annotatedString
        EditorParseMode.Markdown -> MarkdownRichTextParser(value.annotatedString).parse()
        EditorParseMode.Html -> HtmlRichTextParser(value.annotatedString).parse()
    }
    return value.copy(annotatedString = annotated, selection = TextRange(annotated.length))
}

internal fun applyMarkdownFormatting(value: TextFieldValue): TextFieldValue {
    return applyEditorFormatting(value, EditorParseMode.Markdown)
}

internal fun applyHtmlFormatting(value: TextFieldValue): TextFieldValue {
    return applyEditorFormatting(value, EditorParseMode.Html)
}

internal fun EditorParseMode.toRichTextParseMode(): RichTextParseMode? {
    return when (this) {
        EditorParseMode.Plain -> null
        EditorParseMode.Markdown -> RichTextParseMode.Markdown
        EditorParseMode.Html -> RichTextParseMode.Html
    }
}

internal fun normalizeEditorMarkupForSending(text: String, mode: EditorParseMode): String {
    return when (mode) {
        EditorParseMode.Plain -> text
        EditorParseMode.Markdown -> normalizeMarkdownForSending(text)
        EditorParseMode.Html -> normalizeHtmlForSending(text)
    }
}

internal class MarkdownRichTextParser(private val source: AnnotatedString) {
    private val input = source.text
    private val builder = ParsedAnnotatedStringBuilder(source)
    private val markerStarts = mutableMapOf<String, MutableList<Int>>()
    private var index = 0
    private var lineStart = true
    private var activeQuote: MarkdownQuote? = null
    private var quoteLineOpen = false
    private var activeHeading: MarkdownHeading? = null

    fun parse(): AnnotatedString {
        while (index < input.length) {
            if (tryAppendEscapedChar()) continue
            if (activeInlineCode() && tryToggleInlineMarker(codeOnly = true)) continue
            if (tryAppendFenceBlock()) continue
            if (lineStart && tryAppendQuotedTable()) continue
            if (lineStart && tryAppendQuoteLine()) continue
            if (lineStart && activeQuote != null && !quoteLineOpen) {
                closeActiveQuote()
                continue
            }
            if (lineStart && tryOpenHeading()) continue
            if (lineStart && tryAppendDivider()) continue
            if (lineStart && tryAppendTable()) continue
            if (lineStart && tryAppendListMarker()) continue
            if (tryAppendLink()) continue
            if (tryAppendLatexBlock()) continue
            if (tryAppendInlineLatex()) continue
            if (tryToggleInlineMarker()) continue

            appendInputChar(index)
            index++
        }

        closeActiveQuote()
        closeActiveHeading()
        return builder.build()
    }

    private fun tryAppendEscapedChar(): Boolean {
        if (input.getOrNull(index) != '\\' || index + 1 >= input.length) return false
        builder.appendChar(input[index + 1], index)
        lineStart = false
        index += 2
        return true
    }

    private fun activeInlineCode(): Boolean = markerStarts["`"].isNullOrEmpty().not()

    private fun tryAppendFenceBlock(): Boolean {
        if (!input.startsWith("```", index)) return false

        val infoLineBreak = input.indexOf('\n', startIndex = index + 3)
        if (infoLineBreak == -1) return false

        val closingFence = input.indexOf("```", startIndex = infoLineBreak + 1)
        if (closingFence == -1) return false

        val language = input.substring(index + 3, infoLineBreak).trim()
        val blockStart = builder.length
        var cursor = infoLineBreak + 1
        while (cursor < closingFence) {
            appendInputChar(cursor)
            cursor++
        }
        val blockEnd = builder.length
        if (blockStart < blockEnd) {
            builder.addRichEntity(blockStart, blockEnd, MessageEntityType.Pre(language))
        }
        index = closingFence + 3
        lineStart = builder.lastChar == '\n'
        return true
    }

    private fun tryOpenQuote(): Boolean {
        val type = when {
            input.startsWith(">>> ", index) -> MessageEntityType.BlockQuoteExpandable
            input.startsWith("> ", index) -> MessageEntityType.BlockQuote
            else -> null
        } ?: return false

        activeQuote = MarkdownQuote(start = builder.length, type = type)
        index += if (type is MessageEntityType.BlockQuoteExpandable) 4 else 2
        lineStart = false
        return true
    }

    private fun tryAppendQuoteLine(): Boolean {
        val (prefixLength, type) = when {
            input.startsWith(">>> ", index) -> 4 to MessageEntityType.BlockQuoteExpandable
            input.startsWith("> ", index) -> 2 to MessageEntityType.BlockQuote
            input.startsWith(">", index) -> 1 to MessageEntityType.BlockQuote
            else -> return false
        }

        if (activeQuote == null) {
            activeQuote = MarkdownQuote(start = builder.length, type = type)
        } else if (type is MessageEntityType.BlockQuoteExpandable && activeQuote?.type != type) {
            activeQuote = activeQuote?.copy(type = type)
        }

        quoteLineOpen = true
        index += prefixLength
        lineStart = true
        return true
    }

    private fun tryAppendQuotedTable(): Boolean {
        val prefix = quotePrefixAt(index) ?: return false
        val headerEnd = currentLineEnd(index)
        if (headerEnd >= input.length) return false
        val separatorStart = headerEnd + 1
        val separatorEnd = currentLineEnd(separatorStart)

        val headerLine = input.substring(index, headerEnd).removePrefix(prefix)
        val separatorLine = input.substring(separatorStart, separatorEnd).removePrefix(prefix)
        val headerCells = parseMarkdownTableCells(headerLine) ?: return false
        val separatorCells = parseMarkdownTableSeparator(
            line = separatorLine,
            expectedColumns = headerCells.size
        ) ?: return false
        if (separatorCells.size != headerCells.size) return false

        val quoteType = if (prefix.startsWith(">>>")) {
            MessageEntityType.BlockQuoteExpandable
        } else {
            MessageEntityType.BlockQuote
        }
        if (activeQuote == null) {
            activeQuote = MarkdownQuote(start = builder.length, type = quoteType)
        } else if (quoteType is MessageEntityType.BlockQuoteExpandable && activeQuote?.type != quoteType) {
            activeQuote = activeQuote?.copy(type = quoteType)
        }

        val rows = mutableListOf(headerCells)
        var cursor = if (separatorEnd < input.length) separatorEnd + 1 else separatorEnd
        while (cursor < input.length) {
            val rowEnd = currentLineEnd(cursor)
            val rowPrefix = quotePrefixAt(cursor) ?: break
            val cells = parseMarkdownTableCells(
                input.substring(cursor, rowEnd).removePrefix(rowPrefix)
            ) ?: break
            if (cells.size != headerCells.size) break
            rows += cells
            cursor = if (rowEnd < input.length) rowEnd + 1 else rowEnd
        }

        val formattedTable = formatMarkdownTable(rows)
        if (formattedTable.isBlank()) return false

        val start = builder.length
        builder.appendText(formattedTable, index)
        val end = builder.length
        builder.addRichEntity(start, end, MessageEntityType.Pre("table"))

        if (cursor < input.length && builder.lastChar != '\n') {
            builder.appendChar('\n', cursor - 1)
        }
        index = cursor
        lineStart = index >= input.length || input.getOrNull(index - 1) == '\n'
        quoteLineOpen = false
        return true
    }

    private fun tryOpenHeading(): Boolean {
        var cursor = index
        var leadingSpaces = 0
        while (cursor < input.length && input[cursor] == ' ' && leadingSpaces < 3) {
            cursor++
            leadingSpaces++
        }
        if (cursor >= input.length || input[cursor] != '#') return false

        var level = 0
        while (cursor + level < input.length && input[cursor + level] == '#' && level < 3) {
            level++
        }
        if (level == 0) return false
        if (cursor + level < input.length && input[cursor + level] == '#') return false
        if (cursor + level >= input.length || !input[cursor + level].isWhitespace()) return false

        var contentStart = cursor + level
        while (contentStart < input.length && input[contentStart].isWhitespace() && input[contentStart] != '\n') {
            contentStart++
        }
        val lineEnd = currentLineEnd(index)
        if (contentStart >= lineEnd) return false

        activeHeading = MarkdownHeading(
            start = builder.length,
            level = level
        )
        index = contentStart
        lineStart = false
        return true
    }

    private fun tryAppendDivider(): Boolean {
        val lineEnd = currentLineEnd(index)
        val line = input.substring(index, lineEnd).trim()
        if (line.length < 3) return false
        val marker = line.first()
        if (marker !in setOf('-', '_', '*')) return false
        if (line.any { it != marker && !it.isWhitespace() }) return false
        if (line.count { it == marker } < 3) return false

        val start = builder.length
        builder.appendText("────────────────────", index)
        val end = builder.length
        builder.addEditorBlock(EDITOR_DIVIDER_TAG, "", start, end)

        if (lineEnd < input.length) {
            builder.appendChar('\n', lineEnd)
            index = lineEnd + 1
            lineStart = true
        } else {
            index = lineEnd
            lineStart = false
        }
        return true
    }

    private fun tryAppendTable(): Boolean {
        val headerEnd = currentLineEnd(index)
        if (headerEnd >= input.length) return false
        val separatorStart = headerEnd + 1
        val separatorEnd = currentLineEnd(separatorStart)

        val headerCells =
            parseMarkdownTableCells(input.substring(index, headerEnd).stripQuotePrefix())
                ?: return false
        val separatorCells = parseMarkdownTableSeparator(
            line = input.substring(separatorStart, separatorEnd).stripQuotePrefix(),
            expectedColumns = headerCells.size
        ) ?: return false
        if (separatorCells.size != headerCells.size) return false

        val rows = mutableListOf(headerCells)
        var cursor = if (separatorEnd < input.length) separatorEnd + 1 else separatorEnd
        while (cursor < input.length) {
            val rowEnd = currentLineEnd(cursor)
            val cells =
                parseMarkdownTableCells(input.substring(cursor, rowEnd).stripQuotePrefix()) ?: break
            if (cells.size != headerCells.size) break
            rows += cells
            cursor = if (rowEnd < input.length) rowEnd + 1 else rowEnd
        }

        val formattedTable = formatMarkdownTable(rows)
        if (formattedTable.isBlank()) return false

        val start = builder.length
        builder.appendText(formattedTable, index)
        val end = builder.length
        builder.addRichEntity(start, end, MessageEntityType.Pre("table"))

        if (cursor < input.length && builder.lastChar != '\n') {
            builder.appendChar('\n', cursor - 1)
        }
        index = cursor
        lineStart = index >= input.length || input.getOrNull(index - 1) == '\n'
        return true
    }

    private fun tryAppendListMarker(): Boolean {
        val lineEnd = currentLineEnd(index)
        if (lineEnd <= index) return false
        val line = input.substring(index, lineEnd)

        val unorderedMatch = MARKDOWN_UNORDERED_LIST_REGEX.find(line)
        if (unorderedMatch != null && unorderedMatch.range.first == 0) {
            val indent = unorderedMatch.groupValues[1]
            val markerEnd = index + unorderedMatch.range.last + 1
            builder.appendText(indent, index)
            builder.appendText("\u2022 ", index)
            index = markerEnd
            lineStart = false
            return true
        }

        val orderedMatch = MARKDOWN_ORDERED_LIST_REGEX.find(line)
        if (orderedMatch != null && orderedMatch.range.first == 0) {
            val indent = orderedMatch.groupValues[1]
            val number = orderedMatch.groupValues[2]
            val markerEnd = index + orderedMatch.range.last + 1
            builder.appendText(indent, index)
            builder.appendText("$number. ", index)
            index = markerEnd
            lineStart = false
            return true
        }

        return false
    }

    private fun tryAppendLink(): Boolean {
        val match = LINK_REGEX.find(input, index) ?: return false
        if (match.range.first != index) return false

        val label = match.groupValues[1]
        val url = match.groupValues[2].trim()
        if (label.isEmpty() || url.isEmpty()) return false

        val start = builder.length
        label.forEachIndexed { offset, char ->
            builder.appendChar(char, index + 1 + offset)
        }
        val end = builder.length
        builder.addRichEntity(start, end, MessageEntityType.TextUrl(url))
        index = match.range.last + 1
        lineStart = builder.lastChar == '\n'
        return true
    }

    private fun tryAppendLatexBlock(): Boolean {
        if (!input.startsWith("$$", index)) return false
        val closing = findClosingDelimiter("$$", index + 2) ?: return false
        val start = builder.length
        var cursor = index + 2
        while (cursor < closing) {
            appendInputChar(cursor)
            cursor++
        }
        val end = builder.length
        builder.addLatex(start, end, displayMode = true)
        index = closing + 2
        lineStart = builder.lastChar == '\n'
        return true
    }

    private fun tryAppendInlineLatex(): Boolean {
        if (input.getOrNull(index) != '$' || input.getOrNull(index + 1) == '$') return false
        val closing = findClosingDelimiter("$", index + 1) ?: return false
        val start = builder.length
        var cursor = index + 1
        while (cursor < closing) {
            appendInputChar(cursor)
            cursor++
        }
        val end = builder.length
        builder.addLatex(start, end, displayMode = false)
        index = closing + 1
        lineStart = builder.lastChar == '\n'
        return true
    }

    private fun tryToggleInlineMarker(codeOnly: Boolean = false): Boolean {
        val marker = (if (codeOnly) CODE_ONLY_MARKERS else MARKDOWN_MARKERS)
            .firstOrNull { input.startsWith(it, index) }
            ?: return false
        val type = markerToType(marker) ?: return false
        val starts = markerStarts.getOrPut(marker) { mutableListOf() }

        if (starts.isNotEmpty()) {
            val start = starts.removeAt(starts.lastIndex)
            val end = builder.length
            if (start < end) {
                builder.addRichEntity(start, end, type)
            }
            index += marker.length
            return true
        }

        if (findClosingDelimiter(marker, index + marker.length) == null) return false
        starts += builder.length
        index += marker.length
        return true
    }

    private fun findClosingDelimiter(delimiter: String, fromIndex: Int): Int? {
        var cursor = fromIndex
        while (cursor <= input.length - delimiter.length) {
            if (input[cursor] == '\\') {
                cursor += 2
                continue
            }
            if (input.startsWith(delimiter, cursor)) return cursor
            cursor++
        }
        return null
    }

    private fun appendInputChar(sourceIndex: Int) {
        val char = input[sourceIndex]
        if (char == '\n') {
            closeActiveHeading()
            quoteLineOpen = false
        }
        builder.appendChar(char, sourceIndex)
        lineStart = char == '\n'
    }

    private fun closeActiveQuote() {
        val quote = activeQuote ?: return
        val end = builder.length
        if (quote.start < end) {
            builder.addRichEntity(quote.start, end, quote.type)
        }
        activeQuote = null
        quoteLineOpen = false
    }

    private fun closeActiveHeading() {
        val heading = activeHeading ?: return
        val end = builder.length
        if (heading.start < end) {
            builder.addEditorBlock(
                tag = EDITOR_HEADING_TAG,
                item = heading.level.toString(),
                start = heading.start,
                end = end
            )
        }
        activeHeading = null
    }

    private fun currentLineEnd(fromIndex: Int): Int {
        val newlineIndex = input.indexOf('\n', startIndex = fromIndex)
        return if (newlineIndex == -1) input.length else newlineIndex
    }

    private fun String.stripQuotePrefix(): String {
        return when {
            startsWith(">>> ") -> substring(4)
            startsWith("> ") -> substring(2)
            startsWith(">") -> substring(1)
            else -> this
        }
    }

    private fun quotePrefixAt(startIndex: Int): String? {
        return when {
            input.startsWith(">>> ", startIndex) -> ">>> "
            input.startsWith("> ", startIndex) -> "> "
            input.startsWith(">", startIndex) -> ">"
            else -> null
        }
    }
}

internal class HtmlRichTextParser(private val source: AnnotatedString) {
    private val input = source.text
    private val builder = ParsedAnnotatedStringBuilder(source)
    private val openTags = mutableListOf<HtmlOpenTag>()
    private var index = 0

    fun parse(): AnnotatedString {
        while (index < input.length) {
            if (tryAppendHtmlTable()) continue
            if (input[index] == '<' && tryHandleTag()) continue
            if (input[index] == '&' && tryHandleEntity()) continue

            builder.appendChar(input[index], index)
            index++
        }

        return builder.build()
    }

    private fun tryHandleTag(): Boolean {
        if (input.startsWith("<!--", index)) {
            val commentEnd = input.indexOf("-->", startIndex = index + 4)
            if (commentEnd != -1) {
                index = commentEnd + 3
                return true
            }
        }

        val token = parseHtmlTagToken(input, index) ?: return false
        val handled = when {
            token.isClosing -> closeTag(token)
            else -> openTag(token)
        }

        if (!handled) return false
        index += token.raw.length
        return true
    }

    private fun openTag(token: HtmlTagToken): Boolean {
        return when (val name = token.name.lowercase()) {
            "br" -> {
                builder.appendChar('\n', index)
                true
            }

            "b", "strong" -> pushOrApply(token, HtmlOpenKind.Rich(MessageEntityType.Bold))
            "i", "em" -> pushOrApply(token, HtmlOpenKind.Rich(MessageEntityType.Italic))
            "u", "ins" -> pushOrApply(token, HtmlOpenKind.Rich(MessageEntityType.Underline))
            "s", "strike", "del" -> pushOrApply(
                token,
                HtmlOpenKind.Rich(MessageEntityType.Strikethrough)
            )

            "tg-spoiler" -> pushOrApply(token, HtmlOpenKind.Rich(MessageEntityType.Spoiler))
            "span" -> {
                if (token.attributes["class"]?.contains("tg-spoiler", ignoreCase = true) == true) {
                    pushOrApply(token, HtmlOpenKind.Rich(MessageEntityType.Spoiler))
                } else {
                    false
                }
            }

            "a" -> {
                val href = token.attributes["href"].orEmpty().trim()
                when {
                    href.startsWith("tg://user?id=") -> {
                        val userId =
                            href.substringAfter("tg://user?id=").toLongOrNull() ?: return false
                        pushOrApply(token, HtmlOpenKind.TextMention(userId))
                    }

                    href.isNotBlank() -> pushOrApply(
                        token,
                        HtmlOpenKind.Rich(MessageEntityType.TextUrl(href))
                    )

                    else -> false
                }
            }

            "code" -> {
                if (openTags.lastOrNull()?.kind is HtmlOpenKind.Pre) {
                    val language = token.attributes["class"]
                        ?.split(' ', '\t', '\n')
                        ?.firstOrNull { it.startsWith("language-") }
                        ?.substringAfter("language-")
                        .orEmpty()
                    if (language.isNotBlank()) {
                        val preTag = openTags.last()
                        preTag.kind = HtmlOpenKind.Pre(language)
                    }
                    false
                } else {
                    pushOrApply(token, HtmlOpenKind.Rich(MessageEntityType.Code))
                }
            }

            "pre" -> {
                ensureBlockBoundary()
                pushOrApply(
                    token,
                    HtmlOpenKind.Pre(
                        token.attributes["language"]
                            ?: token.attributes["lang"]
                            ?: token.attributes["data-language"]
                            ?: ""
                    )
                )
            }

            "h1", "h2", "h3" -> {
                ensureBlockBoundary()
                pushOrApply(
                    token,
                    HtmlOpenKind.Heading(name.removePrefix("h").toIntOrNull()?.coerceIn(1, 3) ?: 1)
                )
            }

            "blockquote" -> {
                ensureBlockBoundary()
                val type = if (token.attributes.containsKey("expandable")) {
                    MessageEntityType.BlockQuoteExpandable
                } else {
                    MessageEntityType.BlockQuote
                }
                pushOrApply(token, HtmlOpenKind.Rich(type))
            }

            "p", "div" -> {
                ensureBlockBoundary()
                pushOrApply(token, HtmlOpenKind.BlockContainer)
            }

            "ul", "ol" -> {
                ensureBlockBoundary()
                pushOrApply(token, HtmlOpenKind.ListContainer(ordered = name == "ol"))
            }

            "li" -> {
                ensureBlockBoundary()
                val listContainer =
                    openTags.lastOrNull { it.kind is HtmlOpenKind.ListContainer } ?: return false
                val listKind = listContainer.kind as HtmlOpenKind.ListContainer
                val prefix = if (listKind.ordered) {
                    "${listKind.nextIndex}. ".also { listKind.nextIndex++ }
                } else {
                    "\u2022 "
                }
                builder.appendText(prefix, index)
                pushOrApply(token, HtmlOpenKind.BlockContainer)
            }

            "hr" -> {
                val start = builder.length
                builder.appendText("────────────────────", index)
                builder.addEditorBlock(EDITOR_DIVIDER_TAG, "", start, builder.length)
                if (builder.lastChar != '\n') {
                    builder.appendChar('\n', index)
                }
                true
            }

            "tg-emoji" -> {
                val emojiId = token.attributes["emoji-id"]?.toLongOrNull() ?: return false
                if (token.selfClosing) {
                    val start = builder.length
                    builder.appendChar('\uFFFC', index)
                    builder.addCustomEmoji(start, builder.length, emojiId)
                    true
                } else {
                    pushOrApply(token, HtmlOpenKind.CustomEmoji(emojiId))
                }
            }

            "tg-math", "math", "latex" -> {
                ensureBlockBoundary()
                pushOrApply(token, HtmlOpenKind.Latex(displayMode = name != "latex"))
            }

            else -> false
        }
    }

    private fun closeTag(token: HtmlTagToken): Boolean {
        val matchIndex = openTags.indexOfLast { it.name == token.name.lowercase() }
        if (matchIndex == -1) return false

        while (openTags.size > matchIndex) {
            val openTag = openTags.removeAt(openTags.lastIndex)
            val start = openTag.start
            val end = builder.length

            when (val kind = openTag.kind) {
                is HtmlOpenKind.Rich -> if (start < end) builder.addRichEntity(
                    start,
                    end,
                    kind.type
                )

                is HtmlOpenKind.Pre -> if (start < end) builder.addRichEntity(
                    start,
                    end,
                    MessageEntityType.Pre(kind.language)
                )

                is HtmlOpenKind.TextMention -> if (start < end) builder.addMention(
                    start,
                    end,
                    kind.userId
                )

                is HtmlOpenKind.CustomEmoji -> if (start < end) builder.addCustomEmoji(
                    start,
                    end,
                    kind.emojiId
                )

                is HtmlOpenKind.Latex -> if (start < end) builder.addLatex(
                    start,
                    end,
                    kind.displayMode
                )

                is HtmlOpenKind.Heading -> if (start < end) builder.addEditorBlock(
                    EDITOR_HEADING_TAG,
                    kind.level.toString(),
                    start,
                    end
                )

                is HtmlOpenKind.ListContainer -> Unit
                HtmlOpenKind.BlockContainer -> Unit
            }

            if (openTag.name in BLOCK_LEVEL_TAGS && builder.lastChar != '\n') {
                builder.appendChar('\n', index)
            }

            if (openTag.name == token.name.lowercase()) break
        }

        return true
    }

    private fun pushOrApply(token: HtmlTagToken, kind: HtmlOpenKind): Boolean {
        if (token.selfClosing) {
            val start = builder.length
            when (kind) {
                is HtmlOpenKind.CustomEmoji -> {
                    builder.appendChar('\uFFFC', index)
                    builder.addCustomEmoji(start, builder.length, kind.emojiId)
                }

                else -> Unit
            }
            return true
        }

        openTags += HtmlOpenTag(token.name.lowercase(), builder.length, kind)
        return true
    }

    private fun ensureBlockBoundary() {
        if (builder.length > 0 && builder.lastChar != '\n') {
            builder.appendChar('\n', index)
        }
    }

    private fun tryAppendHtmlTable(): Boolean {
        if (!input.startsWith("<table", index, ignoreCase = true)) return false
        val closingStart = input.indexOf("</table>", startIndex = index, ignoreCase = true)
        if (closingStart == -1) return false
        val closingEnd = closingStart + "</table>".length
        val block = input.substring(index, closingEnd)
        val rows = parseHtmlTableRows(block)
        if (rows.isEmpty()) return false

        ensureBlockBoundary()
        val start = builder.length
        builder.appendText(formatMarkdownTable(rows), index)
        builder.addRichEntity(start, builder.length, MessageEntityType.Pre("table"))
        if (closingEnd < input.length && builder.lastChar != '\n') {
            builder.appendChar('\n', closingStart)
        }
        index = closingEnd
        return true
    }

    private fun tryHandleEntity(): Boolean {
        val semicolon = input.indexOf(';', startIndex = index + 1)
        if (semicolon == -1) return false
        val entity = input.substring(index + 1, semicolon)
        val decoded = decodeHtmlEntity(entity) ?: return false
        decoded.forEach { char -> builder.appendChar(char, index) }
        index = semicolon + 1
        return true
    }
}

private class ParsedAnnotatedStringBuilder(private val source: AnnotatedString) {
    private val textBuilder = StringBuilder()
    private val sourceIndexByOutputIndex = mutableListOf<Int>()
    private val richRanges = mutableListOf<RichRange>()
    private val mentionRanges = mutableListOf<MentionRange>()
    private val customEmojiRanges = mutableListOf<CustomEmojiRange>()
    private val latexRanges = mutableListOf<LatexRange>()
    private val editorBlockRanges = mutableListOf<EditorBlockRange>()

    val length: Int
        get() = textBuilder.length

    val lastChar: Char?
        get() = textBuilder.lastOrNull()

    fun appendChar(char: Char, sourceIndex: Int) {
        textBuilder.append(char)
        sourceIndexByOutputIndex += sourceIndex
    }

    fun appendText(text: String, sourceIndex: Int) {
        text.forEach { char -> appendChar(char, sourceIndex) }
    }

    fun addRichEntity(start: Int, end: Int, type: MessageEntityType) {
        if (start < end) richRanges += RichRange(start, end, type)
    }

    fun addMention(start: Int, end: Int, userId: Long) {
        if (start < end) mentionRanges += MentionRange(start, end, userId)
    }

    fun addCustomEmoji(start: Int, end: Int, emojiId: Long) {
        if (start < end) customEmojiRanges += CustomEmojiRange(start, end, emojiId)
    }

    fun addLatex(start: Int, end: Int, displayMode: Boolean) {
        if (start < end) latexRanges += LatexRange(start, end, displayMode)
    }

    fun addEditorBlock(tag: String, item: String, start: Int, end: Int) {
        if (start < end) editorBlockRanges += EditorBlockRange(tag, item, start, end)
    }

    fun build(): AnnotatedString {
        val builder = AnnotatedString.Builder(textBuilder.toString())

        source.getStringAnnotations(0, source.length).forEach { annotation ->
            val mapped = mapSourceRange(annotation.start, annotation.end) ?: return@forEach
            if (mapped.first < mapped.second) {
                builder.addStringAnnotation(
                    annotation.tag,
                    annotation.item,
                    mapped.first,
                    mapped.second
                )
            }
        }

        richRanges.forEach { range ->
            val key = richEntityToAnnotation(range.type) ?: return@forEach
            builder.addStringAnnotation(RICH_ENTITY_TAG, key, range.start, range.end)
        }
        mentionRanges.forEach { range ->
            builder.addStringAnnotation(
                MENTION_TAG,
                range.userId.toString(),
                range.start,
                range.end
            )
        }
        customEmojiRanges.forEach { range ->
            builder.addStringAnnotation(
                CUSTOM_EMOJI_TAG,
                range.emojiId.toString(),
                range.start,
                range.end
            )
        }
        latexRanges.forEach { range ->
            builder.addStringAnnotation(
                LATEX_TAG,
                if (range.displayMode) "block" else "inline",
                range.start,
                range.end
            )
        }
        editorBlockRanges.forEach { range ->
            builder.addStringAnnotation(range.tag, range.item, range.start, range.end)
        }

        return builder.toAnnotatedString()
    }

    private fun mapSourceRange(start: Int, end: Int): Pair<Int, Int>? {
        var mappedStart = -1
        var mappedEnd = -1

        sourceIndexByOutputIndex.forEachIndexed { outputIndex, sourceIndex ->
            if (sourceIndex in start until end) {
                if (mappedStart == -1) mappedStart = outputIndex
                mappedEnd = outputIndex + 1
            }
        }

        return if (mappedStart >= 0 && mappedEnd > mappedStart) mappedStart to mappedEnd else null
    }
}

private fun markerToType(marker: String): MessageEntityType? {
    return when (marker) {
        "**" -> MessageEntityType.Bold
        "__" -> MessageEntityType.Underline
        "*", "_" -> MessageEntityType.Italic
        "~~" -> MessageEntityType.Strikethrough
        "||" -> MessageEntityType.Spoiler
        "`" -> MessageEntityType.Code
        else -> null
    }
}

private fun normalizeMarkdownForSending(text: String): String {
    if (text.isBlank()) return text
    val lines = normalizeMarkdownLatex(text).split('\n')
    val result = mutableListOf<String>()
    var index = 0

    while (index < lines.size) {
        val line = lines[index]
        val heading = parseMarkdownHeadingLine(line)
        if (heading != null) {
            result += "**${heading.trim()}**"
            index++
            continue
        }

        if (isMarkdownDividerLine(line)) {
            result += "────────────────────"
            index++
            continue
        }

        val table = collectMarkdownTable(lines, index)
        if (table != null) {
            result += "```table"
            result += formatMarkdownTable(table.rows).split('\n')
            result += "```"
            index += table.consumedLineCount
            continue
        }

        val unordered = MARKDOWN_UNORDERED_LIST_REGEX.find(line)
            ?.takeIf { it.range.first == 0 }
            ?.let { match ->
                "${match.groupValues[1]}\u2022 ${line.substring(match.range.last + 1)}"
            }
            ?: line
        val normalized = MARKDOWN_ORDERED_LIST_REGEX.find(unordered)
            ?.takeIf { it.range.first == 0 }
            ?.let { match ->
                "${match.groupValues[1]}${match.groupValues[2]}. ${unordered.substring(match.range.last + 1)}"
            }
            ?: unordered
        result += normalized
        index++
    }

    return result.joinToString("\n")
}

private fun normalizeHtmlForSending(text: String): String {
    if (text.isBlank()) return text
    var normalized = normalizeHtmlMath(text)
    normalized = normalizeHtmlLists(normalized)

    normalized = HTML_HEADING_REGEX.replace(normalized) { match ->
        val content = match.groupValues[2].trim()
        if (content.isBlank()) "" else "<b>$content</b>"
    }

    normalized = HTML_HR_REGEX.replace(normalized, "────────────────────")

    normalized = HTML_TABLE_REGEX.replace(normalized) { match ->
        val rows = parseHtmlTableRows(match.value)
        if (rows.isEmpty()) {
            match.value
        } else {
            "<pre>${formatMarkdownTable(rows)}</pre>"
        }
    }

    return normalized
}

private fun normalizeMarkdownLatex(text: String): String {
    val blockNormalized = MARKDOWN_BLOCK_LATEX_REGEX.replace(text) { match ->
        val content = match.groupValues[1].trim('\n', '\r')
        if (content.isBlank()) "" else "```math\n$content\n```"
    }
    return MARKDOWN_INLINE_LATEX_REGEX.replace(blockNormalized) { match ->
        val content = match.groupValues[1].trim()
        if (content.isBlank()) "" else "`$content`"
    }
}

private fun normalizeHtmlMath(text: String): String {
    var normalized = HTML_BLOCK_LATEX_REGEX.replace(text) { match ->
        val content = decodeBasicHtml(match.groupValues[2].replace(HTML_TAG_REGEX, " ").trim())
        if (content.isBlank()) "" else "<pre>$content</pre>"
    }
    normalized = HTML_INLINE_LATEX_REGEX.replace(normalized) { match ->
        val content = decodeBasicHtml(match.groupValues[2].replace(HTML_TAG_REGEX, " ").trim())
        if (content.isBlank()) "" else "<code>$content</code>"
    }
    return normalized
}

private fun normalizeHtmlLists(text: String): String {
    var normalized = text
    while (true) {
        val next = HTML_ORDERED_LIST_REGEX.replace(normalized) { match ->
            val items = extractHtmlListItems(match.groupValues[1])
            if (items.isEmpty()) {
                match.value
            } else {
                items.mapIndexed { index, item -> "${index + 1}. $item" }.joinToString("\n")
            }
        }
        if (next == normalized) break
        normalized = next
    }

    while (true) {
        val next = HTML_UNORDERED_LIST_REGEX.replace(normalized) { match ->
            val items = extractHtmlListItems(match.groupValues[1])
            if (items.isEmpty()) {
                match.value
            } else {
                items.joinToString("\n") { item -> "\u2022 $item" }
            }
        }
        if (next == normalized) break
        normalized = next
    }

    return normalized
}

private fun parseMarkdownHeadingLine(line: String): String? {
    val match = MARKDOWN_HEADING_LINE_REGEX.find(line) ?: return null
    if (match.range.first != 0) return null
    return match.groupValues[3].trim().trimEnd('#').trim()
}

private fun isMarkdownDividerLine(line: String): Boolean {
    val trimmed = line.trim()
    if (trimmed.length < 3) return false
    val marker = trimmed.firstOrNull() ?: return false
    if (marker !in setOf('-', '_', '*')) return false
    if (trimmed.any { it != marker && !it.isWhitespace() }) return false
    return trimmed.count { it == marker } >= 3
}

private data class MarkdownTableBlock(
    val rows: List<List<String>>,
    val consumedLineCount: Int
)

private fun collectMarkdownTable(lines: List<String>, startIndex: Int): MarkdownTableBlock? {
    if (startIndex + 1 >= lines.size) return null
    val headerCells = parseMarkdownTableCells(lines[startIndex]) ?: return null
    val separatorCells =
        parseMarkdownTableSeparator(lines[startIndex + 1], headerCells.size) ?: return null
    if (separatorCells.size != headerCells.size) return null

    val rows = mutableListOf(headerCells)
    var index = startIndex + 2
    while (index < lines.size) {
        val row = parseMarkdownTableCells(lines[index]) ?: break
        if (row.size != headerCells.size) break
        rows += row
        index++
    }
    return MarkdownTableBlock(rows = rows, consumedLineCount = index - startIndex)
}

private fun parseMarkdownTableCells(line: String): List<String>? {
    if (!line.contains('|')) return null
    val trimmed = line.trim()
    if (trimmed.isBlank()) return null
    val normalized = trimmed.removePrefix("|").removeSuffix("|")
    val cells = splitUnescapedPipes(normalized).map { it.trim() }
    return if (cells.size >= 2 && cells.any { it.isNotEmpty() }) cells else null
}

private fun parseMarkdownTableSeparator(line: String, expectedColumns: Int): List<String>? {
    val cells = parseMarkdownTableCells(line) ?: return null
    if (cells.size != expectedColumns) return null
    return cells.takeIf { separatorCells ->
        separatorCells.all { cell ->
            val trimmed = cell.trim()
            trimmed.length >= 3 && trimmed.all { it == '-' || it == ':' }
        }
    }
}

private fun splitUnescapedPipes(line: String): List<String> {
    val result = mutableListOf<String>()
    val current = StringBuilder()
    var escaped = false
    line.forEach { char ->
        when {
            escaped -> {
                current.append(char)
                escaped = false
            }

            char == '\\' -> escaped = true
            char == '|' -> {
                result += current.toString()
                current.clear()
            }

            else -> current.append(char)
        }
    }
    result += current.toString()
    return result
}

private fun formatMarkdownTable(rows: List<List<String>>): String {
    if (rows.isEmpty()) return ""
    val columnCount = rows.maxOfOrNull { it.size } ?: return ""
    val normalizedRows = rows.map { row ->
        if (row.size == columnCount) row else row + List(columnCount - row.size) { "" }
    }
    val widths = IntArray(columnCount) { column ->
        normalizedRows.maxOf { row -> row[column].length }
    }

    fun border(left: String, middle: String, right: String): String {
        return buildString {
            append(left)
            widths.forEachIndexed { index, width ->
                append("─".repeat(width + 2))
                append(if (index == widths.lastIndex) right else middle)
            }
        }
    }

    fun rowLine(cells: List<String>): String {
        return buildString {
            append("│")
            cells.forEachIndexed { index, cell ->
                append(' ')
                append(cell.padEnd(widths[index]))
                append(' ')
                append("│")
            }
        }
    }

    return buildString {
        appendLine(border("┌", "┬", "┐"))
        appendLine(rowLine(normalizedRows.first()))
        appendLine(border("├", "┼", "┤"))
        normalizedRows.drop(1).forEachIndexed { index, row ->
            appendLine(rowLine(row))
            if (index != normalizedRows.drop(1).lastIndex) {
                appendLine(border("├", "┼", "┤"))
            }
        }
        append(border("└", "┴", "┘"))
    }
}

private fun parseHtmlTableRows(tableHtml: String): List<List<String>> {
    val rowMatches = HTML_TABLE_ROW_REGEX.findAll(tableHtml)
    return rowMatches.mapNotNull { rowMatch ->
        val cells = HTML_TABLE_CELL_REGEX.findAll(rowMatch.groupValues[1]).map { cellMatch ->
            decodeBasicHtml(cellMatch.groupValues[2].replace(HTML_TAG_REGEX, " ").trim())
        }.toList()
        cells.takeIf { it.isNotEmpty() }
    }.toList()
}

private fun extractHtmlListItems(listHtml: String): List<String> {
    return HTML_LIST_ITEM_REGEX.findAll(listHtml).mapNotNull { match ->
        decodeBasicHtml(match.groupValues[1].replace(HTML_TAG_REGEX, " ").trim())
            .takeIf { it.isNotBlank() }
    }.toList()
}

private fun decodeBasicHtml(value: String): String {
    return value
        .replace("&nbsp;", " ", ignoreCase = true)
        .replace("&lt;", "<", ignoreCase = true)
        .replace("&gt;", ">", ignoreCase = true)
        .replace("&amp;", "&", ignoreCase = true)
        .replace("&quot;", "\"", ignoreCase = true)
        .replace("&#39;", "'", ignoreCase = true)
}

private fun parseHtmlTagToken(text: String, startIndex: Int): HtmlTagToken? {
    if (text.getOrNull(startIndex) != '<') return null

    var cursor = startIndex + 1
    var inSingleQuote = false
    var inDoubleQuote = false

    while (cursor < text.length) {
        when (text[cursor]) {
            '\'' -> if (!inDoubleQuote) inSingleQuote = !inSingleQuote
            '"' -> if (!inSingleQuote) inDoubleQuote = !inDoubleQuote
            '>' -> if (!inSingleQuote && !inDoubleQuote) break
        }
        cursor++
    }

    if (cursor >= text.length || text[cursor] != '>') return null

    val raw = text.substring(startIndex, cursor + 1)
    val inner = raw.removePrefix("<").removeSuffix(">").trim()
    if (inner.isEmpty()) return null

    val isClosing = inner.startsWith("/")
    val normalized = if (isClosing) inner.removePrefix("/").trim() else inner
    val selfClosing = normalized.endsWith("/")
    val content = normalized.removeSuffix("/").trim()
    if (content.isEmpty()) return null

    val nameEnd =
        content.indexOfFirst { it.isWhitespace() }.let { if (it == -1) content.length else it }
    val name = content.substring(0, nameEnd)
    val attributes = if (nameEnd < content.length) {
        parseHtmlAttributes(content.substring(nameEnd).trim())
    } else {
        emptyMap()
    }

    return HtmlTagToken(
        raw = raw,
        name = name,
        isClosing = isClosing,
        selfClosing = selfClosing,
        attributes = attributes
    )
}

private fun parseHtmlAttributes(input: String): Map<String, String?> {
    if (input.isBlank()) return emptyMap()
    val result = linkedMapOf<String, String?>()
    var index = 0

    while (index < input.length) {
        while (index < input.length && input[index].isWhitespace()) index++
        if (index >= input.length) break

        val nameStart = index
        while (index < input.length && !input[index].isWhitespace() && input[index] != '=') index++
        val name = input.substring(nameStart, index).lowercase()
        while (index < input.length && input[index].isWhitespace()) index++

        var value: String? = null
        if (index < input.length && input[index] == '=') {
            index++
            while (index < input.length && input[index].isWhitespace()) index++
            if (index >= input.length) {
                value = ""
            } else if (input[index] == '"' || input[index] == '\'') {
                val quote = input[index]
                index++
                val valueStart = index
                while (index < input.length && input[index] != quote) index++
                value = input.substring(valueStart, index)
                if (index < input.length) index++
            } else {
                val valueStart = index
                while (index < input.length && !input[index].isWhitespace()) index++
                value = input.substring(valueStart, index)
            }
        }

        if (name.isNotBlank()) {
            result[name] = value
        }
    }

    return result
}

private fun decodeHtmlEntity(entity: String): String? {
    return when (entity.lowercase()) {
        "amp" -> "&"
        "lt" -> "<"
        "gt" -> ">"
        "quot" -> "\""
        "apos", "#39" -> "'"
        "nbsp" -> " "
        else -> {
            when {
                entity.startsWith("#x", ignoreCase = true) -> entity.substring(2).toIntOrNull(16)
                    ?.toChar()?.toString()

                entity.startsWith("#") -> entity.substring(1).toIntOrNull()?.toChar()?.toString()
                else -> null
            }
        }
    }
}

private data class MarkdownQuote(
    val start: Int,
    val type: MessageEntityType
)

private data class MarkdownHeading(
    val start: Int,
    val level: Int
)

private data class RichRange(
    val start: Int,
    val end: Int,
    val type: MessageEntityType
)

private data class MentionRange(
    val start: Int,
    val end: Int,
    val userId: Long
)

private data class CustomEmojiRange(
    val start: Int,
    val end: Int,
    val emojiId: Long
)

private data class LatexRange(
    val start: Int,
    val end: Int,
    val displayMode: Boolean
)

private data class EditorBlockRange(
    val tag: String,
    val item: String,
    val start: Int,
    val end: Int
)

private data class HtmlTagToken(
    val raw: String,
    val name: String,
    val isClosing: Boolean,
    val selfClosing: Boolean,
    val attributes: Map<String, String?>
)

private data class HtmlOpenTag(
    val name: String,
    val start: Int,
    var kind: HtmlOpenKind
)

private sealed interface HtmlOpenKind {
    data class Rich(val type: MessageEntityType) : HtmlOpenKind
    data class Pre(val language: String) : HtmlOpenKind
    data class Heading(val level: Int) : HtmlOpenKind
    data class ListContainer(val ordered: Boolean, var nextIndex: Int = 1) : HtmlOpenKind
    data class TextMention(val userId: Long) : HtmlOpenKind
    data class CustomEmoji(val emojiId: Long) : HtmlOpenKind
    data class Latex(val displayMode: Boolean) : HtmlOpenKind
    data object BlockContainer : HtmlOpenKind
}

private val LINK_REGEX = Regex("\\[([^\\]]+)]\\(([^)]+)\\)")
private val MARKDOWN_MARKERS = listOf("**", "__", "~~", "||", "`", "*", "_")
private val CODE_ONLY_MARKERS = listOf("`")
private val MARKDOWN_HEADING_LINE_REGEX = Regex("^(\\s{0,3})(#{1,3})\\s+(.+?)\\s*$")
private val MARKDOWN_UNORDERED_LIST_REGEX = Regex("^(\\s{0,6})[-*+]\\s+")
private val MARKDOWN_ORDERED_LIST_REGEX = Regex("^(\\s{0,6})(\\d+)[.)]\\s+")
private val BLOCK_LEVEL_TAGS =
    setOf("pre", "blockquote", "p", "div", "tg-math", "math", "h1", "h2", "h3", "ul", "ol", "li")
private val HTML_HEADING_REGEX = Regex(
    "<h([1-3])\\b[^>]*>(.*?)</h\\1>",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
)
private val HTML_HR_REGEX = Regex("<hr\\b[^>]*?/?>", RegexOption.IGNORE_CASE)
private val HTML_UNORDERED_LIST_REGEX =
    Regex("<ul\\b[^>]*>(.*?)</ul>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val HTML_ORDERED_LIST_REGEX =
    Regex("<ol\\b[^>]*>(.*?)</ol>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val HTML_LIST_ITEM_REGEX =
    Regex("<li\\b[^>]*>(.*?)</li>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val HTML_TABLE_REGEX =
    Regex("<table\\b[^>]*>.*?</table>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val HTML_TABLE_ROW_REGEX =
    Regex("<tr\\b[^>]*>(.*?)</tr>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val HTML_TABLE_CELL_REGEX = Regex(
    "<(th|td)\\b[^>]*>(.*?)</\\1>",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
)
private val HTML_TAG_REGEX = Regex("<[^>]+>")
private val HTML_BLOCK_LATEX_REGEX = Regex(
    "<(tg-math|math)\\b[^>]*>(.*?)</\\1>",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
)
private val HTML_INLINE_LATEX_REGEX = Regex(
    "<latex\\b[^>]*>(.*?)</latex>",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
)
private val MARKDOWN_BLOCK_LATEX_REGEX = Regex("\\$\\$\\s*([\\s\\S]*?)\\s*\\$\\$")
private val MARKDOWN_INLINE_LATEX_REGEX = Regex("(?<!\\$)\\$(?!\\$)(.+?)(?<!\\$)\\$(?!\\$)")
