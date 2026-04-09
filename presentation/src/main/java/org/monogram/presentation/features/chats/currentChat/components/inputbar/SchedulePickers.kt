package org.monogram.presentation.features.chats.currentChat.components.inputbar

import android.text.format.DateFormat
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import org.monogram.presentation.R
import org.monogram.presentation.core.util.DateFormatManager
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleDatePickerDialog(
    onDismiss: () -> Unit,
    onDateSelected: (Long) -> Unit
) {
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = System.currentTimeMillis()
    )

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = datePickerState.selectedDateMillis != null,
                onClick = {
                    datePickerState.selectedDateMillis?.let(onDateSelected)
                }
            ) {
                Text(text = stringResource(R.string.action_done))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.action_cancel))
            }
        }
    ) {
        DatePicker(state = datePickerState)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleTimePickerDialog(
    initialHour: Int,
    initialMinute: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int) -> Unit
) {
    val context = LocalContext.current
    val is24HourFormat = remember(context) { DateFormat.is24HourFormat(context) }
    val timePickerState = rememberTimePickerState(
        initialHour = initialHour,
        initialMinute = initialMinute,
        is24Hour = is24HourFormat
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.cd_select_time)) },
        text = {
            TimePicker(
                state = timePickerState,
                modifier = Modifier
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(timePickerState.hour, timePickerState.minute) }) {
                Text(text = stringResource(R.string.action_done))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.action_cancel))
            }
        }
    )
}

fun buildScheduledDateEpochSeconds(selectedDateMillis: Long, hour: Int, minute: Int): Int {
    val utcDate = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        timeInMillis = selectedDateMillis
    }

    val selected = Calendar.getInstance().apply {
        set(Calendar.YEAR, utcDate.get(Calendar.YEAR))
        set(Calendar.MONTH, utcDate.get(Calendar.MONTH))
        set(Calendar.DAY_OF_MONTH, utcDate.get(Calendar.DAY_OF_MONTH))
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    val now = Calendar.getInstance()
    if (selected.before(now)) {
        selected.timeInMillis = now.timeInMillis + 60_000L
    }

    return (selected.timeInMillis / 1000L).toInt()
}

fun formatScheduledTimestamp(epochSeconds: Int, timeFormat: String): String {
    return try {
        val formatter = SimpleDateFormat("dd MMM, $timeFormat", Locale.getDefault())
        formatter.format(Date(epochSeconds * 1000L))
    } catch (_: Exception) {
        ""
    }
}
