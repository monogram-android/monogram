package org.monogram.feature.dialog

import com.arkivanov.mvikotlin.core.store.SimpleBootstrapper
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import kotlinx.coroutines.Dispatchers
import org.monogram.core.common.telegram.TelegramError
import org.monogram.core.database.OfflineWarmup
import org.monogram.core.database.SessionMetadataStore
import org.monogram.core.markup.KotlinMarkupParser
import org.monogram.core.markup.MarkupParser
import org.monogram.core.models.Chat
import org.monogram.core.models.ForumIo
import org.monogram.core.models.ForumTopic
import org.monogram.core.models.InlineBotResults
import org.monogram.core.models.Message
import org.monogram.core.models.MessageViewers
import org.monogram.core.models.OutboxReadState
import org.monogram.core.models.PeerId
import org.monogram.core.models.Profile
import org.monogram.core.models.ReactionChoice
import org.monogram.core.models.ReadReceiptConfig
import org.monogram.core.models.ReplyButton
import org.monogram.core.models.SavedGif
import org.monogram.core.models.StickerPack
import org.monogram.core.models.TypingPresence
import org.monogram.core.models.UploadItem
import org.monogram.core.models.WebpagePreview
import org.monogram.feature.dialog.store.DialogExecutor
import org.monogram.feature.dialog.store.DialogReducer
import org.monogram.network.bridge.MtprotoClient
import kotlin.coroutines.CoroutineContext

interface DialogStore : Store<DialogStore.Intent, DialogStore.State, Nothing> {
    sealed interface Intent {
        data object Refresh : Intent
        data object RefreshPresence : Intent
        data object LoadOlder : Intent
        data object LoadNewer : Intent
        data class DraftChanged(val value: String) : Intent
        data class AttachPhoto(val path: String) : Intent
        data class AttachMedia(val items: List<UploadItem>) : Intent
        data class AppendMedia(val items: List<UploadItem>) : Intent
        data object ClearAttach : Intent
        data object FixLinkPreview : Intent
        data object DismissLinkPreview : Intent
        data object RestoreLinkPreview : Intent
        data class SelectLinkPreview(val url: String) : Intent
        data class ReplyTo(val message: Message, val focusComposer: Boolean = false) : Intent
        data object ClearReply : Intent
        data class Edit(val message: Message) : Intent
        data object CancelEdit : Intent
        data class Delete(val messageId: Int, val revoke: Boolean) : Intent
        data class ForwardPick(val message: Message) : Intent
        data object ClearForward : Intent
        data class ForwardQuery(val value: String) : Intent
        data class ForwardTo(val chatId: PeerId) : Intent
        data object ClearForwardHint : Intent
        data class Send(val text: String? = null) : Intent
        data object RetryFailed : Intent
        data class Search(val query: String) : Intent
        data class JumpToDate(val epochSeconds: Int) : Intent
        data object ClearSearch : Intent
        data class SaveScroll(val messageId: Int) : Intent
        data object NextPinned : Intent
        data object OpenPinnedList : Intent
        data object ClosePinnedList : Intent
        data class JumpToPinned(val messageId: Int) : Intent
        data class JumpToMessage(val messageId: Int) : Intent
        data object JumpUnread : Intent
        data object JumpMention : Intent
        data object JumpUnreadReaction : Intent
        data object JumpLatest : Intent
        data object MarkRead : Intent

        /** The user can see messages up to this id in the open history. */
        data class VisibleRead(val messageId: Int, val atLiveEdge: Boolean = false) : Intent
        data class VisibleWindow(val messageIds: Set<Int>) : Intent
        data object ToggleGifPicker : Intent
        data object ToggleAttachSheet : Intent
        data object CloseAttachSheet : Intent
        data object ToggleEmojiPanel : Intent
        data object ReloadPickerContent : Intent
        data class SetEmojiTab(val tab: String) : Intent
        data class InsertEmoji(val glyph: String) : Intent
        data class SendUpload(
            val path: String,
            val kind: String,
            val fileName: String = "",
            val mimeType: String = "",
            val duration: Int = 0,
            val width: Int = 0,
            val height: Int = 0,
        ) : Intent
        data class SendAlbum(val items: List<UploadItem>) : Intent
        data class OpenStickerPack(val setId: Long, val accessHash: Long) : Intent
        data object LoadSavedGifs : Intent
        data class SendSavedGif(val documentId: Long) : Intent
        data class React(val messageId: Int, val emoticon: String = "", val documentId: Long = 0L) : Intent
        data class OpenComments(val message: Message) : Intent
        data object ClearPendingChat : Intent
        data object LoadMoreTopics : Intent
        data class SendInlineResult(val resultId: String) : Intent
        data object LoadMoreInlineResults : Intent
        data object RetryInlineResults : Intent
        data class SelectMention(val candidate: MentionCandidate) : Intent
        data object LoadMoreMentions : Intent
        data class BotButton(
            val messageId: Int,
            val button: ReplyButton,
            val fromKeyboard: Boolean,
        ) : Intent
        data class ToggleChecklist(val messageId: Int, val itemId: Int) : Intent
        /** Raw option bytes from the poll payload; the optimistic state arrives with the update. */
        data class VotePoll(val messageId: Int, val options: List<ByteArray>) : Intent
        data class AppendChecklistItems(
            val messageId: Int,
            val firstId: Int,
            val titles: List<String>,
        ) : Intent
        data class SendLocation(
            val latitude: Double,
            val longitude: Double,
            val livePeriodSeconds: Int,
        ) : Intent
        data object ClearBotNotice : Intent
        data object DismissBotAlert : Intent
        data object ClearBotUrl : Intent

