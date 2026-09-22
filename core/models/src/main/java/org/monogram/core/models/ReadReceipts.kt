package org.monogram.core.models

// https://core.telegram.org/method/messages.getMessageReadParticipants
// https://core.telegram.org/method/messages.getOutboxReadDate
data class ReadReceiptConfig(
    val chatReadMarkSizeThreshold: Int = DEFAULT_CHAT_READ_MARK_SIZE_THRESHOLD,
    val chatReadMarkExpirePeriod: Int = DEFAULT_EXPIRE_PERIOD,
    val pmReadDateExpirePeriod: Int = DEFAULT_EXPIRE_PERIOD,
    val fromServer: Boolean = false,
) {
    companion object {
        const val DEFAULT_CHAT_READ_MARK_SIZE_THRESHOLD: Int = 100
        const val DEFAULT_EXPIRE_PERIOD: Int = 604_800
        val Fallback = ReadReceiptConfig()
    }
}

data class MessageViewer(
    val peerId: PeerId,
    val date: Long,
    val title: String? = null,
    val avatarCacheKey: String? = null,
    val emoticon: String? = null,
    val documentId: Long? = null,
    val pollOptionHex: List<String> = emptyList(),
)

sealed interface MessageViewers {
    data object Loading : MessageViewers

    data class Ready(val viewers: List<MessageViewer>, val played: Boolean) : MessageViewers

    data object Expired : MessageViewers

    data object TooBig : MessageViewers

    data object Unavailable : MessageViewers
}

sealed interface OutboxReadState {
    data object Loading : OutboxReadState

    data class Read(val date: Long) : OutboxReadState

    data object Unread : OutboxReadState

    data object Expired : OutboxReadState

    data object PeerPrivacyHidden : OutboxReadState

    data object MyPrivacyHidden : OutboxReadState

    data object Unavailable : OutboxReadState
}

fun playedMediaKind(mediaKind: String?): Boolean =
    mediaKind == "voice" || mediaKind == "audio" || mediaKind == "video_note"

fun canShowMessageViewers(
    message: Message,
    isGroup: Boolean,
    isChannel: Boolean,
    isSelf: Boolean,
    membersCount: Int?,
    config: ReadReceiptConfig,
    nowSeconds: Long,
): Boolean {
    if (!message.outgoing) return false
    if (isSelf) return false
    if (!isGroup || isChannel) return false
    if (message.pending || message.failed) return false
    if (message.id.id <= 0) return false
    if (isServiceMessage(message)) return false
    val count = membersCount ?: return false
    if (count <= 0 || count > config.chatReadMarkSizeThreshold) return false
    val age = nowSeconds - message.date
    if (age < 0 || age >= config.chatReadMarkExpirePeriod) return false
    return true
}

fun canShowOutboxReadDate(
    message: Message,
    isGroup: Boolean,
    isChannel: Boolean,
    isSelf: Boolean,
    readOutboxMaxId: Int,
    config: ReadReceiptConfig,
    nowSeconds: Long,
): Boolean {
    if (!message.outgoing) return false
    if (isGroup || isChannel || isSelf) return false
    if (message.pending || message.failed) return false
    if (message.id.id <= 0) return false
    if (isServiceMessage(message)) return false
    val age = nowSeconds - message.date
    if (age < 0 || age >= config.pmReadDateExpirePeriod) return false

    return readOutboxMaxId >= message.id.id
}

internal fun isServiceMessage(message: Message): Boolean =
    message.text?.let { parseServiceMessage(it) != null } == true
