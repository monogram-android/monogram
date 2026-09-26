package org.monogram.feature.settings.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.monogram.core.common.push.PeerNotificationMode
import org.monogram.core.ui.components.ItemPosition
import org.monogram.core.ui.components.SectionHeader
import org.monogram.core.ui.components.SettingsTile
import org.monogram.feature.settings.NotificationsStore
import org.monogram.feature.settings.R
import org.monogram.feature.settings.SettingsComponent

internal fun LazyListScope.notificationsItems(
    component: SettingsComponent,
    state: NotificationsStore.State,
    debug: Boolean,
    onOpenDebug: () -> Unit,
    onOpenCategory: (String) -> Unit,
    onOpenExceptions: () -> Unit,
) {
    val now = (System.currentTimeMillis() / 1000L).toInt()
    item { SectionHeader(stringResource(R.string.settings_notifications_section_global)) }
    globalRow(component, "users", R.string.settings_notifications_private, state.users, now, 0, 2, onOpenCategory)
    globalRow(component, "chats", R.string.settings_notifications_groups, state.chats, now, 1, 2, onOpenCategory)
    globalRow(component, "broadcasts", R.string.settings_notifications_channels, state.broadcasts, now, 2, 2, onOpenCategory)
    switchRow(component, "stories", R.string.settings_notifications_stories, state.stories, ItemPosition.TOP)
    switchValueRow(
        titleRes = R.string.settings_notifications_stories_popup,
        checked = state.storiesPopup,
        position = ItemPosition.MIDDLE,
        onToggle = { component.onNotification(NotificationsStore.Intent.SetCategoryPopup("stories", it)) },
    )
    switchRow(component, "reactions", R.string.settings_notifications_reactions, state.reactions, ItemPosition.MIDDLE)
    switchValueRow(
        titleRes = R.string.settings_notifications_reactions_popup,
        checked = state.reactionsPopup,
        position = ItemPosition.MIDDLE,
        onToggle = { component.onNotification(NotificationsStore.Intent.SetCategoryPopup("reactions", it)) },
    )
    switchRow(component, "gifts", R.string.settings_notifications_gifts, state.gifts, ItemPosition.BOTTOM)
    item { SectionHeader(stringResource(R.string.settings_notifications_exceptions)) }
    item {
        SettingsTile(
            icon = Icons.Outlined.Notifications,
            title = stringResource(R.string.settings_notifications_exceptions_count, state.exceptions.size),
            subtitle = stringResource(R.string.settings_notifications_exceptions_sub),
            iconColor = MaterialTheme.colorScheme.secondary,
            position = ItemPosition.STANDALONE,
            onClick = onOpenExceptions,
        )
    }
    if (state.folders.isNotEmpty()) {
        item { SectionHeader(stringResource(R.string.settings_notifications_folders)) }
        state.folders.forEachIndexed { index, folder ->
            val muted = folder.id in state.mutedFolders
            item {
                SettingsTile(
                    icon = Icons.Outlined.Folder,
                    title = folder.title,
                    subtitle = if (muted) stringResource(R.string.settings_notifications_folder_muted) else stringResource(R.string.settings_notifications_folder_on),
                    iconColor = MaterialTheme.colorScheme.tertiary,
                    position = ItemPosition.TOP,
                    onClick = { component.onNotification(NotificationsStore.Intent.ToggleFolder(folder.id, !muted)) },
                    trailingContent = { Switch(checked = !muted, onCheckedChange = { component.onNotification(NotificationsStore.Intent.ToggleFolder(folder.id, !it)) }) },
                )
            }
            val prefs = state.folderPrefs[folder.id] ?: NotificationsStore.FolderPrefs()
            val key = "folder_${folder.id}"
            switchValueRow(
                titleRes = R.string.settings_notifications_vibrate,
                checked = prefs.vibrate,
                position = ItemPosition.MIDDLE,
                onToggle = { component.onNotification(NotificationsStore.Intent.SetCategoryVibrate(key, it)) },
            )
            switchValueRow(
                titleRes = R.string.settings_notifications_led,
                checked = prefs.led,
                position = ItemPosition.MIDDLE,
                onToggle = { component.onNotification(NotificationsStore.Intent.SetCategoryLed(key, it)) },
            )
            switchValueRow(
                titleRes = R.string.settings_notifications_popup,
                checked = prefs.popup,
                position = ItemPosition.BOTTOM,
                onToggle = { component.onNotification(NotificationsStore.Intent.SetCategoryPopup(key, it)) },
            )
        }
    }
    item { SectionHeader(stringResource(R.string.settings_notifications_calls)) }
    item {
        SettingsTile(
            icon = Icons.Outlined.Phone,
            title = stringResource(R.string.settings_notifications_calls_vibrate),
            subtitle = state.callsVibrate,
            iconColor = MaterialTheme.colorScheme.primary,
            position = ItemPosition.TOP,
            onClick = { component.onNotification(NotificationsStore.Intent.CycleCallsVibrate) },
        )
    }
    item {
        SettingsTile(
            icon = Icons.Outlined.Phone,
            title = stringResource(R.string.settings_notifications_calls_ringtone),
            subtitle = state.callsRingtone,
            iconColor = MaterialTheme.colorScheme.primary,
            position = ItemPosition.BOTTOM,
            onClick = { component.onNotification(NotificationsStore.Intent.CycleCallsRingtone) },
        )
    }
    item { SectionHeader(stringResource(R.string.settings_notifications_inapp)) }
    switchRow(component, "sound", R.string.settings_notifications_inapp_sound, state.inAppSound, ItemPosition.TOP)
    switchRow(component, "vibrate", R.string.settings_notifications_inapp_vibrate, state.inAppVibrate, ItemPosition.MIDDLE)
    switchRow(component, "preview", R.string.settings_notifications_inapp_preview, state.inAppPreview, ItemPosition.MIDDLE)
    switchRow(component, "inchat", R.string.settings_notifications_inchat_sound, state.inChatSound, ItemPosition.MIDDLE)
    switchRow(component, "priority", R.string.settings_notifications_inapp_priority, state.inAppPriority, ItemPosition.BOTTOM)
    item { SectionHeader(stringResource(R.string.settings_notifications_events)) }
    switchRow(component, "joined", R.string.settings_notifications_contact_joined, state.contactJoined, ItemPosition.TOP, Icons.Outlined.PersonAdd)
    switchRow(component, "pinned", R.string.settings_notifications_pinned, state.pinned, ItemPosition.BOTTOM, Icons.Outlined.PushPin)
    item { SectionHeader(stringResource(R.string.settings_notifications_badge)) }
    switchRow(component, "badge", R.string.settings_notifications_badge_show, state.badge, ItemPosition.TOP)
    switchRow(component, "badgeMuted", R.string.settings_notifications_badge_muted, state.badgeMuted, ItemPosition.MIDDLE)
    switchRow(component, "badgeMessages", R.string.settings_notifications_badge_messages, state.badgeMessages, ItemPosition.BOTTOM)
    item { SectionHeader(stringResource(R.string.settings_notifications_other)) }
    item {
        val label = when (state.repeatMinutes) {
            0 -> stringResource(R.string.settings_notifications_repeat_off)
            else -> stringResource(R.string.settings_notifications_repeat_min, state.repeatMinutes)
        }
        SettingsTile(
            icon = Icons.Outlined.Refresh,
            title = stringResource(R.string.settings_notifications_repeat),
            subtitle = label,
            iconColor = MaterialTheme.colorScheme.primary,
            position = ItemPosition.TOP,
            onClick = { component.onNotification(NotificationsStore.Intent.CycleRepeat) },
        )
    }
    item {
        val context = LocalContext.current
        SettingsTile(
            icon = Icons.Outlined.Notifications,
            title = stringResource(R.string.settings_system_notifications),
            iconColor = MaterialTheme.colorScheme.primary,
            position = ItemPosition.MIDDLE,
            onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                )
            },
        )
    }
    item {
        SettingsTile(
            icon = Icons.Outlined.Refresh,
            title = stringResource(R.string.settings_notifications_reset),
            subtitle = stringResource(R.string.settings_notifications_reset_sub),
            iconColor = MaterialTheme.colorScheme.error,
            position = if (debug) ItemPosition.MIDDLE else ItemPosition.BOTTOM,
            onClick = { component.onNotification(NotificationsStore.Intent.Reset) },
        )
    }
    if (debug) {
        item {
            SettingsTile(
                icon = Icons.Outlined.BugReport,
                title = stringResource(R.string.settings_notifications_debug),
                subtitle = stringResource(R.string.settings_notifications_debug_sub),
                iconColor = MaterialTheme.colorScheme.tertiary,
                position = ItemPosition.BOTTOM,
                onClick = onOpenDebug,
            )
        }
    }
}

