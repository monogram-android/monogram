package org.monogram.core.common.push

import org.monogram.core.models.NotifyException
import org.monogram.core.models.NotifySettings
import org.monogram.core.models.PeerId

data class NotificationPolicyState(
    val users: NotifySettings = NotifySettings(),
    val chats: NotifySettings = NotifySettings(),
    val broadcasts: NotifySettings = NotifySettings(),
    val exceptions: Map<Long, NotifySettings> = emptyMap(),
    val peerModes: Map<Long, PeerNotificationMode> = emptyMap(),
    val folderMutedChatIds: Set<Long> = emptySet(),
    val storiesEnabled: Boolean = true,
    val reactionsEnabled: Boolean = true,
    val pinnedEnabled: Boolean = true,
    val contactJoinedEnabled: Boolean = true,
    val giftsEnabled: Boolean = true,
    val inAppSound: Boolean = true,
    val inAppVibrate: Boolean = true,
    val inAppPreview: Boolean = true,
    val inChatSound: Boolean = true,
    val inAppPriority: Boolean = true,
    val popupUsers: Boolean = true,
    val popupChats: Boolean = true,
    val popupBroadcasts: Boolean = true,
    val popupStories: Boolean = true,
    val popupReactions: Boolean = true,
    val badgeMuted: Boolean = true,
    val showPreview: Boolean = true,
)

data class NotificationDecision(
    val show: Boolean,
    val preview: Boolean,
    val sound: Boolean,
    val vibrate: Boolean,
    val popup: Boolean,
    /** Whether this notification contributes to the launcher badge (muted chats can be excluded). */
    val badge: Boolean,
    val channelKind: PushChannelKind,
)

fun decideNotification(
    payload: PushPayload,
    state: NotificationPolicyState,
    nowSeconds: Int,
    appInForeground: Boolean,
    openChatId: Long? = null,
): NotificationDecision {
    if (payload.action != PushAction.Show) {
        return NotificationDecision(false, false, false, false, false, false, payload.channelKind)
    }
    if (payload.locKey.startsWith("STORY_") && !state.storiesEnabled) {
        return hidden(payload.channelKind)
    }
    if (payload.locKey.contains("REACT") && !state.reactionsEnabled) {
        return hidden(payload.channelKind)
    }
    if (payload.locKey.startsWith("PINNED_") && !state.pinnedEnabled) {
        return hidden(payload.channelKind)
    }
    if (payload.locKey == "CONTACT_JOINED" && !state.contactJoinedEnabled) {
        return hidden(payload.channelKind)
    }
    val chatId = payload.chatId
    if (chatId != null && chatId == openChatId && appInForeground) {
        return NotificationDecision(
            show = false,
            preview = false,
            sound = state.inChatSound && !payload.silent,
            vibrate = false,
            popup = false,
            badge = false,
            channelKind = payload.channelKind,
        )
    }
    if (chatId != null && chatId in state.folderMutedChatIds) {
        return hidden(payload.channelKind)
    }
    val mode = chatId?.let { state.peerModes[it] }
    val settings = settingsFor(chatId, payload.channelKind, state)
    if ((settings.isMuted(nowSeconds) || mode?.isMuted(nowSeconds) == true) && !payload.mention) {
        return hidden(payload.channelKind)
    }
    val preview = state.showPreview && settings.showPreviews && state.inAppPreview && (mode?.preview ?: true)
    val sound = !payload.silent && !settings.silent && (mode?.sound ?: true) && (!appInForeground || state.inAppSound)
    val vibrate = !payload.silent && (!appInForeground || state.inAppVibrate)
    // A chat mode overrides the category; silently posted messages and a foreground app without
    // in-app priority never pop up.
    val categoryPopup = when (payload.channelKind) {
        PushChannelKind.Private -> state.popupUsers
        PushChannelKind.Group -> state.popupChats
        PushChannelKind.Channel -> state.popupBroadcasts
        PushChannelKind.Stories -> state.popupStories
        PushChannelKind.Reactions -> state.popupReactions
        PushChannelKind.Other -> state.popupUsers
    }
    val popup = !payload.silent && (mode?.popup ?: (categoryPopup && (!appInForeground || state.inAppPriority)))
    // "Include muted chats in the badge counter": a muted chat that still posts (a mention, or a
    // showed notification) contributes nothing when the setting is off.
    val muted = settings.isMuted(nowSeconds) || mode?.isMuted(nowSeconds) == true
    val badge = state.badgeMuted || !muted
    return NotificationDecision(true, preview, sound, vibrate, popup, badge, payload.channelKind)
}

fun settingsFor(
    chatId: Long?,
    kind: PushChannelKind,
    state: NotificationPolicyState,
): NotifySettings {
    if (chatId != null) {
        state.exceptions[chatId]?.let { return it }
    }
    return when (kind) {
        PushChannelKind.Private -> state.users
        PushChannelKind.Group -> state.chats
        PushChannelKind.Channel -> state.broadcasts
        PushChannelKind.Stories -> state.broadcasts
        PushChannelKind.Reactions -> state.broadcasts
        PushChannelKind.Other -> state.users
    }
}

fun folderMemberIds(
    chatIds: List<PeerId>,
    excludeChatIds: List<PeerId>,
): Set<Long> = chatIds.map { it.value }.toSet() - excludeChatIds.map { it.value }.toSet()

private fun hidden(kind: PushChannelKind) =
    NotificationDecision(false, false, false, false, false, false, kind)
