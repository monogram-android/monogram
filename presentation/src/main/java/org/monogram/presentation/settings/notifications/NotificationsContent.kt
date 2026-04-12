package org.monogram.presentation.settings.notifications

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkivanov.decompose.extensions.compose.stack.Children
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import org.monogram.domain.repository.NotificationSettingsRepository.TdNotificationScope
import org.monogram.domain.repository.PushProvider
import org.monogram.presentation.R
import org.monogram.presentation.core.ui.ExpressiveDefaults
import org.monogram.presentation.core.ui.ItemPosition
import org.monogram.presentation.core.ui.SettingsItem
import org.monogram.presentation.core.ui.SettingsSwitchTile
import org.monogram.presentation.core.util.findActivity
import org.unifiedpush.android.connector.UnifiedPush

@Composable
fun NotificationsContent(component: NotificationsComponent) {
    Children(stack = component.childStack) {
        when (val child = it.instance) {
            is NotificationsComponent.Child.Main -> NotificationsMainContent(component)
            is NotificationsComponent.Child.Exceptions -> NotificationsExceptionsContent(component, child.scope)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun NotificationsMainContent(component: NotificationsComponent) {
    val state by component.state.subscribeAsState()
    val context = LocalContext.current
    var showVibrationSheet by remember { mutableStateOf(false) }
    var showPrioritySheet by remember { mutableStateOf(false) }
    var showRepeatSheet by remember { mutableStateOf(false) }
    var showPushProviderSheet by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.semantics { contentDescription = "NotificationsContent" },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.notifications_sounds_header),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface) },
                navigationIcon = {
                    IconButton(
                        onClick = component::onBackClicked,
                        shapes = ExpressiveDefaults.iconButtonShapes()
                    ) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back)
                        )
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
                SectionHeader(stringResource(R.string.message_notifications_header))
                SettingsSwitchTile(
                    icon = Icons.Rounded.Person,
                    title = stringResource(R.string.private_chats_title),
                    subtitle = if (!state.privateExceptions.isNullOrEmpty()) {
                        stringResource(
                            R.string.exceptions_count_format,
                            if (state.privateChatsEnabled) stringResource(R.string.on_label) else stringResource(R.string.off_label),
                            state.privateExceptions?.size ?: 0
                        )
                    } else {
                        if (state.privateChatsEnabled) stringResource(R.string.on_label) else stringResource(R.string.off_label)
                    },
                    checked = state.privateChatsEnabled,
                    iconColor = Color(0xFF4285F4),
                    position = ItemPosition.TOP,
                    onCheckedChange = component::onPrivateChatsToggled,
                    onClick = { component.onExceptionClicked(TdNotificationScope.PRIVATE_CHATS) }
                )
                SettingsSwitchTile(
                    icon = Icons.Rounded.Group,
                    title = stringResource(R.string.groups_title),
                    subtitle = if (!state.groupExceptions.isNullOrEmpty()) {
                        stringResource(
                            R.string.exceptions_count_format,
                            if (state.groupsEnabled) stringResource(R.string.on_label) else stringResource(R.string.off_label),
                            state.groupExceptions?.size ?: 0
                        )
                    } else {
                        if (state.groupsEnabled) stringResource(R.string.on_label) else stringResource(R.string.off_label)
                    },
                    checked = state.groupsEnabled,
                    iconColor = Color(0xFF34A853),
                    position = ItemPosition.MIDDLE,
                    onCheckedChange = component::onGroupsToggled,
                    onClick = { component.onExceptionClicked(TdNotificationScope.GROUPS) }
                )
                SettingsSwitchTile(
                    icon = Icons.Rounded.Campaign,
                    title = stringResource(R.string.channels_title),
                    subtitle = if (!state.channelExceptions.isNullOrEmpty()) {
                        stringResource(
                            R.string.exceptions_count_format,
                            if (state.channelsEnabled) stringResource(R.string.on_label) else stringResource(R.string.off_label),
                            state.channelExceptions?.size ?: 0
                        )
                    } else {
                        if (state.channelsEnabled) stringResource(R.string.on_label) else stringResource(R.string.off_label)
                    },
                    checked = state.channelsEnabled,
                    iconColor = Color(0xFFF9AB00),
                    position = ItemPosition.BOTTOM,
                    onCheckedChange = component::onChannelsToggled,
                    onClick = { component.onExceptionClicked(TdNotificationScope.CHANNELS) }
                )
            }

            item {
                SectionHeader(stringResource(R.string.notification_settings_header))
                SettingsItem(
                    icon = Icons.Rounded.Vibration,
                    title = stringResource(R.string.vibration_title),
                    subtitle = when (state.vibrationPattern) {
                        "short" -> stringResource(R.string.vibration_pattern_short)
                        "long" -> stringResource(R.string.vibration_pattern_long)
                        "disabled" -> stringResource(R.string.vibration_pattern_disabled)
                        else -> stringResource(R.string.vibration_pattern_default)
                    },
                    iconBackgroundColor = Color(0xFF9C27B0),
                    position = ItemPosition.TOP,
                    onClick = { showVibrationSheet = true }
                )
                SettingsItem(
                    icon = Icons.Rounded.PriorityHigh,
                    title = stringResource(R.string.priority_title),
                    subtitle = when(state.priority) {
                        0 -> stringResource(R.string.priority_low)
                        2 -> stringResource(R.string.priority_high)
                        else -> stringResource(R.string.priority_default)
                    },
                    iconBackgroundColor = Color(0xFFFF5722),
                    position = ItemPosition.MIDDLE,
                    onClick = { showPrioritySheet = true }
                )
                SettingsItem(
                    icon = Icons.Rounded.Repeat,
                    title = stringResource(R.string.repeat_notifications_title),
                    subtitle = when (state.repeatNotifications) {
                        0 -> stringResource(R.string.repeat_never)
                        60 -> stringResource(R.string.repeat_hour_format)
                        120, 240 -> stringResource(R.string.repeat_hours_format, state.repeatNotifications / 60)
                        else -> stringResource(R.string.repeat_minutes_format, state.repeatNotifications)
                    },
                    iconBackgroundColor = Color(0xFF3F51B5),
                    position = ItemPosition.MIDDLE,
                    onClick = { showRepeatSheet = true }
                )
                SettingsSwitchTile(
                    icon = Icons.Rounded.Visibility,
                    title = stringResource(R.string.show_sender_only_title),
                    subtitle = stringResource(R.string.show_sender_only_subtitle),
                    checked = state.showSenderOnly,
                    iconColor = Color(0xFF607D8B),
                    position = ItemPosition.BOTTOM,
                    onCheckedChange = component::onShowSenderOnlyToggled
                )
            }

            item {
                SectionHeader(stringResource(R.string.push_service_header))
                SettingsItem(
                    icon = Icons.Rounded.NotificationsActive,
                    title = stringResource(R.string.push_provider_title),
                    subtitle = when (state.pushProvider) {
                        PushProvider.FCM -> stringResource(R.string.push_provider_fcm)
                        PushProvider.UNIFIED_PUSH -> stringResource(R.string.push_provider_unified)
                        PushProvider.GMS_LESS -> stringResource(R.string.push_provider_gms_less)
                    },
                    iconBackgroundColor = Color(0xFF4CAF50),
                    position = ItemPosition.TOP,
                    onClick = { showPushProviderSheet = true }
                )
                SettingsSwitchTile(
                    icon = Icons.Rounded.Sync,
                    title = stringResource(R.string.keep_alive_service_title),
                    subtitle = stringResource(R.string.keep_alive_service_subtitle),
                    checked = state.backgroundServiceEnabled,
                    iconColor = Color(0xFF607D8B),
                    position = ItemPosition.MIDDLE,
                    enabled = state.pushProvider == PushProvider.GMS_LESS,
                    onCheckedChange = component::onBackgroundServiceToggled
                )
                SettingsSwitchTile(
                    icon = Icons.Rounded.VisibilityOff,
                    title = stringResource(R.string.hide_foreground_notification_title),
                    subtitle = stringResource(R.string.hide_foreground_notification_subtitle),
                    checked = state.hideForegroundNotification,
                    iconColor = Color(0xFF9E9E9E),
                    position = ItemPosition.BOTTOM,
                    enabled = state.pushProvider == PushProvider.GMS_LESS,
                    onCheckedChange = component::onHideForegroundNotificationToggled
                )
            }

            item {
                SectionHeader(stringResource(R.string.in_app_notifications_header))
                SettingsSwitchTile(
                    icon = Icons.AutoMirrored.Rounded.VolumeUp,
                    title = stringResource(R.string.in_app_sounds_title),
                    checked = state.inAppSounds,
                    iconColor = Color(0xFFE91E63),
                    position = ItemPosition.TOP,
                    onCheckedChange = component::onInAppSoundsToggled
                )
                SettingsSwitchTile(
                    icon = Icons.Rounded.Vibration,
                    title = stringResource(R.string.in_app_vibrate_title),
                    checked = state.inAppVibrate,
                    iconColor = Color(0xFF9C27B0),
                    position = ItemPosition.MIDDLE,
                    onCheckedChange = component::onInAppVibrateToggled
                )
                SettingsSwitchTile(
                    icon = Icons.Rounded.ChatBubbleOutline,
                    title = stringResource(R.string.in_app_preview_title),
                    checked = state.inAppPreview,
                    iconColor = Color(0xFF00BCD4),
                    position = ItemPosition.BOTTOM,
                    onCheckedChange = component::onInAppPreviewToggled
                )
            }

            item {
                SectionHeader(stringResource(R.string.events_header))
                SettingsSwitchTile(
                    icon = Icons.Rounded.PersonAdd,
                    title = stringResource(R.string.contact_joined_telegram_title),
                    checked = state.contactJoined,
                    iconColor = Color(0xFF4CAF50),
                    position = ItemPosition.TOP,
                    onCheckedChange = component::onContactJoinedToggled
                )
                SettingsSwitchTile(
                    icon = Icons.Rounded.PushPin,
                    title = stringResource(R.string.pinned_messages_title),
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
                    title = stringResource(R.string.reset_all_notifications_title),
                    subtitle = stringResource(R.string.reset_all_notifications_subtitle),
                    iconBackgroundColor = MaterialTheme.colorScheme.error,
                    position = ItemPosition.STANDALONE,
                    onClick = component::onResetNotificationsClicked
                )
            }
            item { Spacer(modifier = Modifier.height(padding.calculateBottomPadding()-16.dp)) }
        }
    }

    if (showVibrationSheet) {
        val options = listOf(
            "default" to stringResource(R.string.vibration_pattern_default),
            "short" to stringResource(R.string.vibration_pattern_short),
            "long" to stringResource(R.string.vibration_pattern_long),
            "disabled" to stringResource(R.string.vibration_pattern_disabled)
        )
        NotificationOptionSheet(
            title = stringResource(R.string.vibration_pattern_title),
            options = options,
            selectedOption = state.vibrationPattern,
            onOptionSelected = {
                component.onVibrationPatternChanged(it)
                showVibrationSheet = false
            },
            onDismiss = { showVibrationSheet = false }
        )
    }

    if (showPrioritySheet) {
        val options = listOf(
            "0" to stringResource(R.string.priority_low),
            "1" to stringResource(R.string.priority_default),
            "2" to stringResource(R.string.priority_high)
        )
        NotificationOptionSheet(
            title = stringResource(R.string.notification_priority_title),
            options = options,
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
            "0" to stringResource(R.string.repeat_never),
            "5" to stringResource(R.string.repeat_minutes_format, 5),
            "10" to stringResource(R.string.repeat_minutes_format, 10),
            "30" to stringResource(R.string.repeat_minutes_format, 30),
            "60" to stringResource(R.string.repeat_hour_format),
            "120" to stringResource(R.string.repeat_hours_format, 2),
            "240" to stringResource(R.string.repeat_hours_format, 4)
        )
        NotificationOptionSheet(
            title = stringResource(R.string.repeat_notifications_title),
            options = options,
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
            options.add(PushProvider.FCM.name to stringResource(R.string.push_provider_fcm))
        }
        if (state.isUnifiedPushAvailable) {
            options.add(PushProvider.UNIFIED_PUSH.name to stringResource(R.string.push_provider_unified))
        }
        options.add(PushProvider.GMS_LESS.name to stringResource(R.string.push_provider_gms_less))

        NotificationOptionSheet(
            title = stringResource(R.string.push_provider_title),
            options = options,
            selectedOption = state.pushProvider.name,
            onOptionSelected = {
                val selected = PushProvider.valueOf(it)
                if (selected != PushProvider.UNIFIED_PUSH) {
                    component.onPushProviderChanged(selected)
                    showPushProviderSheet = false
                    return@NotificationOptionSheet
                }

                val activity = context.findActivity()
                if (activity == null) {
                    Toast.makeText(
                        context,
                        "Cannot select UnifiedPush without active activity",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@NotificationOptionSheet
                }

                UnifiedPush.tryUseCurrentOrDefaultDistributor(activity) { success ->
                    if (success) {
                        component.onPushProviderChanged(PushProvider.UNIFIED_PUSH)
                        showPushProviderSheet = false
                    } else {
                        Toast.makeText(
                            context,
                            "UnifiedPush distributor not selected",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            },
            onDismiss = { showPushProviderSheet = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
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
                shapes = ExpressiveDefaults.largeButtonShapes(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ButtonDefaults.MediumContainerHeight)
            ) {
                Text(stringResource(R.string.cancel_button), fontSize = 16.sp, fontWeight = FontWeight.Bold)
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