internal fun LazyListScope.notificationDebugItems(component: SettingsComponent, state: NotificationsStore.State) {
    val debug = state.debug
    item { SectionHeader(stringResource(R.string.settings_notifications_debug)) }
    item {
        SettingsTile(
            icon = Icons.Outlined.BugReport,
            title = stringResource(R.string.settings_notifications_debug_transport, debug.transport),
            subtitle = stringResource(
                R.string.settings_notifications_debug_status,
                debug.tokenRedacted.ifBlank { "—" },
                if (debug.gmsAvailable) "GMS" else "no-GMS",
                debug.distributor.ifBlank { "—" },
            ),
            iconColor = MaterialTheme.colorScheme.tertiary,
            position = ItemPosition.TOP,
            onClick = { component.onNotification(NotificationsStore.Intent.Refresh) },
        )
    }
    item {
        SettingsTile(
            icon = Icons.Outlined.Notifications,
            title = stringResource(R.string.settings_notifications_debug_last, debug.lastLocKey.ifBlank { "—" }),
            subtitle = debug.lastCustomIds.ifBlank { debug.lastRegister },
            iconColor = MaterialTheme.colorScheme.secondary,
            position = ItemPosition.MIDDLE,
            onClick = { },
        )
    }
    item {
        SettingsTile(
            icon = Icons.Outlined.Refresh,
            title = stringResource(R.string.settings_notifications_debug_reregister),
            iconColor = MaterialTheme.colorScheme.primary,
            position = ItemPosition.MIDDLE,
            onClick = { component.onNotification(NotificationsStore.Intent.Reregister) },
        )
    }
    item {
        SettingsTile(
            icon = Icons.Outlined.Notifications,
            title = stringResource(R.string.settings_notifications_debug_permission),
            subtitle = if (debug.permissionGranted) stringResource(R.string.settings_notifications_debug_granted) else stringResource(R.string.settings_notifications_debug_missing),
            iconColor = MaterialTheme.colorScheme.primary,
            position = ItemPosition.MIDDLE,
            onClick = { component.onNotification(NotificationsStore.Intent.RequestPermission) },
        )
    }
    item {
        SettingsTile(
            icon = Icons.AutoMirrored.Outlined.VolumeUp,
            title = stringResource(R.string.settings_notifications_debug_simulate),
            iconColor = MaterialTheme.colorScheme.primary,
            position = ItemPosition.BOTTOM,
            onClick = { component.onNotification(NotificationsStore.Intent.Simulate("MESSAGE_TEXT")) },
        )
    }
}

