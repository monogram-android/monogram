package org.monogram.presentation.features.chats.conversation.ui.inputbar

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.monogram.presentation.R
import org.monogram.presentation.core.ui.ItemPosition
import org.monogram.presentation.core.ui.SettingsTextField

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
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SettingsTextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = stringResource(R.string.editor_find),
                icon = Icons.Outlined.Search,
                position = ItemPosition.TOP,
                singleLine = true,
            )
            SettingsTextField(
                value = replacement,
                onValueChange = onReplacementChange,
                placeholder = stringResource(R.string.editor_replace),
                icon = Icons.Outlined.Search,
                position = ItemPosition.BOTTOM,
                singleLine = true,
            )

            val indicatorText = if (matchesCount > 0) {
                stringResource(
                    R.string.editor_matches_count,
                    (currentMatchIndex + 1).coerceAtMost(matchesCount),
                    matchesCount
                )
            } else {
                stringResource(R.string.editor_no_matches)
            }

            Text(
                text = indicatorText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TextButton(onClick = onClose) { Text(text = stringResource(R.string.action_close)) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = onPrev, enabled = matchesCount > 0) {
                        Text(text = stringResource(R.string.action_previous))
                    }
                    TextButton(onClick = onNext, enabled = matchesCount > 0) {
                        Text(text = stringResource(R.string.action_next))
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onReplace,
                    enabled = matchesCount > 0,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(text = stringResource(R.string.editor_replace))
                }
                Button(
                    onClick = onReplaceAll,
                    enabled = matchesCount > 0,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(text = stringResource(R.string.editor_replace_all))
                }
            }
        }
    }
}