        data class LoadReadReceipts(val messageId: Int) : Intent
        data class LoadReactionUsers(val messageId: Int) : Intent
        data class LoadPollVoters(val messageId: Int) : Intent
        data object ClearCopyText : Intent
    }

    data class State(
        val chatId: PeerId,
        val title: String = "",
        val isSelf: Boolean = false,
        val isGroup: Boolean = false,
        val isChannel: Boolean = false,
        val isForum: Boolean = false,
        val emojiStatusDocumentId: Long? = null,
        val messages: List<Message> = emptyList(),
        val senders: Map<PeerId, Profile> = emptyMap(),
        val senderTags: Map<PeerId, String> = emptyMap(),
        val draft: String = "",
        val pendingAttach: List<UploadItem> = emptyList(),
        val linkPreview: WebpagePreview? = null,
        val linkPreviewUrl: String? = null,
        val linkPreviewFixed: Boolean = false,
        val linkPreviewLoading: Boolean = false,
        val linkPreviewHidden: Boolean = false,
        val linkPreviewChoice: String? = null,
        val linkPreviewUrls: List<String> = emptyList(),
        val replyTo: Message? = null,
        val composerFocusSeq: Int = 0,
        val editing: Message? = null,
        val forwardMessage: Message? = null,
        val forwardTargets: List<Chat> = emptyList(),
        val forwardQuery: String = "",
        val forwarding: Boolean = false,
        val forwardHint: String? = null,
        val canView: Boolean = true,
        val canSendPlain: Boolean = true,
        val canSendPhotos: Boolean = true,
        val canForward: Boolean = true,
        val canDeleteOthers: Boolean = false,
        val searchQuery: String = "",
        val searching: Boolean = false,
        val loading: Boolean = false,
        val loadingOlder: Boolean = false,

        /** True while [loadingOlder] came from the automatic post-open prefetch. */
        val prefetchingOlder: Boolean = false,
        val loadingNewer: Boolean = false,
        val error: TelegramError? = null,
        val fromCache: Boolean = false,
        val hasOlder: Boolean = true,
        val hasNewer: Boolean = false,
        val pinnedListOpen: Boolean = false,
        val sending: Boolean = false,
        val unreadCount: Int = 0,
        val unreadMentionsCount: Int = 0,
        val unreadReactionsCount: Int = 0,
        val readInboxMaxId: Int = 0,
        val pinnedMessages: List<Message> = emptyList(),
        val pinnedIndex: Int = 0,
        val typing: Boolean = false,
        val typingUsers: Map<PeerId, TypingPresence> = emptyMap(),
        val peerStatus: String? = null,
        val peerStatusAt: Long? = null,
        val membersCount: Int? = null,
        val onlineCount: Int? = null,
        val readOutboxMaxId: Int = 0,
        val photoCacheKey: String? = null,
        val anchorMessageId: Int? = null,

        /** The anchor is the first unread row: show it at the top edge, like Telegram does. */
        val anchorAtTop: Boolean = false,
        val savedGifs: List<SavedGif> = emptyList(),
        val savedGifsLoaded: Boolean = false,
        val savedGifsError: Boolean = false,
        val gifPickerOpen: Boolean = false,
        val composerPanel: String? = null,
        val emojiTab: String = ComposerPanels.TAB_EMOJI,
        val stickerSets: List<StickerPack> = emptyList(),
        val stickerSetsLoaded: Boolean = false,
        val stickerSetsError: Boolean = false,
        val emojiSets: List<StickerPack> = emptyList(),
        val emojiSetsLoaded: Boolean = false,
        val emojiSetsError: Boolean = false,
        val openStickerPack: StickerPack? = null,
        val loadedStickerPacks: Map<Long, StickerPack> = emptyMap(),
        val loadingStickerPackIds: Set<Long> = emptySet(),
        val failedStickerPackIds: Set<Long> = emptySet(),
        val pendingChatId: PeerId? = null,
        val pendingChatMessageId: Int? = null,
        val discussionUnread: Int = 0,
        val recentReactions: List<ReactionChoice> = emptyList(),
        val threadTopId: Int = 0,
        val topics: List<ForumTopic> = emptyList(),
        val topicsCount: Int = 0,
        val loadingTopics: Boolean = false,
        val hasMoreTopics: Boolean = false,
        val topicClosed: Boolean = false,
        val topicIconColor: Int = 0,
        val topicIconEmojiId: Long? = null,
        val botNotice: String? = null,
        val botAlert: Boolean = false,
        val botUrl: String? = null,
        val copyText: String? = null,
        val readReceiptConfig: ReadReceiptConfig =
            ReadReceiptConfig.Fallback,
        val messageViewers: Map<Int, MessageViewers> = emptyMap(),
        val reactionUsers: Map<Int, List<org.monogram.core.models.MessageViewer>> = emptyMap(),
        val pollVoters: Map<Int, List<org.monogram.core.models.MessageViewer>> = emptyMap(),
        val outboxReadStates: Map<Int, OutboxReadState> = emptyMap(),
        val keyboardDismissedKey: String? = null,
        val inlineQuery: InlineBotQuery? = null,
        val inlineResults: InlineBotResults? = null,
        val inlineLoading: Boolean = false,
        val inlineError: Boolean = false,
        val mentionToken: String? = null,
        val mentionCandidates: List<MentionCandidate> = emptyList(),
        val mentionLoading: Boolean = false,
        val mentionHasMore: Boolean = false,
        val draftMentions: List<DraftMention> = emptyList(),
    ) {
        val isCommentThread: Boolean get() = threadTopId > 0 && !isForum
        val showTopicList: Boolean get() = ForumIo.showTopicList(isForum, threadTopId)
        val inForumTopic: Boolean get() = isForum && threadTopId > 0
    }
}

