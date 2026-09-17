package org.monogram.core.models

data class PeerId(val value: Long)

fun peerAvatarCacheKey(peerId: PeerId, stored: String? = null): String =
    stored?.takeIf { it.isNotBlank() } ?: "avatar:${peerId.value}"

data class User(
    val id: PeerId,
    val firstName: String,
    val lastName: String? = null,
    val username: String? = null,
    val phone: String? = null,
)

data class Chat(
    val id: PeerId,
    val title: String,
    val isChannel: Boolean = false,
    val isGroup: Boolean = false,
    val isForum: Boolean = false,
    /** True if the user is not a participant of this dialog (left, kicked or migrated). */
    val left: Boolean = false,
    val unreadCount: Int = 0,
    val lastMessagePreview: String? = null,
    val lastMessageOutgoing: Boolean = false,
    val lastMessageSenderName: String? = null,
    val lastMessageMediaKind: String? = null,
    val lastMessageDate: Long? = null,
    val photoCacheKey: String? = null,
    val archived: Boolean = false,
    val muted: Boolean = false,
    /**
     * The dialog carries its own mute setting. When false the dialog inherits the peer type
     * default from `account.getNotifySettings` (users / chats / broadcasts), which is what
     * Telegram clients and the mute icon in the chat list use.
     */
    val muteOverride: Boolean = false,
    /** `dialog.unread_mark`: manually marked unread, independently of [unreadCount]. */
    val unreadMark: Boolean = false,
    /** `dialog.unread_mentions_count`. */
    val unreadMentionsCount: Int = 0,
    /** `dialog.unread_reactions_count`. */
    val unreadReactionsCount: Int = 0,
    val isContact: Boolean = false,
    val isBot: Boolean = false,
    val pinned: Boolean = false,
    val pinnedOrder: Int = Int.MAX_VALUE,
    val readInboxMaxId: Int = 0,
    val readOutboxMaxId: Int = 0,
    val peerStatus: String? = null,
    val peerStatusAt: Long? = null,
    val typing: Boolean = false,
    val typingName: String? = null,
    val typingAction: String? = null,
    val lastMediaThumbCacheKey: String? = null,
    val lastMessageId: Int = 0,
    val dialogScrollMessageId: Int? = null,
    val canView: Boolean = true,
    val canSendPlain: Boolean = true,
    val canSendPhotos: Boolean = true,
    val canForward: Boolean = true,
    val canDeleteOthers: Boolean = false,
    val emojiStatusDocumentId: Long? = null,
    val isVerified: Boolean = false,
)

fun isPlaceholderPeerTitle(title: String, peerId: Long): Boolean {
    val t = title.trim()
    if (t.isEmpty()) return true
    val signed = peerId.toString()
    val abs = kotlin.math.abs(peerId).toString()
    return t.equals("Chat", true) ||
        t.equals("Channel", true) ||
        t.equals("User", true) ||
        t.equals("Chat $signed", true) ||
        t.equals("Chat $abs", true) ||
        t.equals("Channel $signed", true) ||
        t.equals("Channel $abs", true) ||
        t.equals("User $signed", true) ||
        t.equals("User $abs", true)
}

fun preferredPeerTitle(incoming: String, previous: String?, peerId: Long): String {
    val betterPrevious = previous?.takeIf { !isPlaceholderPeerTitle(it, peerId) }
    return if (isPlaceholderPeerTitle(incoming, peerId) && betterPrevious != null) {
        betterPrevious
    } else {
        incoming
    }
}