internal fun LazyListScope.notificationCategoryItems(
    component: SettingsComponent,
    state: NotificationsStore.State,
    kind: String,
    onOpenExceptions: () -> Unit,
) {
    val now = (System.currentTimeMillis() / 1000L).toInt()
    val settings = when (kind) {
        "chats" -> state.chats
        "broadcasts" -> state.broadcasts
        else -> state.users
    }
    val enabled = settings.muteUntil <= now
    val vibrate = when (kind) {
        "chats" -> state.chatsVibrate
        "broadcasts" -> state.broadcastsVibrate
        else -> state.usersVibrate
    }
    val led = when (kind) {
        "chats" -> state.chatsLed
        "broadcasts" -> state.broadcastsLed
        else -> state.usersLed
    }
    val priority = when (kind) {
        "chats" -> state.chatsPriority
        "broadcasts" -> state.broadcastsPriority
        else -> state.usersPriority
    }
    val popup = when (kind) {
        "chats" -> state.chatsPopup
        "broadcasts" -> state.broadcastsPopup
        else -> state.usersPopup
    }
    item { SectionHeader(stringResource(R.string.settings_notifications_section_global)) }
    switchValueRow(
        titleRes = R.string.settings_notifications,
        checked = enabled,
        position = ItemPosition.TOP,
        onToggle = { component.onNotification(NotificationsStore.Intent.ToggleGlobal(kind, it)) },
    )
    switchValueRow(
        titleRes = R.string.settings_notifications_preview,
        checked = settings.showPreviews,
        position = ItemPosition.MIDDLE,
        onToggle = { component.onNotification(NotificationsStore.Intent.SetPreview(kind, it)) },
    )
    item {
        SettingsTile(
            icon = Icons.AutoMirrored.Outlined.VolumeUp,
            title = stringResource(R.string.settings_notifications_sound),
            subtitle = if (settings.sound == "none") {
                stringResource(R.string.settings_notifications_sound_none)
            } else {
                stringResource(R.string.settings_notifications_sound_default)
            },
            iconColor = MaterialTheme.colorScheme.primary,
            position = ItemPosition.MIDDLE,
            onClick = {
                val next = if (settings.sound == "none") "default" else "none"
                component.onNotification(NotificationsStore.Intent.SetSound(kind, next))
            },
        )
    }
    switchValueRow(
        titleRes = R.string.settings_notifications_vibrate,
        checked = vibrate,
        position = ItemPosition.MIDDLE,
        onToggle = { component.onNotification(NotificationsStore.Intent.SetCategoryVibrate(kind, it)) },
    )
    switchValueRow(
        titleRes = R.string.settings_notifications_led,
        checked = led,
        position = ItemPosition.MIDDLE,
        onToggle = { component.onNotification(NotificationsStore.Intent.SetCategoryLed(kind, it)) },
    )
    item {
        SettingsTile(
            icon = Icons.Outlined.Notifications,
            title = stringResource(R.string.settings_notifications_priority),
            subtitle = if (priority) {
                stringResource(R.string.settings_notifications_priority_high)
            } else {
                stringResource(R.string.settings_notifications_priority_default)
            },
            iconColor = MaterialTheme.colorScheme.primary,
            position = ItemPosition.MIDDLE,
            onClick = {
                component.onNotification(NotificationsStore.Intent.SetCategoryPriority(kind, !priority))
            },
        )
    }
    switchValueRow(
        titleRes = R.string.settings_notifications_popup,
        checked = popup,
        position = ItemPosition.BOTTOM,
        onToggle = { component.onNotification(NotificationsStore.Intent.SetCategoryPopup(kind, it)) },
    )
    item { SectionHeader(stringResource(R.string.settings_notifications_exceptions)) }
    item {
        SettingsTile(
            icon = Icons.Outlined.Notifications,
            title = stringResource(R.string.settings_notifications_exceptions_count, state.exceptions.size),
            subtitle = stringResource(R.string.settings_notifications_exceptions_sub),
            iconColor = MaterialTheme.colorScheme.secondary,
            position = ItemPosition.STANDALONE,
            onClick = onOpenExceptions,
        )
    }
}

