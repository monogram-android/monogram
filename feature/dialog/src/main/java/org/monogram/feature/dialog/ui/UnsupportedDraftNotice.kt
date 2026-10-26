package org.monogram.feature.dialog.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.monogram.core.ui.components.AppStatusBanner
import org.monogram.feature.dialog.R

@Composable
internal fun UnsupportedDraftNotice(text: String, onDiscard: () -> Unit) {
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column {
            AppStatusBanner(
                text = stringResource(R.string.dialog_draft_unsupported),
                failed = false,
                progress = false,
                maxLines = Int.MAX_VALUE,
            )
            if (text.isNotBlank()) SelectionContainer {
                Text(
                    text, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    maxLines = 4, overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(
                onClick = { confirmDiscard = true },
                modifier = Modifier.align(Alignment.End)
            ) {
                Text(stringResource(R.string.dialog_discard_draft))
            }
        }
    }
    if (confirmDiscard) AlertDialog(
        onDismissRequest = { confirmDiscard = false },
        title = { Text(stringResource(R.string.dialog_discard_draft)) },
        text = { Text(stringResource(R.string.dialog_discard_draft_confirm)) },
        confirmButton = {
            TextButton(onClick = {
                confirmDiscard = false
                onDiscard()
            }) {
                Text(
                    stringResource(R.string.dialog_discard_draft),
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        dismissButton = {
            TextButton(onClick = {
                confirmDiscard = false
            }) { Text(stringResource(R.string.dialog_cancel)) }
        },
    )
}