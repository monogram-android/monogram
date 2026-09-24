package org.monogram.feature.dialog.store

import org.monogram.core.common.telegram.TelegramError
import org.monogram.core.models.Chat
import org.monogram.core.models.ChatActionKind
import org.monogram.core.models.ForumTopic
import org.monogram.core.models.InlineBotResults
import org.monogram.core.models.Message
import org.monogram.core.models.MessageViewers
import org.monogram.core.models.OutboxReadState
import org.monogram.core.models.PeerId
import org.monogram.core.models.Profile
import org.monogram.core.models.ReactionChoice
import org.monogram.core.models.SavedGif
import org.monogram.core.models.StickerPack
import org.monogram.core.models.UploadItem
import org.monogram.feature.dialog.DraftMention
import org.monogram.feature.dialog.InlineBotQuery
import org.monogram.feature.dialog.MentionCandidate

internal sealed interface Msg {
    data class Loading(val value: Boolean) : Msg
    data class LoadingOlder(val value: Boolean, val prefetch: Boolean = false) : Msg
    data class LoadingNewer(val value: Boolean) : Msg
    data class Sending(val value: Boolean) : Msg
    data class Title(val value: String) : Msg
    data class IsSelf(val value: Boolean) : Msg
    data class IsGroup(val value: Boolean) : Msg
    data class IsChannel(val value: Boolean) : Msg
    data class Senders(val value: Map<PeerId, Profile>) : Msg
    data class SenderTags(val value: Map<PeerId, String>) : Msg
    data class Draft(val value: String) : Msg
    data class PendingAttach(val items: List<UploadItem>) : Msg
    data class ReplyTo(val value: Message?, val focusComposer: Boolean = false) : Msg
    data class Editing(val value: Message?) : Msg
    data class ForwardMessage(val value: Message?) : Msg
    data class ForwardTargets(val value: List<Chat>) : Msg
    data class ForwardQuery(val value: String) : Msg
    data class Forwarding(val value: Boolean) : Msg
    data class ForwardHint(val value: String?) : Msg
    data class Rights(
        val canView: Boolean,
        val canSendPlain: Boolean,
        val canSendPhotos: Boolean,
        val canForward: Boolean,
        val canDeleteOthers: Boolean,
    ) : Msg
    data class SearchQuery(val value: String) : Msg
    data class Searching(val value: Boolean) : Msg
    data class Messages(
        val value: List<Message>,
        val fromCache: Boolean,
        val replace: Boolean = false,
        val liveEdge: Boolean = false,
        val searchHit: Boolean = false,
    ) : Msg
    data class Prepend(val value: List<Message>) : Msg
    data class AppendOlder(val value: List<Message>) : Msg
    data class ReplacePending(val pendingId: Int, val sent: Message) : Msg
    data class BindPending(val randomId: Long, val messageId: Int) : Msg
    data class MessageEdited(val value: Message) : Msg
    data class MessagesDeleted(val ids: Set<Int>) : Msg
    data class Append(val value: Message) : Msg
    data class HasOlder(val value: Boolean) : Msg
    data class HasNewer(val value: Boolean) : Msg
    data class PinnedListOpen(val value: Boolean) : Msg
    data class UnreadCount(val value: Int) : Msg
    data class UnreadMentionsCount(val value: Int) : Msg
    data class UnreadReactionsCount(val value: Int) : Msg
    data class ReadInbox(val value: Int) : Msg
    data class Pinned(val value: List<Message>) : Msg
    data class PinnedIndex(val value: Int) : Msg
    data class Error(val value: TelegramError?) : Msg
    data class Typing(
        val userId: PeerId,
        val name: String?,
        val active: Boolean,
        val action: String = ChatActionKind.Typing.wire,
    ) : Msg
    data class PeerStatus(val status: String?, val at: Long?) : Msg
    data class EmojiStatus(val documentId: Long?) : Msg
    data class ChatProfile(
        val members: Int?,
        val online: Int?,
        val authoritative: Boolean = false,
    ) : Msg
    data class ReadOutbox(val value: Int) : Msg
    data class ReadReceiptConfig(val value: org.monogram.core.models.ReadReceiptConfig) : Msg
    data class Viewers(val messageId: Int, val value: MessageViewers) : Msg
    data class ReactionUsers(
        val messageId: Int,
        val value: List<org.monogram.core.models.MessageViewer>,
    ) : Msg
    data class PollVoters(
        val messageId: Int,
        val value: List<org.monogram.core.models.MessageViewer>,
    ) : Msg
    data class OutboxRead(val messageId: Int, val value: OutboxReadState) : Msg
    data class MarkFailed(val pendingId: Int) : Msg
    data class Drop(val messageId: Int) : Msg
    data class PhotoCacheKey(val value: String?) : Msg
    data class Anchor(val value: Int?, val atTop: Boolean = false) : Msg
    data class SavedGifs(val value: List<SavedGif>, val error: Boolean = false) : Msg
    data class SavedGifsLoaded(val value: Boolean) : Msg
    data class GifPicker(val open: Boolean) : Msg
    data class ComposerPanel(val value: String?) : Msg
    data class EmojiTab(val value: String) : Msg
    data class StickerSets(val value: List<StickerPack>, val error: Boolean = false) : Msg
    data class EmojiSets(val value: List<StickerPack>, val error: Boolean = false) : Msg
    data class StickerPackLoading(val setId: Long) : Msg
    data class StickerPackLoaded(val value: StickerPack) : Msg
    data class StickerPackFailed(val setId: Long) : Msg
    data class Reactions(val messageId: Int, val json: String) : Msg
    data class PendingChat(val chatId: PeerId?, val messageId: Int?) : Msg
    data class DiscussionUnread(val value: Int) : Msg
    data class RecentReactions(val value: List<ReactionChoice>) : Msg
    data class IsForum(val value: Boolean) : Msg
    data class Topics(
        val value: List<ForumTopic>,
        val count: Int,
        val append: Boolean = false,
    ) : Msg
    data class LoadingTopics(val value: Boolean) : Msg
    data class HasMoreTopics(val value: Boolean) : Msg
    data class TopicClosed(val value: Boolean) : Msg
    data class TopicHeader(
        val title: String,
        val iconColor: Int,
        val iconEmojiId: Long?,
    ) : Msg
    data class BotNotice(val text: String?, val alert: Boolean = false) : Msg
    data class BotUrl(val value: String?) : Msg
    data class CopyText(val value: String?) : Msg
    data class KeyboardDismissedKey(val value: String?) : Msg
    data class InlineQuery(val value: InlineBotQuery?) : Msg
    data class InlinePage(
        val results: InlineBotResults?,
        val loading: Boolean = false,
        val error: Boolean = false,
    ) : Msg
    data class MentionQuery(val value: String?) : Msg
    data class MentionPage(
        val candidates: List<MentionCandidate>,
        val loading: Boolean = false,
        val hasMore: Boolean = false,
    ) : Msg
    data class DraftMentions(val value: List<DraftMention>) : Msg
    data class LinkPreview(
        val preview: org.monogram.core.models.WebpagePreview?,
        val url: String? = null,
        val fixed: Boolean = false,
        val loading: Boolean = false,
        val hidden: Boolean = false,
    ) : Msg
}
