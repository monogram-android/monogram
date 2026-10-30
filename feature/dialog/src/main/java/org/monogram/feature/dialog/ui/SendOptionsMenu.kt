package org.monogram.feature.dialog.ui

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.monogram.core.ui.components.AppModalSheet
import org.monogram.core.ui.menu.AppMenuItem
import org.monogram.core.ui.menu.AppMenuPopup
import org.monogram.core.ui.menu.AppMenuSurface
import org.monogram.feature.dialog.R
import org.monogram.feature.dialog.store.validSendSchedule
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

@Composable
internal fun SendOptionsMenu(
    expanded: Boolean, onDismiss: () -> Unit, onSilent: () -> Unit, onSchedule: () -> Unit
) {
    AppMenuPopup(expanded, onDismiss, alignToAnchorEnd = true, scrim = true) {
        AppMenuSurface {
            Column {
                AppMenuItem(
                    text = stringResource(R.string.dialog_send_silent),
                    icon = Icons.Outlined.NotificationsOff,
                    onClick = onSilent
                )
                AppMenuItem(
                    text = stringResource(R.string.dialog_send_schedule),
                    icon = Icons.Outlined.Schedule,
                    onClick = onSchedule
                )
            }
        }
    }
}

internal fun scheduledEpoch(dateMillis: Long?, hour: Int, minute: Int, zone: ZoneId): Int? {
    if (dateMillis == null || hour !in 0..23 || minute !in 0..59) return null
    val dateTime =
        Instant.ofEpochMilli(dateMillis).atZone(ZoneOffset.UTC).toLocalDate().atTime(hour, minute)
    val offset = zone.rules.getValidOffsets(dateTime).firstOrNull() ?: return null
    val seconds = dateTime.toEpochSecond(offset)
    return seconds.takeIf { it in 1 until Int.MAX_VALUE.toLong() - 1 }?.toInt()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScheduleMessageSheet(
    onDismiss: () -> Unit, onSchedule: (Int) -> Unit,
    initialDate: Long? = null,
    @androidx.annotation.StringRes titleRes: Int = R.string.dialog_send_schedule,
    enabled: Boolean = true,
) {
    val zone = remember { ZoneId.systemDefault() }
    val initial = remember(initialDate) {
        if (initialDate != null && initialDate > 0 && initialDate != 0x7ffffffeL) Instant.ofEpochSecond(
            initialDate
        ).atZone(zone)
        else Instant.now().atZone(zone).plusMinutes(5)
    }
    var showCalendar by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val date = rememberDatePickerState(
        initialSelectedDateMillis = initial.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant()
            .toEpochMilli()
    )
    val time = rememberTimePickerState(
        initialHour = initial.hour,
        initialMinute = initial.minute,
        is24Hour = android.text.format.DateFormat.is24HourFormat(androidx.compose.ui.platform.LocalContext.current)
    )
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1_000); now = System.currentTimeMillis() / 1000
        }
    }
    val epoch = scheduledEpoch(date.selectedDateMillis, time.hour, time.minute, zone)
    val valid = epoch != null && validSendSchedule(epoch, now)
    AppModalSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.9f).dp)
        ) {
            CreationSheetHeader(title = stringResource(titleRes), onDismiss = onDismiss)
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                FilledTonalButton(
                    onClick = { showCalendar = true }, modifier = Modifier.fillMaxWidth()
                ) {
                    Text(date.selectedDateMillis?.let {
                        java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.FULL)
                            .withLocale(androidx.compose.ui.platform.LocalConfiguration.current.locales[0])
                            .format(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())
                    } ?: stringResource(R.string.dialog_jump_date))
                }
                TimeInput(
                    state = time,
                    modifier = Modifier.align(androidx.compose.ui.Alignment.CenterHorizontally)
                )
                if (!valid) Text(
                    stringResource(R.string.dialog_schedule_invalid),
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(top = 16.dp, bottom = 16.dp)
            ) {
                Button(
                    onClick = {
                        if (enabled) epoch?.takeIf {
                            validSendSchedule(
                                it, System.currentTimeMillis() / 1000
                            )
                        }?.let(onSchedule)
                    }, enabled = valid && enabled,
                    shapes = androidx.compose.material3.ButtonDefaults.shapes(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                ) {
                    Text(stringResource(titleRes))
                }
            }
        }
    }
    if (showCalendar) DatePickerDialog(
        onDismissRequest = { showCalendar = false },
        confirmButton = {
            TextButton(onClick = {
                showCalendar = false
            }) { Text(stringResource(android.R.string.ok)) }
        }) { DatePicker(state = date) }
}