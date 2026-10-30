package org.monogram.feature.dialog.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.monogram.core.common.telegram.TelegramError
import org.monogram.core.models.MessageSendOptions
import org.monogram.core.ui.components.AppStatusBanner
import org.monogram.core.ui.components.toUiMessage
import org.monogram.core.ui.loading.MonogramBusyButton
import org.monogram.feature.dialog.R
import org.monogram.feature.dialog.store.validSendSchedule
import java.text.DateFormat.getDateTimeInstance

@Composable
internal fun CreationSheetHeader(title: String, enabled: Boolean = true, onDismiss: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleLargeEmphasized,
            modifier = Modifier.weight(1f)
        )
        FilledTonalIconButton(
            onClick = onDismiss,
            enabled = enabled,
            shapes = IconButtonDefaults.shapes()
        ) {
            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.dialog_cancel))
        }
    }
}

@Stable
internal class CreationSendState(
    silent: Boolean = false, showSchedule: Boolean = false, lastSchedule: Long? = null
) {
    var silent by mutableStateOf(silent)
    var showSchedule by mutableStateOf(showSchedule)
    var lastSchedule by mutableStateOf(lastSchedule)

    companion object {
        val Saver = listSaver<CreationSendState, Any>(save = {
            listOf(
                it.silent, it.showSchedule, it.lastSchedule ?: 0L
            )
        }, restore = {
            CreationSendState(
                it[0] as Boolean, it[1] as Boolean, (it[2] as Long).takeIf { date -> date != 0L })
        })
    }
}

@Composable
internal fun CreationSendControls(
    state: CreationSendState,
    valid: Boolean,
    busy: Boolean,
    allowed: Boolean,
    error: TelegramError?,
    onSend: (MessageSendOptions) -> Unit,
) {
    with(state) {
        var showOptions by remember { mutableStateOf(false) }
        var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
        LaunchedEffect(lastSchedule) {
            while (lastSchedule != null) {
                now = System.currentTimeMillis() / 1000; kotlinx.coroutines.delay(1000)
            }
        }
        val scheduleValid = validSendSchedule(lastSchedule?.toInt(), now)
        val interactive = allowed && !busy
        CreationSetting(stringResource(R.string.dialog_send_silent), silent, interactive) {
            silent = it
        }
        if (error != null) AppStatusBanner(
            text = error.toUiMessage(), failed = true, progress = false
        )
        if (lastSchedule != null) Text(
            getDateTimeInstance().format(java.util.Date(lastSchedule!! * 1000))
        )
        if (lastSchedule != null) TextButton(enabled = valid && interactive, onClick = {
            lastSchedule = null; onSend(MessageSendOptions(silent = silent))
        }) { Text(stringResource(R.string.dialog_scheduled_send_now)) }
        if (!scheduleValid) Text(
            stringResource(R.string.dialog_schedule_invalid),
            color = MaterialTheme.colorScheme.error
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MonogramBusyButton(
                onClick = {
                    if (valid && interactive && scheduleValid) onSend(
                        MessageSendOptions(
                            silent = silent, scheduleDate = lastSchedule?.toInt()
                        )
                    )
                },
                busy = busy,
                enabled = valid && interactive && scheduleValid,
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 56.dp)
            ) {
                Text(stringResource(if (lastSchedule != null) R.string.dialog_send_schedule else R.string.dialog_send))
            }
            Box {
                IconButton(onClick = { showOptions = true }, enabled = valid && interactive) {
                    Icon(
                        Icons.Outlined.MoreVert,
                        contentDescription = stringResource(R.string.dialog_send_options)
                    )
                }
                SendOptionsMenu(
                    showOptions && valid && interactive,
                    onDismiss = { showOptions = false },
                    onSilent = {
                        showOptions = false; if (scheduleValid) onSend(
                        MessageSendOptions(
                            silent = true, scheduleDate = lastSchedule?.toInt()
                        )
                    )
                    },
                    onSchedule = { showOptions = false; showSchedule = true })
            }
        }
        if (showSchedule) ScheduleMessageSheet(
            onDismiss = { showSchedule = false },
            initialDate = lastSchedule,
            enabled = valid && interactive,
            onSchedule = { date ->
                lastSchedule = date.toLong(); showSchedule = false
                if (valid && interactive) onSend(
                    MessageSendOptions(
                        silent = silent, scheduleDate = date
                    )
                )
            })
    }
}