package org.monogram.network.bridge

import org.monogram.core.models.Chat
import org.monogram.core.models.Folder
import org.monogram.core.models.Message
import org.monogram.core.models.PeerId
import org.monogram.network.bridge.chat.ChatOps
import org.monogram.network.bridge.media.MediaOps
import org.monogram.network.bridge.message.MessageOps
import org.monogram.network.bridge.notify.NotifyOps
import org.monogram.network.bridge.profile.ProfileOps
import org.monogram.network.bridge.session.SessionOps
import org.monogram.network.bridge.updates.UpdatesOps
import org.monogram.network.bridge.web.WebOps

interface MtprotoClient : SessionOps, ChatOps, MessageOps, ProfileOps, MediaOps, WebOps, NotifyOps,
    UpdatesOps

data class UpdatesCursor(
    val pts: Int,
    val qts: Int,
    val date: Int,
    val seq: Int,
)

sealed class MtprotoUpdate {
    data class ChatsChanged(val chats: List<Chat>) : MtprotoUpdate()
    data class NewMessage(val message: Message) : MtprotoUpdate()
    data class MessageEdited(val message: Message) : MtprotoUpdate()
    data class MessagesDeleted(val chatId: PeerId?, val messageIds: List<Int>) : MtprotoUpdate()
    data class FoldersChanged(val folders: List<Folder>) : MtprotoUpdate()
    data class PeerTyping(
        val chatId: PeerId,
        val userId: PeerId,
        val typing: Boolean,
        val action: String = "",
    ) : MtprotoUpdate()

    data class PeerStatus(
        val userId: PeerId,
        val status: String?,
        val statusAt: Long?,
    ) : MtprotoUpdate()

    data class PeerEmojiStatus(
        val userId: PeerId,
        val documentId: Long?,
    ) : MtprotoUpdate()

    data class ReadInbox(
        val chatId: PeerId,
        val maxId: Int,
        val stillUnread: Int,
    ) : MtprotoUpdate()

    data class ReadHistoryConfirmed(val chatId: PeerId, val maxId: Int) : MtprotoUpdate()

    /** A dialog's manual unread mark changed; emitted locally and by `updateDialogUnreadMark`. */
    data class DialogUnreadMark(val chatId: PeerId, val unread: Boolean) : MtprotoUpdate()

    /**
     * Notification settings changed: the per-type defaults (`users` / `chats` / `broadcasts` with
     * [chatId] 0, cached defaults must be re-read) or one peer ([peerKind] `peer`, whose dialog row
     * has to show the bell right away instead of waiting for the next dialog page).
     */
    data class NotifySettingsChanged(
        val peerKind: String,
        val chatId: PeerId,
        val muteUntil: Int,
    ) : MtprotoUpdate()

    data class ReadOutbox(
        val chatId: PeerId,
        val maxId: Int,
    ) : MtprotoUpdate()

    data class Ignored(val kind: String) : MtprotoUpdate()
    data object SavedGifsChanged : MtprotoUpdate()
    data class AccountPremium(val isPremium: Boolean) : MtprotoUpdate()
    data class MessageReactions(
        val chatId: PeerId,
        val messageId: Int,
        val reactionsJson: String,
    ) : MtprotoUpdate()

    data class DiscussionInbox(
        val channelId: PeerId,
        val topMessageId: Int,
        val readMaxId: Int,
    ) : MtprotoUpdate()

    /** Remaining unread mentions for a dialog. [stillUnread] is absolute. */
    data class UnreadMentions(val chatId: PeerId, val stillUnread: Int) : MtprotoUpdate()

    /** Remaining unread reactions for a dialog. [stillUnread] is absolute. */
    data class UnreadReactions(val chatId: PeerId, val stillUnread: Int) : MtprotoUpdate()

    data class UnreadMentionsDelta(val chatId: PeerId, val delta: Int) : MtprotoUpdate()
    data class UnreadReactionsDelta(val chatId: PeerId, val delta: Int) : MtprotoUpdate()
}