internal fun LazyListScope.notificationExceptionItems(
    component: SettingsComponent,
    state: NotificationsStore.State,
    onEditChat: (Long) -> Unit = {},
) {
    val now = (System.currentTimeMillis() / 1000L).toInt()
    item { SectionHeader(stringResource(R.string.settings_notifications_exceptions)) }
    if (state.exceptions.isEmpty()) {
        item {
            SettingsTile(
                icon = Icons.Outlined.NotificationsOff,
                title = stringResource(R.string.settings_notifications_exceptions_empty),
                iconColor = MaterialTheme.colorScheme.secondary,
                position = ItemPosition.STANDALONE,
                onClick = { },
            )
        }
    } else {
        state.exceptions.forEachIndexed { index, exception ->
            val title = state.chatsById[exception.chatId.value] ?: exception.chatId.value.toString()
            val muted = exception.settings.isMuted(now)
            val mode = state.peerModes[exception.chatId.value]
            item {
                SettingsTile(
                    icon = if (muted) Icons.Outlined.NotificationsOff else Icons.Outlined.Notifications,
                    title = title,
                    subtitle = chatModeSummary(mode, muted),
                    iconColor = MaterialTheme.colorScheme.secondary,
                    position = position(index, state.exceptions.size),
                    onClick = { onEditChat(exception.chatId.value) },
                )
            }
        }
    }
    if (state.addableChats.isNotEmpty()) {
        item { SectionHeader(stringResource(R.string.settings_notifications_exceptions_add)) }
        state.addableChats.forEachIndexed { index, chat ->
            item {
                SettingsTile(
                    icon = Icons.Outlined.NotificationsOff,
                    title = chat.title.ifBlank { chat.id.value.toString() },
                    iconColor = MaterialTheme.colorScheme.primary,
                    position = position(index, state.addableChats.size),
                    onClick = {
                        component.onNotification(NotificationsStore.Intent.AddException(chat.id.value))
                    },
                )
            }
        }
    }
}

