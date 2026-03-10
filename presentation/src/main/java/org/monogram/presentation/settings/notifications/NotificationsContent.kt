package org.monogram.presentation.settings.notifications

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkivanov.decompose.extensions.compose.stack.Children
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import org.monogram.domain.repository.PushProvider
import org.monogram.domain.repository.SettingsRepository.TdNotificationScope
import org.monogram.presentation.core.ui.ItemPosition
import org.monogram.presentation.core.ui.SettingsItem
import org.monogram.presentation.core.ui.SettingsSwitchTile

@Composable
fun NotificationsContent(component: NotificationsComponent) {
    Children(stack = component.childStack) {
        when (val child = it.instance) {
            is NotificationsComponent.Child.Main -> NotificationsMainContent(component)
            is NotificationsComponent.Child.Exceptions -> NotificationsExceptionsContent(component, child.scope)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotificationsMainContent(component: NotificationsComponent) {
    val state by component.state.subscribeAsState()
    var showVibrationSheet by remember { mutableStateOf(false) }
    var showPrioritySheet by remember { mutableStateOf(false) }
    var showRepeatSheet by remember { mutableStateOf(false) }
    var showPushProviderSheet by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notifications and Sounds",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface) },
                navigationIcon = {
                    IconButton(onClick = component::onBackClicked) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                SectionHeader("Message Notifications")
                SettingsSwitchTile(
                    icon = Icons.Rounded.Person,
                    title = "Private Chats",
                    subtitle = if (!state.privateExceptions.isNullOrEmpty()) {
                        "${if (state.privateChatsEnabled) "On" else "Off"}, ${state.privateExceptions?.size} exceptions"
                    } else {
                        if (state.privateChatsEnabled) "On" else "Off"
                    },
                    checked = state.privateChatsEnabled,
                    iconColor = Color(0xFF4285F4),
                    position = ItemPosition.TOP,
                    onCheckedChange = component::onPrivateChatsToggled,
                    onClick = { component.onExceptionClicked(TdNotificationScope.PRIVATE_CHATS) }
                )
                SettingsSwitchTile(
                    icon = Icons.Rounded.Group,
                    title = "Groups",
                    subtitle = if (!state.groupExceptions.isNullOrEmpty()) {
                        "${if (state.groupsEnabled) "On" else "Off"}, ${state.groupExceptions?.size} exceptions"
                    } else {
                        if (state.groupsEnabled) "On" else "Off"
                    },
                    checked = state.groupsEnabled,
                    iconColor = Color(0xFF34A853),
                    position = ItemPosition.MIDDLE,
                    onCheckedChange = component::onGroupsToggled,
                    onClick = { component.onExceptionClicked(TdNotificationScope.GROUPS) }
                )
                SettingsSwitchTile(
                    icon = Icons.Rounded.Campaign,
                    title = "Channels",
                    subtitle = if (!state.channelExceptions.isNullOrEmpty()) {
                        "${if (state.channelsEnabled) "On" else "Off"}, ${state.channelExceptions?.size} exceptions"
                    } else {
                        if (state.channelsEnabled) "On" else "Off"
                    },
                    checked = state.channelsEnabled,
                    iconColor = Color(0xFFF9AB00),
                    position = ItemPosition.BOTTOM,
                    onCheckedChange = component::onChannelsToggled,
                    onClick = { component.onExceptionClicked(TdNotificationScope.CHANNELS) }
                )
            }

            item {
                SectionHeader("Notification Settings")
                SettingsItem(
                    icon = Icons.Rounded.Vibration,
                    title = "Vibration",
                    subtitle = state.vibrationPattern.replaceFirstChar { it.uppercase() },
                    iconBackgroundColor = Color(0xFF9C27B0),
                    position = ItemPosition.TOP,
                    onClick = { showVibrationSheet = true }
                )
                SettingsItem(
                    icon = Icons.Rounded.PriorityHigh,
                    title = "Priority",
                    subtitle = when(state.priority) {
                        0 -> "Low"
                        2 -> "High"
                        else -> "Default"
                    },
                    iconBackgroundColor = Color(0xFFFF5722),
                    position = ItemPosition.MIDDLE,
                    onClick = { showPrioritySheet = true }
                )
                SettingsItem(
                    icon = Icons.Rounded.Repeat,
                    title = "Repeat Notifications",
                    subtitle = when (state.repeatNotifications) {
                        0 -> "Never"
                        5, 10, 30 -> "Every ${state.repeatNotifications} minutes"
                        60 -> "Every 1 hour"
                        120 -> "Every 2 hours"
                        240 -> "Every 4 hours"
                        else -> "Every ${state.repeatNotifications} minutes"
                    },
                    iconBackgroundColor = Color(0xFF3F51B5),
                    position = ItemPosition.MIDDLE,
                    onClick = { showRepeatSheet = true }
                )
                SettingsSwitchTile(
                    icon = Icons.Rounded.Visibility,
                    title = "Show Sender Only",
                    subtitle = "Hide message content in notifications",
                    checked = state.showSenderOnly,
                    iconColor = Color(0xFF607D8B),
                    position = ItemPosition.BOTTOM,
                    onCheckedChange = component::onShowSenderOnlyToggled
                )
            }

            item {
                SectionHeader("Push Service")
                SettingsItem(
                    icon = Icons.Rounded.NotificationsActive,
                    title = "Push Provider",
                    subtitle = when (state.pushProvider) {
                        PushProvider.FCM -> "Firebase Cloud Messaging"
                        PushProvider.GMS_LESS -> "GMS-less (Background Service)"
                    },
                    iconBackgroundColor = Color(0xFF4CAF50),
                    position = ItemPosition.TOP,
                    onClick = { showPushProviderSheet = true }
                )
                SettingsSwitchTile(
                    icon = Icons.Rounded.Sync,
                    title = "Keep-Alive Service",
                    subtitle = "Keep the app running in the background for reliable notifications",
                    checked = state.backgroundServiceEnabled,
                    iconColor = Color(0xFF607D8B),
                    position = ItemPosition.MIDDLE,
                    onCheckedChange = component::onBackgroundServiceToggled
                )
                SettingsSwitchTile(
                    icon = Icons.Rounded.VisibilityOff,
                    title = "Hide Foreground Notification",
                    subtitle = "Hide the service notification after it starts. May lead to service termination by system",
                    checked = state.hideForegroundNotification,
                    iconColor = Color(0xFF9E9E9E),
                    position = ItemPosition.BOTTOM,
                    onCheckedChange = component::onHideForegroundNotificationToggled
                )
            }

            item {
                SectionHeader("In-App Notifications")
                SettingsSwitchTile(
                    icon = Icons.AutoMirrored.Rounded.VolumeUp,
                    title = "In-App Sounds",
                    checked = state.inAppSounds,
                    iconColor = Color(0xFFE91E63),
                    position = ItemPosition.TOP,
                    onCheckedChange = component::onInAppSoundsToggled
                )
                SettingsSwitchTile(
                    icon = Icons.Rounded.Vibration,
                    title = "In-App Vibrate",
                    checked = state.inAppVibrate,
                    iconColor = Color(0xFF9C27B0),
                    position = ItemPosition.MIDDLE,
                    onCheckedChange = component::onInAppVibrateToggled
                )
                SettingsSwitchTile(
                    icon = Icons.Rounded.ChatBubbleOutline,
                    title = "In-App Preview",
                    checked = state.inAppPreview,
                    iconColor = Color(0xFF00BCD4),
                    position = ItemPosition.BOTTOM,
                    onCheckedChange = component::onInAppPreviewToggled
                )
            }

            item {
                SectionHeader("Events")
                SettingsSwitchTile(
                    icon = Icons.Rounded.PersonAdd,
                    title = "Contact Joined Telegram",
                    checked = state.contactJoined,
                    iconColor = Color(0xFF4CAF50),
                    position = ItemPosition.TOP,
                    onCheckedChange = component::onContactJoinedToggled
                )
                SettingsSwitchTile(
                    icon = Icons.Rounded.PushPin,
                    title = "Pinned Messages",
                    checked = state.pinnedMessages,
                    iconColor = Color(0xFFFF9800),
                    position = ItemPosition.BOTTOM,
                    onCheckedChange = component::onPinnedMessagesToggled
                )
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
                SettingsItem(
                    icon = Icons.Rounded.Refresh,
                    title = "Reset All Notifications",
                    subtitle = "Undo all custom notification settings for all your contacts and groups",
                    iconBackgroundColor = MaterialTheme.colorScheme.error,
                    position = ItemPosition.STANDALONE,
                    onClick = component::onResetNotificationsClicked
                )
            }
            item { Spacer(modifier = Modifier.height(padding.calculateBottomPadding()-16.dp)) }
        }
    }

    if (showVibrationSheet) {
        val options = listOf("default", "short", "long", "disabled")
        NotificationOptionSheet(
            title = "Vibration Pattern",
            options = options.map { it to it.replaceFirstChar { char -> char.uppercase() } },
            selectedOption = state.vibrationPattern,
            onOptionSelected = {
                component.onVibrationPatternChanged(it)
                showVibrationSheet = false
            },
            onDismiss = { showVibrationSheet = false }
        )
    }

    if (showPrioritySheet) {
        val options = listOf(0 to "Low", 1 to "Default", 2 to "High")
        NotificationOptionSheet(
            title = "Notification Priority",
            options = options.map { it.first.toString() to it.second },
            selectedOption = state.priority.toString(),
            onOptionSelected = {
                component.onPriorityChanged(it.toInt())
                showPrioritySheet = false
            },
            onDismiss = { showPrioritySheet = false }
        )
    }

    if (showRepeatSheet) {
        val options = listOf(
            0 to "Never",
            5 to "5 minutes",
            10 to "10 minutes",
            30 to "30 minutes",
            60 to "1 hour",
            120 to "2 hours",
            240 to "4 hours"
        )
        NotificationOptionSheet(
            title = "Repeat Notifications",
            options = options.map { it.first.toString() to it.second },
            selectedOption = state.repeatNotifications.toString(),
            onOptionSelected = {
                component.onRepeatNotificationsChanged(it.toInt())
                showRepeatSheet = false
            },
            onDismiss = { showRepeatSheet = false }
        )
    }

    if (showPushProviderSheet) {
        val options = mutableListOf<Pair<String, String>>()
        if (state.isGmsAvailable) {
            options.add(PushProvider.FCM.name to "Firebase Cloud Messaging")
        }
        options.add(PushProvider.GMS_LESS.name to "GMS-less (Background Service)")

        NotificationOptionSheet(
            title = "Push Provider",
            options = options,
            selectedOption = state.pushProvider.name,
            onOptionSelected = {
                component.onPushProviderChanged(PushProvider.valueOf(it))
                showPushProviderSheet = false
            },
            onDismiss = { showPushProviderSheet = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotificationOptionSheet(
    title: String,
    options: List<Pair<String, String>>,
    selectedOption: String,
    onOptionSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
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
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp, start = 8.dp)
            )

            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                LazyColumn(
                    modifier = Modifier.padding(vertical = 8.dp)
                ) {
                    items(options) { (value, label) ->
                        val isSelected = value == selectedOption
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOptionSelected(value) }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = label,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                            RadioButton(
                                selected = isSelected,
                                onClick = { onOptionSelected(value) }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("Cancel", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(start = 12.dp, bottom = 8.dp, top = 16.dp),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold
    )
}
