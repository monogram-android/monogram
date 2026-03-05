package org.monogram.presentation.chatsScreen.currentChat.chatContent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccessTime
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.monogram.domain.models.ChatPermissionsModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RestrictUserSheet(
    onDismiss: () -> Unit,
    onConfirm: (ChatPermissionsModel, Int) -> Unit
) {
    var permissions by remember { mutableStateOf(ChatPermissionsModel()) }
    var untilDate by remember { mutableIntStateOf(0) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    val calendar = remember(untilDate) {
        Calendar.getInstance().apply {
            if (untilDate != 0) {
                timeInMillis = untilDate.toLong() * 1000
            }
        }
    }

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = if (untilDate != 0) untilDate.toLong() * 1000 else System.currentTimeMillis()
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let {
                        val newCalendar = Calendar.getInstance().apply {
                            timeInMillis = it
                            if (untilDate != 0) {
                                val oldCal = Calendar.getInstance().apply { timeInMillis = untilDate.toLong() * 1000 }
                                set(Calendar.HOUR_OF_DAY, oldCal.get(Calendar.HOUR_OF_DAY))
                                set(Calendar.MINUTE, oldCal.get(Calendar.MINUTE))
                            }
                        }
                        untilDate = (newCalendar.timeInMillis / 1000).toInt()
                    }
                    showDatePicker = false
                }) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("Cancel")
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (showTimePicker) {
        val timePickerState = rememberTimePickerState(
            initialHour = calendar.get(Calendar.HOUR_OF_DAY),
            initialMinute = calendar.get(Calendar.MINUTE)
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    val newCalendar = Calendar.getInstance().apply {
                        if (untilDate != 0) {
                            timeInMillis = untilDate.toLong() * 1000
                        }
                        set(Calendar.HOUR_OF_DAY, timePickerState.hour)
                        set(Calendar.MINUTE, timePickerState.minute)
                    }
                    untilDate = (newCalendar.timeInMillis / 1000).toInt()
                    showTimePicker = false
                }) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) {
                    Text("Cancel")
                }
            },
            text = {
                TimePicker(state = timePickerState)
            }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = "Restrict User",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 4.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .padding(8.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    PermissionToggle("Send Messages", permissions.canSendBasicMessages) {
                        permissions = permissions.copy(canSendBasicMessages = it)
                    }
                    PermissionToggle("Send Media", permissions.canSendPhotos && permissions.canSendVideos) {
                        permissions = permissions.copy(
                            canSendPhotos = it,
                            canSendVideos = it,
                            canSendAudios = it,
                            canSendDocuments = it,
                            canSendVideoNotes = it,
                            canSendVoiceNotes = it
                        )
                    }
                    PermissionToggle("Send Stickers & GIFs", permissions.canSendOtherMessages) {
                        permissions = permissions.copy(canSendOtherMessages = it)
                    }
                    PermissionToggle("Send Polls", permissions.canSendPolls) {
                        permissions = permissions.copy(canSendPolls = it)
                    }
                    PermissionToggle("Embed Links", permissions.canAddLinkPreviews) {
                        permissions = permissions.copy(canAddLinkPreviews = it)
                    }
                    PermissionToggle("Pin Messages", permissions.canPinMessages) {
                        permissions = permissions.copy(canPinMessages = it)
                    }
                    PermissionToggle("Change Chat Info", permissions.canChangeInfo) {
                        permissions = permissions.copy(canChangeInfo = it)
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Restrict until", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = if (untilDate == 0) "Forever" else SimpleDateFormat(
                                    "MMM d, yyyy, HH:mm",
                                    Locale.getDefault()
                                ).format(Date(untilDate.toLong() * 1000)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Row {
                            IconButton(onClick = { showDatePicker = true }) {
                                Icon(Icons.Rounded.CalendarToday, contentDescription = "Select Date")
                            }
                            IconButton(onClick = { showTimePicker = true }) {
                                Icon(Icons.Rounded.AccessTime, contentDescription = "Select Time")
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text("Cancel", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = { onConfirm(permissions, untilDate) },
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text("Restrict", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun PermissionToggle(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.primary,
                checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
            )
        )
    }
}