private fun LazyListScope.globalRow(
    component: SettingsComponent,
    kind: String,
    titleRes: Int,
    settings: org.monogram.core.models.NotifySettings,
    now: Int,
    index: Int,
    last: Int,
    onOpenCategory: (String) -> Unit,
) {
    val enabled = settings.muteUntil <= now
    item {
        SettingsTile(
            icon = if (enabled) Icons.Outlined.Notifications else Icons.Outlined.NotificationsOff,
            title = stringResource(titleRes),
            subtitle = stringResource(R.string.settings_notifications_category_sub),
            iconColor = MaterialTheme.colorScheme.primary,
            position = position(index, last + 3),
            onClick = { onOpenCategory(kind) },
            trailingContent = {
                Switch(
                    checked = enabled,
                    onCheckedChange = { component.onNotification(NotificationsStore.Intent.ToggleGlobal(kind, it)) },
                )
            },
        )
    }
}

private fun LazyListScope.switchValueRow(
    titleRes: Int,
    checked: Boolean,
    position: ItemPosition,
    onToggle: (Boolean) -> Unit,
) {
    item {
        SettingsTile(
            icon = Icons.Outlined.Notifications,
            title = stringResource(titleRes),
            iconColor = MaterialTheme.colorScheme.primary,
            position = position,
            onClick = { onToggle(!checked) },
            trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        )
    }
}

private fun LazyListScope.switchRow(
    component: SettingsComponent,
    key: String,
    titleRes: Int,
    checked: Boolean,
    position: ItemPosition,
    icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Outlined.Notifications,
) {
    item {
        SettingsTile(
            icon = icon,
            title = stringResource(titleRes),
            iconColor = MaterialTheme.colorScheme.primary,
            position = position,
            onClick = { component.onNotification(NotificationsStore.Intent.ToggleInApp(key, !checked)) },
            trailingContent = {
                Switch(checked = checked, onCheckedChange = null)
            },
        )
    }
}

private fun position(index: Int, size: Int): ItemPosition = when {
    size <= 1 -> ItemPosition.STANDALONE
    index == 0 -> ItemPosition.TOP
    index == size - 1 -> ItemPosition.BOTTOM
    else -> ItemPosition.MIDDLE
}

/** Mute options offered per chat; [seconds] of 0 clears the mute and [Int.MAX_VALUE] mutes forever. */
private data class ChatMuteChoice(val labelRes: Int, val seconds: Int) {
    fun until(now: Int): Int = if (seconds == Int.MAX_VALUE) Int.MAX_VALUE else now + seconds
}

