package org.monogram.feature.dialog.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import org.monogram.core.models.parseMarkdownMapped

internal val CustomEmojiMarkdown = Regex("""!\[([^]]*)]\(tg://emoji\?id=(\d+)\)""")

internal data class ComposerEmojiSpan(
    val documentId: Long,
    val displayStart: Int,
    val displayLength: Int,
)

internal fun composerEmojiSpans(raw: String): List<ComposerEmojiSpan> {
    val matches = CustomEmojiMarkdown.findAll(raw).toList()
    if (matches.isEmpty()) return emptyList()
    val spans = ArrayList<ComposerEmojiSpan>(matches.size)
    var src = 0
    var disp = 0
    for (match in matches) {
        disp += match.range.first - src
        val alt = match.groupValues[1].ifEmpty { "🙂" }
        val id = match.groupValues[2].toLongOrNull() ?: 0L
        if (id != 0L) spans += ComposerEmojiSpan(id, disp, alt.length)
        disp += alt.length
        src = match.range.last + 1
    }
    return spans
}

internal class MarkdownVisualTransformation(
    private val linkColor: Color,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        if (text.isEmpty()) return TransformedText(text, OffsetMapping.Identity)
        collapseCustomEmojiMarkdown(text.text)?.let { return it }
        val mapped = parseMarkdownMapped(text.text)
        val originalEntities = mapped.styled.entities.mapNotNull { entity ->
            val range = originalRange(
                mapped.origToDisp,
                entity.offset,
                entity.offset + entity.length,
            )
            val start = range.first.coerceIn(0, text.length)
            val end = (range.last + 1).coerceIn(start, text.length)
            if (start >= end) return@mapNotNull null
            entity.copy(offset = start, length = end - start)
        }
        val styled = buildAnnotatedString {
            append(text.text)
            applyMessageEntities(
                text = text.text,
                entities = originalEntities,
                linkColor = linkColor,
                revealSpoilers = true,
            )
            originalEntities.filter { it.kind == "heading" }.forEach { entity ->
                val start = entity.offset.coerceIn(0, text.length)
                val end = (entity.offset + entity.length).coerceIn(start, text.length)
                if (start < end) {
                    addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, end)
                }
            }
        }
        return TransformedText(styled, OffsetMapping.Identity)
    }
}

internal fun collapseCustomEmojiMarkdown(raw: String): TransformedText? {
    val matches = CustomEmojiMarkdown.findAll(raw).toList()
    if (matches.isEmpty()) return null
    val origToDisp = IntArray(raw.length + 1)
    val out = StringBuilder()
    var src = 0
    for (match in matches) {
        while (src < match.range.first) {
            origToDisp[src] = out.length
            out.append(raw[src])
            src++
        }
        val alt = match.groupValues[1].ifEmpty { "🙂" }
        val disp = out.length
        out.append(alt)
        while (src <= match.range.last) {
            origToDisp[src] = disp
            src++
        }
    }
    while (src < raw.length) {
        origToDisp[src] = out.length
        out.append(raw[src])
        src++
    }
    origToDisp[raw.length] = out.length
    val displayed = out.toString()
    val styled = buildAnnotatedString {
        append(displayed)
        composerEmojiSpans(raw).forEach { span ->
            val end = (span.displayStart + span.displayLength).coerceAtMost(displayed.length)
            if (span.displayStart < end) {
                addStyle(SpanStyle(color = Color.Transparent), span.displayStart, end)
            }
        }
    }
    return TransformedText(styled, MarkdownOffsetMapping(origToDisp))
}

internal fun originalRange(origToDisp: IntArray, dispStart: Int, dispEnd: Int): IntRange {
    if (origToDisp.isEmpty()) return 0 until 0
    var start = origToDisp.lastIndex
    var end = origToDisp.lastIndex
    for (i in origToDisp.indices) {
        if (origToDisp[i] >= dispStart) {
            start = i
            break
        }
    }
    for (i in origToDisp.indices) {
        if (origToDisp[i] >= dispEnd) {
            end = i
            break
        }
    }
    if (end < start) end = start
    return start until end
}

internal class MarkdownOffsetMapping(
    private val origToDisp: IntArray,
) : OffsetMapping {
    override fun originalToTransformed(offset: Int): Int {
        if (origToDisp.isEmpty()) return 0
        return origToDisp[offset.coerceIn(0, origToDisp.lastIndex)]
    }

    override fun transformedToOriginal(offset: Int): Int {
        if (origToDisp.isEmpty()) return 0
        val target = offset.coerceIn(0, origToDisp.last())
        for (i in 0 until origToDisp.lastIndex) {
            if (origToDisp[i] == target && origToDisp[i + 1] > target) return i
        }
        for (i in origToDisp.indices) {
            if (origToDisp[i] >= target) return i
        }
        return origToDisp.lastIndex
    }
}
