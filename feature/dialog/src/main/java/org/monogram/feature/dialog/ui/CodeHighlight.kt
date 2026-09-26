package org.monogram.feature.dialog.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import org.monogram.core.markup.CodeHighlight

internal fun highlightScopeColor(
    scope: String,
    onSurface: Color,
    primary: Color,
    secondary: Color,
    tertiary: Color,
    onSurfaceVariant: Color,
): Color = when {
    scope.startsWith("keyword") -> primary
    scope.startsWith("string") -> tertiary
    scope.startsWith("comment") -> onSurfaceVariant
    scope.startsWith("function") -> secondary
    scope.startsWith("type") -> secondary
    scope == "number" || scope.startsWith("constant") -> primary
    scope.startsWith("attribute") -> tertiary
    else -> onSurface
}

internal fun highlightToAnnotatedString(
    code: String,
    spans: List<CodeHighlight>,
    colorForScope: (String) -> Color,
): AnnotatedString = buildAnnotatedString {
    append(code)
    val len = code.length
    for (span in spans) {
        val start = span.start.coerceIn(0, len)
        val end = span.end.coerceIn(start, len)
        if (start >= end) continue
        val color = colorForScope(span.scope)
        val style = when {
            span.scope.startsWith("keyword") -> SpanStyle(color = color, fontWeight = FontWeight.Bold)
            span.scope.startsWith("comment") -> SpanStyle(color = color, fontStyle = FontStyle.Italic)
            else -> SpanStyle(color = color)
        }
        addStyle(style, start, end)
    }
}