internal class DialogStoreFactory(
    private val storeFactory: StoreFactory,
    private val client: MtprotoClient,
    private val warmup: OfflineWarmup?,
    private val sessionStore: SessionMetadataStore?,
    private val chatId: PeerId,
    private val jumpToMessageId: Int = 0,
    private val threadTopMsgId: Int = 0,
    private val seedIsForum: Boolean? = null,
    private val mainContext: CoroutineContext = Dispatchers.Main,
    private val markup: MarkupParser = KotlinMarkupParser(),
    private val isPremium: () -> Boolean = { false },
    private val markupContext: CoroutineContext = Dispatchers.Default,
) {
    fun create(): DialogStore =
        object :
            DialogStore,
            Store<DialogStore.Intent, DialogStore.State, Nothing> by storeFactory.create(
                name = "DialogStore",
                initialState = DialogStore.State(
                    chatId = chatId,
                    loading = true,
                    pinnedMessages = PinnedBarMemory.get(chatId.value)?.messages.orEmpty(),
                    pinnedIndex = PinnedBarMemory.get(chatId.value)?.index ?: 0,
                    senderTags = SenderTagMemory.get(chatId.value),
                    topics = TopicListMemory.get(chatId.value),
                    threadTopId = threadTopMsgId,
                    isForum = seedIsForum == true,
                    savedGifs = SavedGifMemory.get().orEmpty(),
                    savedGifsLoaded = SavedGifMemory.get() != null,
                    stickerSets = StickerCatalogMemory.get(false)?.sets.orEmpty(),
                    stickerSetsLoaded = StickerCatalogMemory.get(false) != null,
                    emojiSets = StickerCatalogMemory.get(true)?.sets.orEmpty(),
                    emojiSetsLoaded = StickerCatalogMemory.get(true) != null,
                    loadedStickerPacks = StickerPackMemory.snapshot(),
                ),
                bootstrapper = SimpleBootstrapper(Unit),
                executorFactory = {
                    DialogExecutor(
                        client = client,
                        warmup = warmup,
                        sessionStore = sessionStore,
                        chatId = chatId,
                        jumpToMessageId = jumpToMessageId,
                        threadTopMsgId = threadTopMsgId,
                        seedIsForum = seedIsForum,
                        markup = markup,
                        isPremium = isPremium,
                        markupContext = markupContext,
                        mainContext = mainContext,
                    )
                },
                reducer = DialogReducer,
            ) {}
}
