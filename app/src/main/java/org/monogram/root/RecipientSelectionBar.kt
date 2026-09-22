package org.monogram.root

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.monogram.R

@Composable
internal fun RecipientSelectionBar(component: RecipientPickerComponent, state: RecipientPickerState) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val count = if (component.request.forwarding) component.request.messageIds.size
                else component.request.share?.attachments?.size ?: 0
            if (count > 0) {
                Text(
                    stringResource(if (component.request.forwarding) R.string.recipient_messages else R.string.recipient_files, count),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (component.request.forwarding) {
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
                        value = state.dropAuthor, enabled = !state.started, role = Role.Switch,
                        onValueChange = component::onDropAuthor,
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.recipient_hide_author), Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = state.dropAuthor, onCheckedChange = null, enabled = !state.started,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                            uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                            uncheckedBorderColor = MaterialTheme.colorScheme.outline,
                        ),
                    )
                }
            }
            OutlinedTextField(
                value = state.comment, onValueChange = component::onComment,
                enabled = !state.started,
                placeholder = { Text(stringResource(R.string.recipient_comment)) },
                maxLines = 3, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                shape = RoundedCornerShape(28.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                ),
            )
            if (state.interrupted || state.error) {
                Text(
                    when {
                        state.interrupted -> stringResource(R.string.recipient_interrupted)
                        !state.errorMessage.isNullOrBlank() -> state.errorMessage
                        else -> stringResource(R.string.recipient_error)
                    },
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                )
            }
            Button(
                onClick = component::onSend,
                enabled = !state.sending && !state.interrupted && state.selected.isNotEmpty() &&
                    (component.request.forwarding || !component.request.share?.attachments.isNullOrEmpty() || state.comment.isNotBlank()),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                    disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                ),
            ) {
                Text(when {
                    state.sending -> stringResource(R.string.recipient_sending, state.completed.size, state.selected.size)
                    else -> stringResource(R.string.recipient_send, state.selected.size)
                })
            }
        }
    }
}
