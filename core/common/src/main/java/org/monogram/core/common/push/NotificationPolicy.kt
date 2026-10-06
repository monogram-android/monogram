package org.monogram.core.common.push

import org.monogram.core.models.Chat
import org.monogram.core.models.Folder
import org.monogram.core.models.NotifySettings
import org.monogram.core.models.PeerId
import org.monogram.core.models.contains

data class NotificationPolicyState(
    val users: NotifySettings = NotifySettings(),
    val chats: NotifySettings = NotifySettings(),
    val broadcasts: NotifySettings = NotifySettings(),
    val exceptions: Map<Long, NotifySettings> = emptyMap(),
    val topicExceptions: Map<Pair<Long, Int>, NotifySettings> = emptyMap(),
    val peerKinds: Map<Long, PushChannelKind> = emptyMap(),
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
    val callsVibrate: String = "default",
    val callsRingtone: String = "default",
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
    openTopicId: Int? = null,
): NotificationDecision {
    val kind = when (payload.channelKind) {
        PushChannelKind.Private, PushChannelKind.Group, PushChannelKind.Channel, PushChannelKind.Other ->
            state.peerKinds[payload.chatId] ?: payload.channelKind

        else -> payload.channelKind
    }
    if (payload.action != PushAction.Show) {
        return NotificationDecision(false, false, false, false, false, false, kind)
    }
    if (payload.locKey.startsWith("STORY_") && !state.storiesEnabled) {
        return hidden(kind)
    }
    if (payload.locKey.contains("REACT") && !state.reactionsEnabled) {
        return hidden(kind)
    }
    if (payload.locKey.startsWith("PINNED_") && !state.pinnedEnabled) {
        return hidden(kind)
    }
    if (payload.locKey == "CONTACT_JOINED" && !state.contactJoinedEnabled) {
        return hidden(kind)
    }
    if (payload.locKey.contains("GIFT") && !state.giftsEnabled) {
        return hidden(kind)
    }
    val chatId = payload.chatId
    if (chatId != null && chatId == openChatId && payload.topicId == openTopicId && appInForeground) {
        return NotificationDecision(
            show = false,
            preview = false,
            sound = state.inChatSound && !payload.silent,
            vibrate = false,
            popup = false,
            badge = false,
            channelKind = kind,
        )
    }
    if (chatId != null && chatId in state.folderMutedChatIds) {
        return hidden(kind)
    }
    val mode = chatId?.let { state.peerModes[it] }
    if (payload.locKey.startsWith("PINNED_") && mode?.pinned == false) return hidden(kind)
    val settings =
        payload.topicId?.let { topic -> chatId?.let { state.topicExceptions[it to topic] } }
            ?: settingsFor(chatId, kind, state)
    if (kind == PushChannelKind.Stories && settings.storiesMuted) return hidden(kind)
    val mentionAllowed = payload.mention && mode?.mentions != false
    if ((settings.isMuted(nowSeconds) || mode?.isMuted(nowSeconds) == true) && !mentionAllowed) {
        return hidden(kind)
    }
    val parentSettings = settingsFor(chatId, kind, state)
    val categorySettings = settingsFor(chatId, kind, state.copy(exceptions = emptyMap()))
    val parentPreview = if (parentSettings.previewInherited) categorySettings.showPreviews
    else parentSettings.showPreviews
    val settingsPreview = if (settings.previewInherited) parentPreview else settings.showPreviews
    val preview = state.showPreview && settingsPreview &&
            (!appInForeground || state.inAppPreview) && (mode?.preview ?: true)
    val calls = kind == PushChannelKind.Calls
    val sound = !payload.silent && !settings.silent && (mode?.sound ?: true) &&
        (!appInForeground || state.inAppSound) && !(calls && state.callsRingtone == "none")
    val vibrate = !payload.silent && (!appInForeground || state.inAppVibrate) &&
        !(calls && state.callsVibrate == "off")
    // A chat mode overrides the category; silently posted messages and a foreground app without
    // in-app priority never pop up.
    val categoryPopup = when (kind) {
        PushChannelKind.Private -> state.popupUsers
        PushChannelKind.Group -> state.popupChats
        PushChannelKind.Channel -> state.popupBroadcasts
        PushChannelKind.Stories -> state.popupStories
        PushChannelKind.Reactions -> state.popupReactions
        PushChannelKind.Calls -> true
        PushChannelKind.Other -> state.popupUsers
    }
    val popup = !payload.silent && (mode?.popup ?: (categoryPopup && (!appInForeground || state.inAppPriority)))
    // "Include muted chats in the badge counter": a muted chat that still posts (a mention, or a
    // showed notification) contributes nothing when the setting is off.
    val muted = settings.isMuted(nowSeconds) || mode?.isMuted(nowSeconds) == true
    val badge = state.badgeMuted || !muted
    return NotificationDecision(true, preview, sound, vibrate, popup, badge, kind)
}

fun settingsFor(
    chatId: Long?,
    kind: PushChannelKind,
    state: NotificationPolicyState,
): NotifySettings {
    if (chatId != null) {
        state.exceptions[chatId]?.let { return it }
    }
    val peerKind = chatId?.let { state.peerKinds[it] } ?: when {
        chatId != null && chatId > 0 -> PushChannelKind.Private
        chatId != null && chatId > -CHANNEL_ID_OFFSET -> PushChannelKind.Group
        chatId != null && chatId <= -CHANNEL_ID_OFFSET && kind != PushChannelKind.Group -> PushChannelKind.Channel
        kind == PushChannelKind.Stories || kind == PushChannelKind.Reactions -> PushChannelKind.Channel
        else -> kind
    }
    return when (peerKind) {
        PushChannelKind.Private -> state.users
        PushChannelKind.Group -> state.chats
        PushChannelKind.Channel -> state.broadcasts
        PushChannelKind.Stories -> state.broadcasts
        PushChannelKind.Reactions -> state.broadcasts
        PushChannelKind.Calls -> state.users
        PushChannelKind.Other -> state.users
    }
}

fun folderMemberIds(
    chatIds: List<PeerId>,
    excludeChatIds: List<PeerId>,
): Set<Long> = chatIds.map { it.value }.toSet() - excludeChatIds.map { it.value }.toSet()

fun folderMemberIds(folder: Folder, chats: List<Chat>): Set<Long> =
    folderMemberIds(folder.chatIds, folder.excludeChatIds) + chats.filter { folder.contains(it) }
        .map { it.id.value }

fun notificationPeerKind(chat: Chat): PushChannelKind = when {
    chat.isGroup -> PushChannelKind.Group
    chat.isChannel -> PushChannelKind.Channel
    else -> PushChannelKind.Private
}

private fun hidden(kind: PushChannelKind) =
    NotificationDecision(false, false, false, false, false, false, kind)
