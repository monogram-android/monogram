package org.monogram.feature.dialog.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.monogram.core.models.InstantViewBlock

internal object IvSpacing {
    val Gutter = 18.dp
    val BarHeight = 64.dp
    val ReadableWidth = 720.dp
    val BlockGap = 14.dp
    val SectionGap = 12.dp
    val CardCorner = 16.dp
    val ProgressHeight = 3.dp
    val DividerHeight = 18.dp
    val QuoteRuleWidth = 3.dp
    val QuoteIndent = 14.dp
    val PreHorizontalPadding = 16.dp
    val PreVerticalPadding = 12.dp
}

internal const val IV_BASE_FONT_SIZE = 16f

internal fun ivScale(fontSize: Int): Float = fontSize / IV_BASE_FONT_SIZE

internal fun ivSectionGap(block: InstantViewBlock): Dp = when (block) {
    is InstantViewBlock.Text -> when (block.kind) {
        "title", "heading", "subtitle", "kicker" -> IvSpacing.SectionGap
        else -> 0.dp
    }

    is InstantViewBlock.Related, is InstantViewBlock.Channel -> IvSpacing.SectionGap
    InstantViewBlock.Divider -> 2.dp
    else -> 0.dp
}

@Composable
internal fun ivTextStyle(kind: String, level: Int, scale: Float): TextStyle {
    val typography = MaterialTheme.typography
    fun size(base: Float) = (base * scale).sp
    fun body(base: Float, factor: Float = 1.45f) = typography.bodyLarge.copy(
        fontSize = size(base),
        lineHeight = size(base * factor),
    )

    fun heading(base: Float) = typography.titleLarge.copy(
        fontSize = size(base),
        lineHeight = size(base * 1.3f),
        fontWeight = FontWeight.SemiBold,
    )
    return when (kind) {
        "title" -> typography.headlineMedium.copy(
            fontSize = size(23f),
            lineHeight = size(29f),
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.sp,
        )

        "heading" -> when (level.coerceIn(1, 6)) {
            1 -> heading(20f)
            2 -> heading(17f)
            3 -> heading(15f)
            4 -> heading(14f)
            5 -> heading(13f)
            else -> heading(12f)
        }

        "subtitle" -> body(20f, factor = 1.35f)
        "kicker" -> typography.labelLarge.copy(
            fontSize = size(14f),
            lineHeight = size(19f),
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.5.sp,
        )

        "authorDate" -> typography.labelLarge.copy(fontSize = size(14f), lineHeight = size(19f))
        "pre" -> body(14f, factor = 1.35f).copy(fontFamily = FontFamily.Monospace)
        "footer" -> body(14f, factor = 1.4f)
        "thinking" -> body(16f).copy(fontStyle = FontStyle.Italic)
        else -> body(16f)
    }
}

@Composable
internal fun ivTextColor(kind: String): Color? = when (kind) {
    "kicker", "authorDate", "footer", "subtitle", "thinking" -> MaterialTheme.colorScheme.onSurfaceVariant
    else -> null
}

@Composable
internal fun ivBodyStyle(scale: Float, size: Float = IV_BASE_FONT_SIZE): TextStyle =
    MaterialTheme.typography.bodyLarge.copy(
        fontSize = (size * scale).sp,
        lineHeight = (size * 1.45f * scale).sp,
    )

@Composable
internal fun ivQuoteStyle(scale: Float): TextStyle = ivBodyStyle(scale, size = 15f)

@Composable
internal fun ivCaptionStyle(scale: Float): TextStyle = ivBodyStyle(scale, size = 14f)

@Composable
internal fun ivCreditStyle(scale: Float): TextStyle =
    ivBodyStyle(scale, size = 12f).copy(fontStyle = FontStyle.Italic)

@Composable
internal fun ivSearchHighlight(): Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.26f)

@Composable
internal fun ivCardColor(): Color = MaterialTheme.colorScheme.surfaceContainerHigh

@Composable
internal fun ivCodeBackground(): Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