private val CHAT_MUTE_CHOICES = listOf(
    ChatMuteChoice(R.string.settings_notifications_mute_off, 0),
    ChatMuteChoice(R.string.settings_notifications_mute_1h, 3_600),
    ChatMuteChoice(R.string.settings_notifications_mute_8h, 8 * 3_600),
    ChatMuteChoice(R.string.settings_notifications_mute_1d, 24 * 3_600),
    ChatMuteChoice(R.string.settings_notifications_mute_1w, 7 * 24 * 3_600),
    ChatMuteChoice(R.string.settings_notifications_mute_forever, Int.MAX_VALUE),
)

/** Which chip represents the stored deadline, tolerant to the seconds that passed since it was set. */
private fun selectedMuteChoice(muteUntil: Int, now: Int): Int = when {
    muteUntil == Int.MAX_VALUE -> CHAT_MUTE_CHOICES.lastIndex
    muteUntil <= now -> 0
    muteUntil <= now + 2 * 3_600 -> 1
    muteUntil <= now + 16 * 3_600 -> 2
    muteUntil <= now + 3 * 24 * 3_600 -> 3
    else -> 4
}

@Composable
private fun chatModeSummary(mode: PeerNotificationMode?, serverMuted: Boolean): String {
    val now = (System.currentTimeMillis() / 1000L).toInt()
    val parts = buildList {
        if (serverMuted || mode?.isMuted(now) == true) add(stringResource(R.string.settings_notifications_chat_muted))
        if (mode != null && !mode.isDefault) {
            if (!mode.preview) add(stringResource(R.string.settings_notifications_chat_no_preview))
            if (!mode.sound) add(stringResource(R.string.settings_notifications_chat_no_sound))
            if (!mode.popup) add(stringResource(R.string.settings_notifications_chat_no_popup))
        }
    }
    return parts.ifEmpty { listOf(stringResource(R.string.settings_notifications_chat_default)) }.joinToString(" · ")
}

/**
 * Per-chat notification mode: previews, sound, popups and mute. Saved locally per peer, so it
 * applies on top of the server exception that put the chat on this screen. Reset clears both
 * the local mode and the server exception.
 */
@Composable
internal fun NotificationChatModeDialog(
    component: SettingsComponent,
    state: NotificationsStore.State,
    chatId: Long,
    onDismiss: () -> Unit,
) {
    val title = state.chatsById[chatId] ?: chatId.toString()
    val mode = state.peerModes[chatId] ?: PeerNotificationMode.Default
    val now = (System.currentTimeMillis() / 1000L).toInt()
    val selectedMute = selectedMuteChoice(mode.muteUntil, now)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                modeSwitch(R.string.settings_notifications_preview, mode.preview) { value ->
                    component.onNotification(NotificationsStore.Intent.SetPeerMode(chatId, mode.copy(preview = value)))
                }
                modeSwitch(R.string.settings_notifications_sound, mode.sound) { value ->
                    component.onNotification(NotificationsStore.Intent.SetPeerMode(chatId, mode.copy(sound = value)))
                }
                modeSwitch(R.string.settings_notifications_popup, mode.popup) { value ->
                    component.onNotification(NotificationsStore.Intent.SetPeerMode(chatId, mode.copy(popup = value)))
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.settings_notifications_chat_mute),
                    style = MaterialTheme.typography.labelLarge,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CHAT_MUTE_CHOICES.forEachIndexed { index, choice ->
                        FilterChip(
                            selected = index == selectedMute,
                            onClick = {
                                component.onNotification(
                                    NotificationsStore.Intent.SetPeerMode(
                                        chatId,
                                        mode.copy(muteUntil = choice.until(now)),
                                    ),
                                )
                            },
                            label = { Text(stringResource(choice.labelRes)) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_notifications_chat_done)) }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    component.onNotification(NotificationsStore.Intent.ClearPeerMode(chatId))
                    component.onNotification(NotificationsStore.Intent.ResetException(chatId))
                    onDismiss()
                },
            ) {
                Text(stringResource(R.string.settings_notifications_exceptions_reset))
            }
        },
    )
}

@Composable
private fun modeSwitch(labelRes: Int, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(stringResource(labelRes))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