/** Keeps locally owned fields (scroll, real titles) when a network chat row replaces the Room row. */
fun Chat.mergeLocalCache(stored: Chat?): Chat =
    copy(
        title = preferredPeerTitle(title, stored?.title, id.value),
        dialogScrollMessageId = dialogScrollMessageId ?: stored?.dialogScrollMessageId,
        photoCacheKey = photoCacheKey ?: stored?.photoCacheKey,
        emojiStatusDocumentId = emojiStatusDocumentId ?: stored?.emojiStatusDocumentId,
        readInboxMaxId = maxOf(readInboxMaxId, stored?.readInboxMaxId ?: 0),
        readOutboxMaxId = maxOf(readOutboxMaxId, stored?.readOutboxMaxId ?: 0),
        unreadCount = if (stored != null && readInboxMaxId < stored.readInboxMaxId) {
            stored.unreadCount
        } else {
            unreadCount
        },
        unreadMentionsCount = if (stored != null && readInboxMaxId < stored.readInboxMaxId) {
            stored.unreadMentionsCount
        } else {
            unreadMentionsCount
        },
        unreadReactionsCount = if (stored != null && readInboxMaxId < stored.readInboxMaxId) {
            stored.unreadReactionsCount
        } else {
            unreadReactionsCount
        },
        lastMessageOutgoing = when {
            lastMessageOutgoing -> true
            stored != null && lastMessageId != 0 && lastMessageId == stored.lastMessageId ->
                stored.lastMessageOutgoing
            else -> false
        },
        lastMessageMediaKind = lastMessageMediaKind ?: stored?.takeIf {
            lastMessageId != 0 && lastMessageId == it.lastMessageId
        }?.lastMessageMediaKind,
        lastMessageSenderName = lastMessageSenderName ?: stored?.takeIf {
            lastMessageId != 0 && lastMessageId == it.lastMessageId
        }?.lastMessageSenderName,
        lastMessagePreview = lastMessagePreview ?: stored?.takeIf {
            lastMessageId != 0 && lastMessageId == it.lastMessageId
        }?.lastMessagePreview,
        archived = if (stored != null && isPlaceholderPeerTitle(title, id.value)) {
            stored.archived
        } else {
            archived
        },
        left = if (stored != null && isPlaceholderPeerTitle(title, id.value)) {
            stored.left
        } else {
            left
        },
    )

data class MessageId(val chatId: PeerId, val id: Int)

data class TextEntity(
    val kind: String,
    val offset: Int,
    val length: Int,
    val url: String? = null,
)

data class Message(
    val id: MessageId,
    val senderId: PeerId?,
    val text: String?,
    val date: Long,
    val editDate: Long? = null,
    val outgoing: Boolean,
    val mediaCacheKey: String? = null,
    /** "photo" | "document" | "sticker" | "sticker_animated" | "video" | "gif" | "audio" | "voice" | null */
    val mediaKind: String? = null,
    val thumbCacheKey: String? = null,
    val mediaDuration: Int? = null,
    val mediaWidth: Int? = null,
    val mediaHeight: Int? = null,
    val groupedId: Long? = null,
    val fileName: String? = null,
    val fileSize: Long? = null,
    val reactionsJson: String? = null,
    val repliesCount: Int = 0,
    val discussionPeerId: Long? = null,
    val pending: Boolean = false,
    val randomId: Long? = null,
    val read: Boolean = false,
    val failed: Boolean = false,
    val replyQuote: String? = null,
    val entities: List<TextEntity> = emptyList(),
    val noforwards: Boolean = false,
    val replyToMsgId: Int? = null,
    val replyToTopId: Int? = null,
    val fwdFrom: String? = null,
    val fwdFromId: Long? = null,
    val fwdDate: Long? = null,
    val viaBot: String? = null,
    val senderName: String? = null,
    val senderEmojiStatusDocumentId: Long? = null,
    val replyMarkup: ReplyMarkup? = null,
    val checklist: Checklist? = null,
)

