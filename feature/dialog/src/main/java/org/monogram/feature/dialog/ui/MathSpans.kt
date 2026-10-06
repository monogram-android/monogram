package org.monogram.feature.dialog.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.monogram.core.markup.MathSpan
import org.monogram.core.models.TextEntity
import org.monogram.core.models.replaceRichDates
import org.monogram.core.ui.AppearanceSettings
import org.monogram.core.ui.theme.scaledToMessageSize
import org.monogram.feature.dialog.R

@Composable
internal fun MathAwareText(
    text: String,
    entities: List<TextEntity>,
    contentColor: Color,
    linkColor: Color,
    revealSpoilers: Boolean,
    modifier: Modifier = Modifier,
    selectable: Boolean = false,
    selectAllNonce: Int = 0,
    onSelectedText: (String) -> Unit = {},
    onTextLayout: (TextLayoutResult) -> Unit = {},
    onOpenStickerPack: ((Long) -> Unit)? = null,
    onSpoilerClick: (() -> Unit)? = null,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    scaleMessageText: Boolean = true,
) {
    val appearance by AppearanceSettings.state.collectAsState()
    val sizedStyle = if (scaleMessageText) {
        textStyle.scaledToMessageSize(
            appearance.messageTextSize,
            appearance.lineSpacing,
            appearance.letterSpacing,
        )
    } else {
        textStyle
    }
    val dateAtTime = stringResource(R.string.dialog_rich_date_at_time)
    val dated = remember(text, entities, dateAtTime) {
        replaceRichDates(
            text,
            entities,
            dateAtTime = { date, time -> dateAtTime.format(date, time) })
    }
    val renderedText = dated.first
    val renderedEntities = dated.second
    val markup = LocalMarkupParser.current
    val math = remember(markup, renderedText, renderedEntities, revealSpoilers, selectable) {
        if (selectable) {
            emptyList()
        } else {
            val fromEntities = mathSpansFromEntities(renderedText, renderedEntities)
            val detected =
                if (fromEntities.isEmpty()) markup.extractMath(renderedText) else emptyList()
            eligibleMathSpans(
                renderedText,
                fromEntities + detected,
                renderedEntities,
                revealSpoilers
            )
        }
    }
    val displays = math.filter { span ->
        span.display && (
                '\n' in renderedText.substring(span.start, span.end) ||
                        renderedEntities.any { it.kind == "math" && it.url == "block" && it.offset == span.start }
                )
    }
    if (displays.isNotEmpty()) {
        Column(modifier) {
            var cursor = 0
            for (span in displays) {
                if (cursor < span.start) {
                    MathAwareText(
                        renderedText.substring(cursor, span.start),
                        sliceMathEntities(renderedEntities, cursor, span.start),
                        contentColor, linkColor, revealSpoilers, textStyle = sizedStyle,
                        onOpenStickerPack = onOpenStickerPack,
                        onSpoilerClick = onSpoilerClick,
                        scaleMessageText = false,
                    )
                }
                DisplayFormula(
                    span,
                    renderedText.substring(span.start, span.end),
                    contentColor,
                    sizedStyle
                )
                cursor = span.end
            }
            if (cursor < renderedText.length) {
                MathAwareText(
                    renderedText.substring(cursor),
                    sliceMathEntities(renderedEntities, cursor, renderedText.length),
                    contentColor, linkColor, revealSpoilers, textStyle = sizedStyle,
                    onOpenStickerPack = onOpenStickerPack,
                    onSpoilerClick = onSpoilerClick,
                    scaleMessageText = false,
                )
            }
        }
        return
    }
    MessageText(
        text = renderedText,
        entities = renderedEntities,
        contentColor = contentColor,
        linkColor = linkColor,
        revealSpoilers = revealSpoilers,
        onSpoilerClick = onSpoilerClick,
        selectable = selectable,
        selectAllNonce = selectAllNonce,
        onSelectedText = onSelectedText,
        onTextLayout = onTextLayout,
        onOpenStickerPack = onOpenStickerPack,
        mathSpans = math,
        modifier = modifier,
        textStyle = sizedStyle,
    )
}

internal fun mathSpansFromEntities(text: String, entities: List<TextEntity>): List<MathSpan> =
    entities.mapNotNull { entity ->
        if (entity.kind != "math") return@mapNotNull null
        val start = entity.offset
        val end = entity.offset + entity.length
        if (start < 0 || end > text.length || start >= end) return@mapNotNull null
        MathSpan(
            start = start,
            end = end,
            display = entity.url == "block",
            source = text.substring(start, end),
        )
    }

internal fun eligibleMathSpans(
    text: String,
    spans: List<MathSpan>,
    entities: List<TextEntity>,
    revealSpoilers: Boolean,
): List<MathSpan> {
    var previousEnd = 0
    return spans.sortedBy { it.start }.filter { span ->
        val valid = span.start >= previousEnd && span.start >= 0 && span.end > span.start &&
            span.end <= text.length && (span.display || !text.substring(span.start, span.end).contains('\n')) &&
            entities.none { entity ->
                val protected = entity.kind in mathProtectedEntities ||
                    (entity.kind == "spoiler" && !revealSpoilers)
                protected && entity.offset.toLong() < span.end &&
                    entity.offset.toLong() + entity.length > span.start
            }
        if (valid) previousEnd = span.end
        valid
    }.take(16)
}

private fun sliceMathEntities(entities: List<TextEntity>, start: Int, end: Int): List<TextEntity> =
    entities.mapNotNull { entity ->
        val left = maxOf(entity.offset.toLong(), start.toLong())
        val right = minOf(entity.offset.toLong() + entity.length, end.toLong())
        if (left >= right) null else entity.copy(offset = (left - start).toInt(), length = (right - left).toInt())
    }

@Composable
private fun DisplayFormula(span: MathSpan, fallback: String, color: Color, style: TextStyle) {
    val density = LocalDensity.current
    val textSize = with(density) { style.fontSize.toPx() }
    val maxWidth = with(density) { 240.dp.roundToPx() }
    val formulaKey = DialogRenderCache.formulaKey(span.source, true, color.toArgb(), textSize, maxWidth)
    val bitmap by produceState<ImageBitmap?>(DialogRenderCache.formula(formulaKey), formulaKey) {
        DialogRenderCache.formula(formulaKey)?.let {
            value = it
            return@produceState
        }
        val rendered = withContext(Dispatchers.Default) {
            renderLatexBitmap(span.source, true, color.toArgb(), textSize, maxWidth)
        }
        if (rendered != null) DialogRenderCache.putFormula(formulaKey, rendered)
        value = rendered
    }
    val rendered = bitmap
    if (rendered == null) {
        MessageText(fallback, emptyList(), color, color, true, textStyle = style)
    } else {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Image(rendered, contentDescription = span.source)
        }
    }
}

private val mathProtectedEntities = setOf(
    "code", "pre", "url", "text_url", "email", "mention", "text_mention", "custom_emoji",
)
