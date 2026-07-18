package org.monogram.presentation.features.chats.conversation.ui.inputbar

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.outlined.Subject
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material.icons.outlined.ZoomOut
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.monogram.presentation.R

@Composable
internal fun FullScreenEditorHeader(
    isOverLimit: Boolean,
    isSending: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    onDismiss: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSend: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FullScreenEditorToolbarActionButton(
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(R.string.action_cancel),
            onClick = onDismiss
        )

        Box(modifier = Modifier.weight(1f))

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FullScreenEditorToolbarActionButton(
                icon = Icons.AutoMirrored.Filled.Undo,
                contentDescription = stringResource(R.string.editor_undo),
                enabled = canUndo,
                onClick = onUndo
            )
            FullScreenEditorToolbarActionButton(
                icon = Icons.AutoMirrored.Filled.Redo,
                contentDescription = stringResource(R.string.editor_redo),
                enabled = canRedo,
                onClick = onRedo
            )
            FullScreenEditorToolbarActionButton(
                icon = Icons.Filled.Check,
                contentDescription = stringResource(R.string.action_send),
                enabled = !isOverLimit && !isSending,
                selected = !isOverLimit && !isSending,
                containerColor = if (isOverLimit || isSending) {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                } else {
                    MaterialTheme.colorScheme.primary
                },
                contentColor = if (isOverLimit || isSending) {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                } else {
                    MaterialTheme.colorScheme.onPrimary
                },
                onClick = onSend
            )
        }
    }
}

@Composable
internal fun FullScreenEditorTopActions(
    isPreviewMode: Boolean,
    parseMode: EditorParseMode,
    showFindReplace: Boolean,
    fontScale: Float,
    showAiAction: Boolean,
    onTogglePreview: () -> Unit,
    onParseModeChange: (EditorParseMode) -> Unit,
    onToggleFindReplace: () -> Unit,
    onTemplatesClick: () -> Unit,
    onAiClick: () -> Unit,
    onZoomOut: () -> Unit,
    onZoomIn: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 6.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showAiAction) {
                FullScreenEditorToolbarActionButton(
                    icon = Icons.Outlined.AutoAwesome,
                    contentDescription = stringResource(R.string.editor_ai),
                    onClick = onAiClick
                )
            }
            FullScreenEditorToolbarActionButton(
                icon = if (isPreviewMode) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                contentDescription = if (isPreviewMode) {
                    stringResource(R.string.editor_mode_edit)
                } else {
                    stringResource(R.string.editor_mode_preview)
                },
                selected = isPreviewMode,
                onClick = onTogglePreview
            )
            FullScreenEditorParseModeSelector(
                parseMode = parseMode,
                onParseModeChange = onParseModeChange
            )
            FullScreenEditorToolbarActionButton(
                icon = Icons.Outlined.Search,
                contentDescription = if (showFindReplace) {
                    stringResource(R.string.action_close)
                } else {
                    stringResource(R.string.editor_find)
                },
                selected = showFindReplace,
                onClick = onToggleFindReplace
            )
            FullScreenEditorToolbarActionButton(
                icon = Icons.Outlined.Description,
                contentDescription = stringResource(R.string.editor_templates),
                onClick = onTemplatesClick
            )
            FullScreenEditorToolbarActionButton(
                icon = Icons.Outlined.ZoomOut,
                contentDescription = stringResource(R.string.editor_zoom_out),
                enabled = fontScale > 0.8f,
                onClick = onZoomOut
            )
            FullScreenEditorToolbarActionButton(
                icon = Icons.Outlined.ZoomIn,
                contentDescription = stringResource(R.string.editor_zoom_in),
                enabled = fontScale < 1.6f,
                onClick = onZoomIn
            )
        }
    }
}

@Composable
internal fun FullScreenEditorParseModeSelector(
    parseMode: EditorParseMode,
    onParseModeChange: (EditorParseMode) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val currentLabel = when (parseMode) {
        EditorParseMode.Plain -> stringResource(R.string.editor_parse_mode_plain)
        EditorParseMode.Markdown -> stringResource(R.string.editor_parse_mode_markdown)
        EditorParseMode.Html -> stringResource(R.string.editor_parse_mode_html)
    }
    val currentIcon = when (parseMode) {
        EditorParseMode.Plain -> Icons.Outlined.TextFields
        EditorParseMode.Markdown -> Icons.AutoMirrored.Outlined.Subject
        EditorParseMode.Html -> Icons.Outlined.Code
    }

    Box {
        Surface(
            modifier = Modifier.height(42.dp),
            onClick = { expanded = true },
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = currentIcon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = currentLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Icon(
                    imageVector = Icons.Outlined.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            EditorParseMode.entries.forEach { mode ->
                val selected = mode == parseMode
                DropdownMenuItem(
                    text = {
                        Text(
                            text = when (mode) {
                                EditorParseMode.Plain -> stringResource(R.string.editor_parse_mode_plain)
                                EditorParseMode.Markdown -> stringResource(R.string.editor_parse_mode_markdown)
                                EditorParseMode.Html -> stringResource(R.string.editor_parse_mode_html)
                            }
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = when (mode) {
                                EditorParseMode.Plain -> Icons.Outlined.TextFields
                                EditorParseMode.Markdown -> Icons.AutoMirrored.Outlined.Subject
                                EditorParseMode.Html -> Icons.Outlined.Code
                            },
                            contentDescription = null
                        )
                    },
                    trailingIcon = if (selected) {
                        {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null
                            )
                        }
                    } else null,
                    onClick = {
                        onParseModeChange(mode)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
internal fun FullScreenEditorToolbarActionButton(
    icon: ImageVector,
    contentDescription: String,
    selected: Boolean = false,
    enabled: Boolean = true,
    containerColor: Color? = null,
    contentColor: Color? = null,
    onClick: () -> Unit
) {
    val resolvedContainerColor = containerColor ?: when {
        !enabled -> MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.55f)
        selected -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val resolvedContentColor = contentColor ?: when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        selected -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        color = resolvedContainerColor
    ) {
        Box(
            modifier = Modifier.size(42.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = resolvedContentColor,
                modifier = Modifier.size(19.dp)
            )
        }
    }
}
