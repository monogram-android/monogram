package org.monogram.core.common.push

object NotificationConversation {
    const val SELF_PERSON_KEY: String = "self"
    const val GROUP_KEY: String = "org.monogram.push.messages"

    fun peerPersonKey(chatId: Long): String = "peer:$chatId"

    fun senderPersonKey(name: String): String = "sender:$name"

    fun isOutgoing(messagePersonKey: String?, userPersonKey: String?): Boolean {
        if (messagePersonKey == SELF_PERSON_KEY) return true
        return messagePersonKey.isNullOrEmpty() && userPersonKey == SELF_PERSON_KEY
    }
}

data class ConversationStyle(
    val messagingStyle: Boolean,
    val shortcutId: String?,
    val personKey: String?,
    val personName: String,
    val locusId: String?,
    val conversationTitle: String?,
    val shortcutLabel: String?,
    val categoryMessage: Boolean,
    val groupKey: String,
    val onlyAlertOnce: Boolean,
    val countsAsNewAlert: Boolean,
    val reply: Boolean,
    val markRead: Boolean,
    val shortcutBeforeNotify: Boolean,
    val messageSenderName: String?,
    val messagePersonKey: String?,
)

fun conversationStyle(
    payload: PushPayload,
    quiet: Boolean,
    lastAlertAtMillis: Long,
    nowMillis: Long,
): ConversationStyle {
    val chat = isConversationChat(payload)
    val chatId = payload.chatId ?: 0L
    val shortcutId = if (chat) "chat:$chatId" else null
    val title = conversationTitle(payload, chat)
    val alert = !quiet && NotificationAlertThrottle.shouldAlert(lastAlertAtMillis, nowMillis)
    val sender = payload.locArgs.firstOrNull()?.takeIf { it.isNotBlank() }
    return ConversationStyle(
        messagingStyle = true,
        shortcutId = shortcutId,
        personKey = if (chat) NotificationConversation.peerPersonKey(chatId) else null,
        personName = title ?: payload.title.ifBlank { sender ?: "" },
        locusId = shortcutId,
        conversationTitle = title,
        shortcutLabel = title ?: payload.title.ifBlank { null },
        categoryMessage = true,
        groupKey = NotificationConversation.GROUP_KEY,
        onlyAlertOnce = !alert,
        countsAsNewAlert = alert,
        reply = chat,
        markRead = chat,
        shortcutBeforeNotify = chat,
        messageSenderName = if (title != null) sender else null,
        messagePersonKey = if (chat) NotificationConversation.peerPersonKey(
            payload.senderId ?: chatId
        ) else null,
    )
}

fun shadeText(preview: Boolean, body: String, hiddenText: String): String =
    if (preview) body else hiddenText

fun mayPostNotifications(sdkInt: Int, permissionGranted: Boolean): Boolean =
    sdkInt < 33 || permissionGranted

/** Most recent chat first. [maxCount] is ShortcutManagerCompat.getMaxShortcutCountPerActivity. */
fun rankedShareChatIds(existing: List<Long>, chatId: Long, maxCount: Int): List<Long> {
    if (maxCount <= 0) return emptyList()
    if (chatId == 0L) return existing.take(maxCount)
    return (listOf(chatId) + existing.filter { it != chatId }).take(maxCount)
}

fun evictedShareChatIds(previous: List<Long>, next: List<Long>): List<Long> =
    previous.filter { it !in next }

fun shouldAttachBubble(enabled: Boolean, conversation: Boolean, demoted: Boolean): Boolean =
    enabled && conversation && !demoted

private fun isConversationChat(payload: PushPayload): Boolean {
    val chatId = payload.chatId ?: return false
    if (chatId == 0L || payload.action != PushAction.Show) return false
    if (payload.channelKind != PushChannelKind.Private &&
        payload.channelKind != PushChannelKind.Group &&
        payload.channelKind != PushChannelKind.Channel
    ) {
        return false
    }
    val key = payload.locKey
    return !(key.startsWith("CONTACT_") || key.startsWith("AUTH_") || key.startsWith("PHONE_"))
}

private fun conversationTitle(payload: PushPayload, chat: Boolean): String? {
    if (!chat || (payload.channelKind == PushChannelKind.Private && payload.topicId == null)) return null
    val base = when (payload.channelKind) {
        PushChannelKind.Group -> payload.locArgs.getOrNull(1)?.takeIf { it.isNotBlank() } ?: payload.title
        PushChannelKind.Channel -> payload.locArgs.firstOrNull()?.takeIf { it.isNotBlank() } ?: payload.title
        else -> payload.title
    }
    val topic = payload.topicTitle?.takeIf { it.isNotBlank() }
    return if (payload.topicId != null && topic != null) "$base · $topic" else base.ifBlank { null }
}
