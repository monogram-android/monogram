package org.monogram.presentation.features.chats.conversation.ui.inputbar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.monogram.presentation.R

@Composable
fun FullScreenEditorFindReplaceBar(
    query: String,
    replacement: String,
    matchesCount: Int,
    currentMatchIndex: Int,
    onQueryChange: (String) -> Unit,
    onReplacementChange: (String) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onReplace: () -> Unit,
    onReplaceAll: () -> Unit,
    onClose: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.editor_find),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.weight(1f))
                FindReplaceIconButton(
                    icon = Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.action_close),
                    onClick = onClose
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                CompactFindTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    placeholder = stringResource(R.string.editor_find),
                    icon = Icons.Outlined.Search,
                    shape = RoundedCornerShape(
                        topStart = 22.dp,
                        topEnd = 22.dp,
                        bottomStart = 4.dp,
                        bottomEnd = 4.dp
                    )
                )
                CompactFindTextField(
                    value = replacement,
                    onValueChange = onReplacementChange,
                    placeholder = stringResource(R.string.editor_replace),
                    icon = Icons.Outlined.TextFields,
                    shape = RoundedCornerShape(
                        bottomStart = 22.dp,
                        bottomEnd = 22.dp,
                        topStart = 4.dp,
                        topEnd = 4.dp
                    )
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(
                        text = if (matchesCount > 0) {
                            stringResource(
                                R.string.editor_matches_count,
                                (currentMatchIndex + 1).coerceAtMost(matchesCount),
                                matchesCount
                            )
                        } else {
                            stringResource(R.string.editor_no_matches)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FindReplaceIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.action_previous),
                        enabled = matchesCount > 0,
                        onClick = onPrev
                    )
                    FindReplaceIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = stringResource(R.string.action_next),
                        enabled = matchesCount > 0,
                        onClick = onNext
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onReplace,
                    enabled = matchesCount > 0,
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(
                        text = stringResource(R.string.editor_replace),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
                Button(
                    onClick = onReplaceAll,
                    enabled = matchesCount > 0,
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(
                        text = stringResource(R.string.editor_replace_all),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactFindTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    icon: ImageVector,
    shape: RoundedCornerShape
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = shape
    ) {
        TextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            textStyle = MaterialTheme.typography.bodyLarge,
            placeholder = {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.bodyLarge
                )
            },
            singleLine = true,
            leadingIcon = {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent
            )
        )
    }
}

@Composable
private fun FindReplaceIconButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val containerColor = if (enabled) {
        MaterialTheme.colorScheme.surfaceContainerHighest
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.55f)
    }
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
    }

    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        color = containerColor
    ) {
        Box(
            modifier = Modifier.size(36.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = contentColor,
                modifier = Modifier.size(17.dp)
            )
        }
    }
}
