package org.monogram.feature.dialog.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.monogram.core.markup.MathSpan
import org.monogram.core.models.TextEntity

@Composable
internal fun MessageText(
    text: String,
    entities: List<TextEntity>,
    contentColor: Color,
    linkColor: Color,
    revealSpoilers: Boolean,
    onSpoilerClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    selectable: Boolean = false,
    selectAllNonce: Int = 0,
    onSelectedText: (String) -> Unit = {},
    onTextLayout: (TextLayoutResult) -> Unit = {},
    onOpenStickerPack: ((Long) -> Unit)? = null,
    mathSpans: List<MathSpan> = emptyList(),
) {
    val density = LocalDensity.current
    val fontSizePx = with(density) { textStyle.fontSize.toPx() }
    val maxMathWidthPx = with(density) { 240.dp.roundToPx() }
    val loadedFormulaImages by produceState<Map<MathSpan, ImageBitmap>>(
        mathSpans.mapNotNull { span ->
            DialogRenderCache.formula(
                DialogRenderCache.formulaKey(
                    span.source, span.display, contentColor.toArgb(), fontSizePx, maxMathWidthPx,
                ),
            )?.let { span to it }
        }.toMap(),
        mathSpans, contentColor, fontSizePx, maxMathWidthPx,
    ) {
        value = withContext(Dispatchers.Default) {
            buildMap {
                for (span in mathSpans) {
                    ensureActive()
                    val key = DialogRenderCache.formulaKey(
                        span.source, span.display, contentColor.toArgb(), fontSizePx, maxMathWidthPx,
                    )
                    val cached = DialogRenderCache.formula(key)
                    if (cached != null) {
                        put(span, cached)
                        continue
                    }
                    renderLatexBitmap(
                        span.source, span.display, contentColor.toArgb(), fontSizePx, maxMathWidthPx,
                    )?.let {
                        DialogRenderCache.putFormula(key, it)
                        put(span, it)
                    }
                }
            }
        }
    }
    val formulaImages = loadedFormulaImages.filterKeys { it in mathSpans }
    // A hidden spoiler reveals on tap, so it is handled as a link inside the text. The listener is
    // remembered and reads the latest lambda, so the annotated string only rebuilds on real changes.
    val latestSpoilerClick = androidx.compose.runtime.rememberUpdatedState(onSpoilerClick)
    val spoilerClick: (() -> Unit)? = if (onSpoilerClick != null) {
        remember { { latestSpoilerClick.value?.invoke() ?: Unit } }
    } else {
        null
    }
    val renderedText = remember(text, entities, linkColor, revealSpoilers, formulaImages, spoilerClick != null) {
        buildAnnotatedString {
            appendWithCustomEmoji(text, entities)
            formulaImages.keys.forEach { span ->
                val inline = buildAnnotatedString {
                    appendInlineContent("math:${span.start}", text.substring(span.start, span.end))
                }
                inline.getStringAnnotations(0, inline.length).forEach { annotation ->
                    addStringAnnotation(annotation.tag, annotation.item, span.start, span.end)
                }
            }
            applyMessageEntities(
                text = text,
                entities = entities,
                linkColor = linkColor,
                revealSpoilers = revealSpoilers,
                onSpoilerClick = spoilerClick,
            )
        }
    }
    val emojiInline = remember(text, entities, onOpenStickerPack) {
        customEmojiInlineMap(text, entities, onOpenStickerPack)
    }
    val formulaInline = remember(formulaImages, density) {
        formulaImages.entries.associate { (span, bitmap) ->
            "math:${span.start}" to InlineTextContent(
                Placeholder(
                    with(density) { bitmap.width.toSp() },
                    with(density) { bitmap.height.toSp() },
                    PlaceholderVerticalAlign.TextCenter,
                ),
            ) {
                Image(bitmap = bitmap, contentDescription = span.source)
            }
        }
    }
    if (selectable) {
        val focus = remember { FocusRequester() }
        val selectionColors = TextSelectionColors(
            handleColor = MaterialTheme.colorScheme.primary,
            backgroundColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
        )
        var field by remember(renderedText) {
            mutableStateOf(
                TextFieldValue(
                    annotatedString = renderedText,
                    selection = TextRange(0, renderedText.length),
                ),
            )
        }
        LaunchedEffect(Unit) {
            runCatching { focus.requestFocus() }
            onSelectedText(field.text)
        }
        LaunchedEffect(selectAllNonce) {
            if (selectAllNonce == 0) return@LaunchedEffect
            field = field.copy(selection = TextRange(0, field.text.length))
            onSelectedText(field.text)
        }
        androidx.compose.runtime.CompositionLocalProvider(
            LocalTextSelectionColors provides selectionColors,
        ) {
            BasicTextField(
                value = field,
                onValueChange = {
                    field = it
                    val lo = it.selection.min.coerceIn(0, it.text.length)
                    val hi = it.selection.max.coerceIn(lo, it.text.length)
                    onSelectedText(it.text.substring(lo, hi))
                },
                readOnly = true,
                textStyle = textStyle.copy(color = contentColor),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                onTextLayout = onTextLayout,
                modifier = modifier.focusRequester(focus),
            )
        }
    } else {
        Text(
            text = renderedText,
            style = textStyle,
            color = contentColor,
            inlineContent = emojiInline + formulaInline,
            onTextLayout = onTextLayout,
            modifier = modifier,
        )
    }
}
