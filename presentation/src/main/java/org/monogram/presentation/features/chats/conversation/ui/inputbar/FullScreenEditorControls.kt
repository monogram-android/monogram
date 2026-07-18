package org.monogram.presentation.features.chats.conversation.ui.inputbar

import android.widget.Toast
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Subject
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.FormatBold
import androidx.compose.material.icons.outlined.FormatClear
import androidx.compose.material.icons.outlined.FormatItalic
import androidx.compose.material.icons.outlined.FormatStrikethrough
import androidx.compose.material.icons.outlined.FormatUnderlined
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.monogram.presentation.R

@Composable
internal fun FullScreenEditorMetaPill(text: String, color: Color, contentColor: Color) {
    Surface(color = color, shape = RoundedCornerShape(999.dp)) {
        Text(
            text = text,
            color = contentColor,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

@Composable
internal fun FullScreenEditorToolButton(
    icon: ImageVector,
    hint: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .combinedClickable(
                enabled = enabled,
                onClick = onClick,
                onLongClick = { Toast.makeText(context, hint, Toast.LENGTH_SHORT).show() }
            )
            .padding(horizontal = 8.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = hint,
            tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(
                alpha = 0.38f
            )
        )
    }
}

@Composable
internal fun FullScreenEditorTools(
    hasSelection: Boolean,
    canCopy: Boolean,
    canCut: Boolean,
    canPaste: Boolean,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onPaste: () -> Unit,
    onBold: () -> Unit,
    onItalic: () -> Unit,
    onUnderline: () -> Unit,
    onStrike: () -> Unit,
    onSpoiler: () -> Unit,
    onCode: () -> Unit,
    onLink: () -> Unit,
    onMention: () -> Unit,
    onPre: () -> Unit,
    onClear: () -> Unit,
    onEmoji: () -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Surface(
            modifier = Modifier
                .weight(1f)
                .height(52.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                FullScreenEditorToolButton(
                    Icons.Outlined.ContentCopy,
                    stringResource(R.string.editor_action_copy),
                    canCopy,
                    onCopy
                )
                FullScreenEditorToolButton(
                    Icons.Outlined.ContentCut,
                    stringResource(R.string.editor_action_cut),
                    canCut,
                    onCut
                )
                FullScreenEditorToolButton(
                    Icons.Outlined.ContentPaste,
                    stringResource(R.string.editor_action_paste),
                    canPaste,
                    onPaste
                )
                FullScreenEditorToolButton(
                    Icons.Outlined.FormatBold,
                    stringResource(R.string.rich_text_bold),
                    hasSelection,
                    onBold
                )
                FullScreenEditorToolButton(
                    Icons.Outlined.FormatItalic,
                    stringResource(R.string.rich_text_italic),
                    hasSelection,
                    onItalic
                )
                FullScreenEditorToolButton(
                    Icons.Outlined.FormatUnderlined,
                    stringResource(R.string.rich_text_underline),
                    hasSelection,
                    onUnderline
                )
                FullScreenEditorToolButton(
                    Icons.Outlined.FormatStrikethrough,
                    stringResource(R.string.rich_text_strikethrough),
                    hasSelection,
                    onStrike
                )
                FullScreenEditorToolButton(
                    Icons.Outlined.VisibilityOff,
                    stringResource(R.string.rich_text_spoiler),
                    hasSelection,
                    onSpoiler
                )
                FullScreenEditorToolButton(
                    Icons.Outlined.Code,
                    stringResource(R.string.rich_text_code),
                    hasSelection,
                    onCode
                )
                FullScreenEditorToolButton(
                    Icons.Outlined.Link,
                    stringResource(R.string.rich_text_link),
                    hasSelection,
                    onLink
                )
                FullScreenEditorToolButton(
                    Icons.Outlined.AlternateEmail,
                    stringResource(R.string.rich_text_mention),
                    true,
                    onMention
                )
                FullScreenEditorToolButton(
                    Icons.AutoMirrored.Outlined.Subject,
                    stringResource(R.string.rich_text_pre),
                    hasSelection,
                    onPre
                )
                FullScreenEditorToolButton(
                    Icons.Outlined.FormatClear,
                    stringResource(R.string.rich_text_clear),
                    hasSelection,
                    onClear
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Surface(
            modifier = Modifier.size(52.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest
        ) {
            IconButton(onClick = onEmoji) {
                Text(
                    text = "☺",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
internal fun FullScreenEditorMarkupTools(
    mode: EditorParseMode,
    canCopy: Boolean,
    canCut: Boolean,
    canPaste: Boolean,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onPaste: () -> Unit,
    onBold: () -> Unit,
    onItalic: () -> Unit,
    onUnderline: () -> Unit,
    onStrike: () -> Unit,
    onSpoiler: () -> Unit,
    onCode: () -> Unit,
    onLink: () -> Unit,
    onQuote: () -> Unit,
    onPre: () -> Unit,
    onLatex: () -> Unit,
    onBlockLatex: () -> Unit,
    onHeading1: () -> Unit,
    onHeading2: () -> Unit,
    onHeading3: () -> Unit,
    onBulletList: () -> Unit,
    onNumberedList: () -> Unit,
    onDivider: () -> Unit,
    onTable: () -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Surface(
            modifier = Modifier
                .weight(1f)
                .height(52.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                FullScreenEditorToolButton(
                    Icons.Outlined.ContentCopy,
                    stringResource(R.string.editor_action_copy),
                    canCopy,
                    onCopy
                )
                FullScreenEditorToolButton(
                    Icons.Outlined.ContentCut,
                    stringResource(R.string.editor_action_cut),
                    canCut,
                    onCut
                )
                FullScreenEditorToolButton(
                    Icons.Outlined.ContentPaste,
                    stringResource(R.string.editor_action_paste),
                    canPaste,
                    onPaste
                )
                FullScreenEditorToolButton(
                    Icons.Outlined.FormatBold,
                    stringResource(R.string.rich_text_bold),
                    true,
                    onBold
                )
                FullScreenEditorToolButton(
                    Icons.Outlined.FormatItalic,
                    stringResource(R.string.rich_text_italic),
                    true,
                    onItalic
                )
                FullScreenEditorToolButton(
                    Icons.Outlined.FormatUnderlined,
                    stringResource(R.string.rich_text_underline),
                    true,
                    onUnderline
                )
                FullScreenEditorToolButton(
                    Icons.Outlined.FormatStrikethrough,
                    stringResource(R.string.rich_text_strikethrough),
                    true,
                    onStrike
                )
                FullScreenEditorToolButton(
                    Icons.Outlined.VisibilityOff,
                    stringResource(R.string.rich_text_spoiler),
                    true,
                    onSpoiler
                )
                FullScreenEditorToolButton(
                    Icons.Outlined.Code,
                    stringResource(R.string.rich_text_code),
                    true,
                    onCode
                )
                FullScreenEditorToolButton(
                    Icons.Outlined.Link,
                    stringResource(R.string.rich_text_link),
                    true,
                    onLink
                )
                FullScreenEditorToolButton(
                    Icons.AutoMirrored.Outlined.Subject,
                    stringResource(R.string.rich_text_blockquote),
                    true,
                    onQuote
                )
                FullScreenEditorToolButton(
                    Icons.AutoMirrored.Outlined.Subject,
                    if (mode == EditorParseMode.Markdown) stringResource(R.string.rich_text_pre) else stringResource(
                        R.string.editor_html_pre
                    ),
                    true,
                    onPre
                )
                FullScreenEditorTokenToolButton(
                    "H1",
                    stringResource(R.string.editor_heading_1),
                    true,
                    onHeading1
                )
                FullScreenEditorTokenToolButton(
                    "H2",
                    stringResource(R.string.editor_heading_2),
                    true,
                    onHeading2
                )
                FullScreenEditorTokenToolButton(
                    "H3",
                    stringResource(R.string.editor_heading_3),
                    true,
                    onHeading3
                )
                FullScreenEditorTokenToolButton(
                    "UL",
                    stringResource(R.string.editor_bulleted_list),
                    true,
                    onBulletList
                )
                FullScreenEditorTokenToolButton(
                    "OL",
                    stringResource(R.string.editor_numbered_list),
                    true,
                    onNumberedList
                )
                FullScreenEditorTokenToolButton(
                    "HR",
                    stringResource(R.string.editor_divider),
                    true,
                    onDivider
                )
                FullScreenEditorTokenToolButton(
                    "Tbl",
                    stringResource(R.string.editor_table),
                    true,
                    onTable
                )
            }
        }
    }
}

@Composable
internal fun FullScreenEditorTokenToolButton(
    label: String,
    hint: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .combinedClickable(
                enabled = enabled,
                onClick = onClick,
                onLongClick = { Toast.makeText(context, hint, Toast.LENGTH_SHORT).show() }
            )
            .padding(horizontal = 8.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(
                alpha = 0.38f
            )
        )
    }
}
