package org.monogram.feature.dialog.scheduled

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.monogram.core.models.ScheduledMessage
import org.monogram.core.models.TextEntities
import org.monogram.core.models.TextEntity
import org.monogram.core.ui.components.AppStatusBanner
import org.monogram.core.ui.components.AppSyncStatus
import org.monogram.core.ui.loading.MonogramBusyIconButton
import org.monogram.core.ui.menu.AppMenuItem
import org.monogram.core.ui.menu.AppMenuPopup
import org.monogram.core.ui.menu.AppMenuSurface
import org.monogram.feature.dialog.R
import java.text.DateFormat
import java.util.Date

@Composable
fun ScheduledQueueContent(component: ScheduledQueueComponent) {
    val state by component.state.collectAsStateWithLifecycle()
    ScheduledQueueScreen(
        state,
        component.onBack,
        component::onRefresh,
        component::onSendNow,
        component::onCancel,
        component::onReschedule,
        component::onEdit,
        onPreview = if (component.mediaRepository != null) component::onPreview else null,
        onClosePreview = component::onClosePreview,
        onRetryPreview = component::onRetryPreview,
        mediaRepository = component.mediaRepository
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScheduledQueueScreen(
    state: ScheduledQueueStore.State,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSendNow: ((Int) -> Unit)? = null,
    onCancel: ((Int) -> Unit)? = null,
    onReschedule: ((Int, Int) -> Unit)? = null,
    onEdit: ((ScheduledMessage, String, List<TextEntity>) -> Unit)? = null,
    onPreview: ((Int) -> Unit)? = null,
    onClosePreview: (() -> Unit)? = null,
    onRetryPreview: (() -> Unit)? = null,
    mediaRepository: org.monogram.network.http.MediaRepository? = null,
) {
    var editing by rememberSaveable(stateSaver = QueueTextEditSession.saver) {
        mutableStateOf(
            null
        )
    }
    val session = editing
    if (session != null) {
        val original = state.messages.firstOrNull { it.message.id.id == session.id }
        val unchanged =
            original != null && QueueTextEditSession.fingerprint(original) == session.fingerprint
        LaunchedEffect(
            session,
            original,
            state.loading,
            state.fromCache,
            state.needsRefresh,
            state.mutatingId,
            state.error
        ) {
            if (session.submittedText != null && original != null && !state.loading && !state.fromCache && !state.needsRefresh && state.mutatingId == null && state.error == null && original.message.text.orEmpty() == session.submittedText && original.message.entities.toSet() == TextEntities.parse(
                    session.submittedEntitiesJson
                ).toSet()
            ) {
                editing = null
            }
        }
        ScheduledQueueTextEditor(
            session,
            original,
            state,
            unchanged,
            onRefresh,
            { editing = null },
            {
                editing = editing?.copy(submittedText = null, submittedEntitiesJson = null)
            }) { text, entities ->
            if (unchanged && original != null) {
                editing = session.copy(
                    submittedText = text, submittedEntitiesJson = TextEntities.serialize(entities)
                )
                onEdit?.invoke(original, text, entities)
            }
        }
        return
    }
    var actionId by rememberSaveable { mutableStateOf<Int?>(null) }
    var sendAction by rememberSaveable { mutableStateOf(false) }
    var rescheduleId by rememberSaveable { mutableStateOf<Int?>(null) }
    val rescheduled = state.messages.firstOrNull { it.message.id.id == rescheduleId }
    LaunchedEffect(rescheduled) { if (rescheduled == null) rescheduleId = null }
    val selected = state.messages.firstOrNull { it.message.id.id == actionId }
    LaunchedEffect(selected) { if (selected == null) actionId = null }
    val dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.dialog_scheduled_messages),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            stringResource(R.string.dialog_back)
                        )
                    }
                },
                actions = {
                    MonogramBusyIconButton(
                        onClick = onRefresh,
                        busy = state.loading || state.mutatingId != null,
                        status = stringResource(R.string.dialog_refresh),
                        icon = {
                            Icon(Icons.Outlined.Refresh, stringResource(R.string.dialog_refresh))
                        })
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            AppStatusBanner(
                sync = when {
                    state.loading || state.mutatingId != null -> AppSyncStatus.Syncing; state.fromCache -> AppSyncStatus.FromCache; else -> AppSyncStatus.Hidden
                },
                error = state.error, onRetry = onRefresh,
            )
            if (state.needsRefresh && state.error == null && !state.loading) {
                TextButton(onClick = onRefresh, modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text(stringResource(R.string.dialog_refresh))
                }
            }
            if (state.uncertainIds.isNotEmpty()) {
                Text(
                    stringResource(R.string.dialog_scheduled_check_result),
                    Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
                if (state.messages.isEmpty() && !state.loading && state.error == null) item {
                    Text(
                        stringResource(R.string.dialog_scheduled_empty),
                        modifier = Modifier.padding(24.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                items(state.messages, key = { it.message.id.id }) { scheduled ->
                    val message = scheduled.message
                    val id = message.id.id
                    ListItem(
                        leadingContent = if (scheduled.canPreviewMedia && onPreview != null) ({
                            ScheduledQueueMediaThumb(scheduled, mediaRepository) { onPreview(id) }
                        }) else null,
                        headlineContent = {
                            Text(
                                message.text?.takeIf(String::isNotBlank)
                                    ?: if (scheduled.canPreviewMedia) scheduledMediaLabel(scheduled) else stringResource(
                                        R.string.dialog_message_hint
                                    ), maxLines = 3, overflow = TextOverflow.Ellipsis
                            )
                        },
                        supportingContent = {
                            Text(
                                if (message.date == 0x7ffffffeL) stringResource(R.string.dialog_schedule_when_online)
                                else dateFormat.format(Date(message.date * 1000))
                            )
                        },
                        trailingContent = if (onSendNow != null && onCancel != null) ({
                            var menu by remember { mutableStateOf(false) }
                            Box {
                                IconButton(
                                    onClick = { menu = true },
                                    enabled = !state.fromCache && !state.needsRefresh && state.mutatingId == null && id !in state.uncertainIds
                                ) {
                                    Icon(
                                        Icons.Outlined.MoreVert,
                                        stringResource(R.string.dialog_scheduled_actions)
                                    )
                                }
                                AppMenuPopup(menu, { menu = false }, alignToAnchorEnd = true) {
                                    AppMenuSurface {
                                        AppMenuItem(
                                            stringResource(R.string.dialog_scheduled_send_now),
                                            onClick = {
                                                menu = false; actionId = id; sendAction = true
                                            })
                                        if (onEdit != null && scheduled.canEditText) AppMenuItem(
                                            stringResource(R.string.dialog_edit), onClick = {
                                                menu = false; editing =
                                                QueueTextEditSession.capture(scheduled)
                                            })
                                        if (onReschedule != null) AppMenuItem(
                                            stringResource(R.string.dialog_scheduled_reschedule),
                                            onClick = {
                                                menu = false; rescheduleId = id
                                            })
                                        AppMenuItem(
                                            stringResource(R.string.dialog_scheduled_cancel),
                                            destructive = true,
                                            onClick = {
                                                menu = false; actionId = id; sendAction = false
                                            })
                                    }
                                }
                            }
                        }) else null,
                    )
                }
            }
        }
    }
    state.preview?.let { preview ->
        if (onClosePreview != null && onRetryPreview != null) ScheduledQueueMediaViewer(
            preview, onClosePreview, onRetryPreview
        )
    }
    if (rescheduled != null) org.monogram.feature.dialog.ui.ScheduleMessageSheet(
        initialDate = rescheduled.message.date,
        titleRes = R.string.dialog_scheduled_reschedule,
        enabled = !state.fromCache && !state.needsRefresh && state.mutatingId == null && rescheduled.message.id.id !in state.uncertainIds,
        onDismiss = { rescheduleId = null },
        onSchedule = { date ->
            rescheduleId = null
            onReschedule?.invoke(rescheduled.message.id.id, date)
        },
    )
    if (selected != null) AlertDialog(
        onDismissRequest = { actionId = null },
        title = { Text(stringResource(if (sendAction) R.string.dialog_scheduled_send_now else R.string.dialog_scheduled_cancel)) },
        text = { Text(stringResource(if (sendAction) R.string.dialog_scheduled_send_confirm else R.string.dialog_scheduled_cancel_confirm)) },
        confirmButton = {
            TextButton(
                enabled = !state.fromCache && !state.needsRefresh && state.mutatingId == null && selected.message.id.id !in state.uncertainIds,
                onClick = {
                    actionId = null
                    if (sendAction) onSendNow?.invoke(selected.message.id.id) else onCancel?.invoke(
                        selected.message.id.id
                    )
                },
            ) { Text(stringResource(if (sendAction) R.string.dialog_scheduled_send_now else R.string.dialog_scheduled_cancel)) }
        },
        dismissButton = {
            TextButton(onClick = {
                actionId = null
            }) { Text(stringResource(R.string.dialog_cancel)) }
        },
    )
}
