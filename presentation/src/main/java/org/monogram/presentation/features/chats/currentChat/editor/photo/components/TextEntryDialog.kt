package org.monogram.presentation.features.chats.currentChat.editor.photo.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.monogram.presentation.R

@Composable
fun TextEntryDialog(
    initialText: String,
    initialColor: Color,
    onDismiss: () -> Unit,
    onConfirm: (String, Color) -> Unit,
    onDelete: () -> Unit
) {
    var text by remember { mutableStateOf(initialText) }
    var color by remember { mutableStateOf(initialColor) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(if (initialText.isEmpty()) R.string.photo_editor_action_add_text else R.string.photo_editor_action_edit_text),
                        style = MaterialTheme.typography.headlineSmall
                    )
                    if (initialText.isNotEmpty()) {
                        IconButton(onClick = onDelete) {
                            Icon(
                                Icons.Rounded.Delete,
                                contentDescription = stringResource(R.string.photo_editor_action_delete),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))

                TextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(
                        color = color,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    placeholder = { Text(stringResource(R.string.photo_editor_label_placeholder_text)) },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = color.copy(alpha = 0.5f),
                        unfocusedIndicatorColor = color.copy(alpha = 0.2f)
                    )
                )

                Spacer(Modifier.height(24.dp))

                ColorSelector(selectedColor = color, onSelect = { color = it })

                Spacer(Modifier.height(32.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Text(stringResource(R.string.photo_editor_action_cancel))
                    }
                    Button(
                        onClick = { if (text.isNotBlank()) onConfirm(text, color) },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(stringResource(R.string.photo_editor_action_apply))
                    }
                }
            }
        }
    }
}
