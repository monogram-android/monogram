package org.monogram.feature.dialog.scheduled

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import org.monogram.core.models.ScheduledMessage
import org.monogram.core.models.TextEntity
import org.monogram.core.models.TextEntities
import org.monogram.core.models.remapTextEntities
import org.monogram.core.ui.components.AppStatusBanner
import org.monogram.core.ui.components.AppSyncStatus
import org.monogram.feature.dialog.R
import org.monogram.feature.dialog.ui.MarkdownEditorScreen

internal data class QueueTextEditSession(
    val id: Int, val fingerprint: String, val text: String, val entitiesJson: String?,
    val submittedText: String? = null, val submittedEntitiesJson: String? = null,
) {
    companion object {
        fun capture(original: ScheduledMessage) = QueueTextEditSession(
            original.message.id.id, fingerprint(original),
            original.message.text.orEmpty(), TextEntities.serialize(original.message.entities)
        )

        // A restored draft must not rebase onto a different queue snapshot.
        fun fingerprint(original: ScheduledMessage): String =
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(original.toString().toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        val saver = Saver<QueueTextEditSession?, Any>(
            save = { session ->
                session?.let {
                    listOf(
                        it.id,
                        it.fingerprint,
                        it.text,
                        it.entitiesJson.orEmpty(),
                        it.submittedText != null,
                        it.submittedText.orEmpty(),
                        it.submittedEntitiesJson.orEmpty()
                    )
                }
            },
            restore = { raw ->
                runCatching {
                    val parts = raw as List<*>
                    QueueTextEditSession(
                        parts[0] as Int, parts[1] as String, parts[2] as String, parts[3] as String,
                        if (parts[4] as Boolean) parts[5] as String else null, parts[6] as String
                    )
                }.getOrNull()
            },
        )
    }
}

@Composable
internal fun ScheduledQueueTextEditor(
    session: QueueTextEditSession,
    original: ScheduledMessage?,
    state: ScheduledQueueStore.State,
    unchanged: Boolean,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
    onDraftChange: () -> Unit,
    onApply: (String, List<TextEntity>) -> Unit,
) {
    var value by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(
            TextFieldValue(
                session.text
            )
        )
    }
    var entityJson by rememberSaveable { mutableStateOf(session.entitiesJson) }
    val entities = TextEntities.parse(entityJson)
    val enabled =
        unchanged && original?.canEditText == true && !state.fromCache && !state.needsRefresh &&
                state.mutatingId == null && session.id !in state.uncertainIds &&
                (original.message.mediaKind != null || value.text.isNotBlank()) &&
                entities.all { it.offset >= 0 && it.length > 0 && it.offset <= value.text.length - it.length }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            AppStatusBanner(
                sync = when {
                    state.loading -> AppSyncStatus.Syncing
                    state.fromCache -> AppSyncStatus.FromCache
                    else -> AppSyncStatus.Hidden
                },
                error = state.error, onRetry = onRefresh,
            )
            if (!unchanged && !state.fromCache && !state.loading) {
                Text(
                    stringResource(R.string.dialog_scheduled_edit_changed),
                    Modifier.padding(16.dp)
                )
            }
            MarkdownEditorScreen(
                value = value,
                onValueChange = { next ->
                    if (next.text != value.text) onDraftChange()
                    entityJson =
                        TextEntities.serialize(remapTextEntities(value.text, next.text, entities))
                    value = next
                },
                entities = entities,
                onEntitiesChange = { onDraftChange(); entityJson = TextEntities.serialize(it) },
                applyEnabled = enabled,
                onApply = { if (enabled) onApply(value.text, entities) },
                onDismiss = onDismiss,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
