package org.monogram.presentation.features.chats.currentChat.components.inputbar

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.monogram.domain.repository.EditorSnippet
import org.monogram.presentation.R
import org.monogram.presentation.core.ui.ItemPosition
import org.monogram.presentation.features.chats.chatList.components.SettingsTextField

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullScreenEditorTemplatesSheet(
    visible: Boolean,
    currentText: String,
    snippets: List<EditorSnippet>,
    onDismiss: () -> Unit,
    onInsertSnippet: (String) -> Unit,
    onSaveCurrentAsSnippet: (String) -> Unit,
    onDeleteSnippet: (EditorSnippet) -> Unit
) {
    if (!visible) return
    var customTitle by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = stringResource(R.string.editor_templates),
                style = MaterialTheme.typography.titleMedium
            )

            SettingsTextField(
                value = customTitle,
                onValueChange = { customTitle = it },
                placeholder = stringResource(R.string.editor_snippet_title_hint),
                icon = Icons.Outlined.Edit,
                position = ItemPosition.STANDALONE,
                singleLine = true,
            )

            Button(
                onClick = {
                    val generated = customTitle.ifBlank {
                        currentText
                            .lineSequence()
                            .firstOrNull { it.isNotBlank() }
                            ?.take(32)
                            .orEmpty()
                    }
                    if (currentText.isNotBlank() && generated.isNotBlank()) {
                        onSaveCurrentAsSnippet(generated)
                        customTitle = ""
                    }
                },
                enabled = currentText.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = stringResource(R.string.editor_save_as_snippet))
            }

            if (snippets.isEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(
                        text = stringResource(R.string.editor_no_snippets),
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(snippets) { snippet ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Column(modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp)) {
                                Text(text = snippet.title, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = snippet.text,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 6.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    TextButton(onClick = { onInsertSnippet(snippet.text) }) {
                                        Text(text = stringResource(R.string.action_insert))
                                    }
                                    TextButton(onClick = { onDeleteSnippet(snippet) }) {
                                        Text(text = stringResource(R.string.action_delete_message))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
