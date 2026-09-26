package org.monogram.feature.dialog.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.monogram.core.markup.MathSpan
import org.monogram.core.models.TextEntity
import org.monogram.core.ui.AppearanceSettings
import org.monogram.core.ui.theme.scaledToMessageSize

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
    val markup = LocalMarkupParser.current
    val math = remember(markup, text, entities, revealSpoilers, selectable) {
        if (selectable) emptyList() else eligibleMathSpans(
            text, markup.extractMath(text), entities, revealSpoilers,
        )
    }
    val displays = math.filter { it.display && '\n' in text.substring(it.start, it.end) }
    if (displays.isNotEmpty()) {
        Column(modifier) {
            var cursor = 0
            for (span in displays) {
                if (cursor < span.start) {
                    MathAwareText(
                        text.substring(cursor, span.start), sliceMathEntities(entities, cursor, span.start),
                        contentColor, linkColor, revealSpoilers, textStyle = sizedStyle,
                        onOpenStickerPack = onOpenStickerPack,
                        onSpoilerClick = onSpoilerClick,
                        scaleMessageText = false,
                    )
                }
                DisplayFormula(span, text.substring(span.start, span.end), contentColor, sizedStyle)
                cursor = span.end
            }
            if (cursor < text.length) {
                MathAwareText(
                    text.substring(cursor), sliceMathEntities(entities, cursor, text.length),
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
        text = text,
        entities = entities,
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