data class Folder(
    val id: Int,
    val title: String,
    val emoticon: String = "",
    val chatIds: List<PeerId> = emptyList(),
    /** `dialogFilter.pinned_peers` for this folder; empty means no folder-specific pins. */
    val pinnedChatIds: List<PeerId> = emptyList(),
    val excludeChatIds: List<PeerId> = emptyList(),
    val includeContacts: Boolean = false,
    val includeNonContacts: Boolean = false,
    val includeGroups: Boolean = false,
    val includeChannels: Boolean = false,
    val includeBots: Boolean = false,
    val excludeMuted: Boolean = false,
    val excludeRead: Boolean = false,
    val excludeArchived: Boolean = false,
) {
    val label: String
        get() = if (emoticon.isBlank()) title else "$emoticon $title"
}

data class AuthSession(
    val userId: PeerId,
    val dcId: Int,
)

data class Profile(
    val id: PeerId,
    val kind: String,
    val title: String,
    val username: String? = null,
    val about: String? = null,
    val avatarCacheKey: String? = null,
    val isSelf: Boolean = false,
    val status: String? = null,
    val statusAt: Long? = null,
    val phone: String? = null,
    val membersCount: Int? = null,
    val onlineCount: Int? = null,
    val commonChatsCount: Int? = null,
    val isBot: Boolean = false,
    val isVerified: Boolean = false,
    val isScam: Boolean = false,
    val isPremium: Boolean = false,
    val emojiStatusDocumentId: Long? = null,
    /** Channels only: whether the server allows listing participants. `null` = unknown. */
    val canViewParticipants: Boolean? = null,
)

data class StickerPack(
    val id: Long,
    val title: String,
    val shortName: String,
    val count: Int,
    val isEmoji: Boolean,
    val previewDocumentIds: List<Long> = emptyList(),
    val accessHash: Long = 0L,
)

data class StickerCatalog(
    val hash: Long,
    val notModified: Boolean,
    val sets: List<StickerPack> = emptyList(),
)

data class StickerList(
    val hash: Long,
    val notModified: Boolean,
    val documentIds: List<Long> = emptyList(),
)

data class ResolvedPeer(
    val peerId: PeerId,
    val username: String? = null,
    val title: String,
    val isBot: Boolean = false,
)

data class SearchPeer(
    val id: PeerId,
    val title: String,
    val username: String? = null,
    val kind: String,
    val isBot: Boolean = false,
    val isGroup: Boolean = false,
    val isChannel: Boolean = false,
)

data class ContactsSearch(
    val people: List<SearchPeer> = emptyList(),
    val chats: List<SearchPeer> = emptyList(),
)

data class GlobalMessageSearch(
    val messages: List<Message> = emptyList(),
    val nextRate: Int = 0,
    val nextPeerId: PeerId = PeerId(0),
    val nextOffsetId: Int = 0,
)

data class InlineBotResult(
    val id: String,
    val kind: String,
    val title: String? = null,
    val description: String? = null,
    val url: String? = null,
    val documentId: Long? = null,
    val thumbCacheKey: String? = null,
) {
    /** Indexed as `(documentId, 0)` or `(photoId, 0)` in the native media map. */
    fun previewLookupId(): Long? =
        documentId ?: thumbCacheKey
            ?.substringAfter("photo:", missingDelimiterValue = "")
            ?.substringBefore(':')
            ?.toLongOrNull()
}

data class InlineBotResults(
    val queryId: Long,
    val gallery: Boolean,
    val nextOffset: String? = null,
    val cacheTime: Int,
    val results: List<InlineBotResult> = emptyList(),
    // Native MessagesBotResults.switch_pm / switch_webview are not mapped through UniFFI yet.
)

data class UploadItem(
    val path: String,
    val kind: String,
    val mimeType: String = "",
    val fileName: String = "",
    val caption: String = "",
    val duration: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val randomId: Long = 0L,
)

sealed class AuthState {
    data object LoggedOut : AuthState()
    data class AwaitingCode(
        val phone: String,
        val phoneCodeHash: String,
        val codeType: String = "",
    ) : AuthState()
    data object AwaitingPassword : AuthState()
    data class Authorized(val session: AuthSession) : AuthState()
}
