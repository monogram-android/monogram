package org.monogram.feature.dialog.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.monogram.core.models.ReplyButton
import org.monogram.core.models.ReplyButtonType
import org.monogram.core.models.ReplyMarkup
import org.monogram.core.models.ReplyMarkupKind

@Composable
internal fun BotKeyboardGrid(
    markup: ReplyMarkup,
    onClick: (ReplyButton) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = markup.kind == ReplyMarkupKind.Inline,
) {
    if (markup.rows.isEmpty()) return
    val minHeight = if (compact) 40.dp else 48.dp
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp),
    ) {
        markup.rows.forEach { row ->
            if (row.isEmpty()) return@forEach
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp),
            ) {
                row.forEach { button ->
                    val enabled = button.type != ReplyButtonType.Other && button.type != ReplyButtonType.Disabled
                    FilledTonalButton(
                        onClick = { onClick(button) },
                        enabled = enabled,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = minHeight)
                            .semantics { contentDescription = button.text },
                    ) {
                        Text(
                            text = button.text,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        }
    }
}
