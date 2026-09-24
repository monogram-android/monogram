package org.monogram.feature.dialog.store

import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.monogram.core.common.AppLog
import org.monogram.core.common.Outcome
import org.monogram.core.common.PerfLog
import org.monogram.core.database.OfflineWarmup
import org.monogram.core.database.SessionMetadataStore
import org.monogram.core.markup.MarkupParser
import org.monogram.core.models.Chat
import org.monogram.core.models.ForumIo
import org.monogram.core.models.LastSeen
import org.monogram.core.models.Message
import org.monogram.core.models.PeerId
import org.monogram.core.models.Profile
import org.monogram.core.models.UploadItem
import org.monogram.core.models.isPlaceholderPeerTitle
import org.monogram.core.models.preferredPeerTitle
import org.monogram.core.models.replaceComposedDialogSeed
import org.monogram.core.models.replaceComposedDialogSeed
import org.monogram.core.models.toggleChosenReaction
import org.monogram.feature.dialog.ComposerPanels
import org.monogram.feature.dialog.DialogStore
import org.monogram.feature.dialog.HISTORY_FIRST_LIMIT
import org.monogram.feature.dialog.PinnedBarMemory
import org.monogram.feature.dialog.encodeSenderTags
import org.monogram.feature.dialog.pinnedMetaKey
import org.monogram.feature.dialog.tagsMetaKey
import org.monogram.feature.dialog.SavedGifMemory
import org.monogram.feature.dialog.SenderTagMemory
import org.monogram.feature.dialog.applyMessageEdit
import org.monogram.feature.dialog.historyHasMore
import org.monogram.feature.dialog.mergeSenderTags
import org.monogram.feature.dialog.parseUpdateMessageId
import org.monogram.feature.dialog.unreadDividerIndex
import org.monogram.network.bridge.MtprotoClient
import org.monogram.network.bridge.MtprotoUpdate
import kotlin.coroutines.CoroutineContext

internal class DialogExecutor(
    internal val client: MtprotoClient,
    internal val warmup: OfflineWarmup?,
    internal val sessionStore: SessionMetadataStore?,
    internal val chatId: PeerId,
    jumpToMessageId: Int,
    internal val threadTopMsgId: Int,
    internal val seedIsForum: Boolean?,
    internal val markup: MarkupParser,
    internal val isPremium: () -> Boolean,
    internal val markupContext: CoroutineContext,
    mainContext: CoroutineContext,
) : CoroutineExecutor<DialogStore.Intent, Unit, DialogStore.State, Msg, Nothing>(mainContext) {
    internal var readReceiptJob: Job? = null
    internal var readReceiptSending = false
    internal var pendingReadMaxId = 0
    internal var searchJob: Job? = null
    internal var typingJob: Job? = null
    internal var inlineJob: Job? = null
    internal var mentionJob: Job? = null
    internal var mentionMemberOffset: Int = 0
    internal var mentionJumpBusy: Boolean = false
    internal var reactionJumpBusy: Boolean = false
    internal val unreadMentionIds = linkedSetOf<Int>()
    internal val unreadReactionIds = linkedSetOf<Int>()
    internal var unreadMentionsExhausted: Boolean = false
    internal var unreadReactionsExhausted: Boolean = false
    internal val unreadMutex = kotlinx.coroutines.sync.Mutex()
    internal var visibleUnreadIds: Set<Int> = emptySet()
    internal var unreadConsumeJob: Job? = null
    internal var unreadRevision: Int = 0
    internal var topicHeaderJob: Job? = null
    internal var confirmingInlineUser: String? = null
    internal var linkPreviewJob: Job? = null
    internal val loadingStickerPacks = mutableSetOf<Long>()
    internal val stickerPackRequests = kotlinx.coroutines.sync.Semaphore(2)
    internal val inlineBots = HashMap<String, PeerId>()
    internal var lastTypingSent: Boolean? = null
    internal var historyGen: Int = 0
    internal var newerRequestId: Int = 0
    internal var activeNewerRequestId: Int? = null
    internal var olderRequestId: Int = 0
    internal var activeOlderRequestId: Int? = null
    internal var pinnedRequested = false
    internal var cachedOlderTail: List<Message> = emptyList()
    // Only network pages may advance this cursor. Cached rows can be sparse.
    internal var serverHistoryBoundaryId: Int? = null
    internal var anchorToUnread = false
    internal var pendingUnreadAnchorId: Int? = null
    internal var confirmedNonForum: Boolean = false
    internal var forumUnknown: Boolean = seedIsForum == null
    internal var pendingJump = jumpToMessageId
    internal var inFlightSends = 0
    internal val typingJobs = HashMap<Long, Job>()
    internal val viewersInFlight = mutableSetOf<Int>()
    internal val readDatesInFlight = mutableSetOf<Int>()
    internal val configInFlight = java.util.concurrent.atomic.AtomicBoolean(false)
    internal val sendSeq = java.util.concurrent.atomic.AtomicInteger(0)

    internal fun emit(msg: Msg) = dispatch(msg)
    internal fun snapshot(): DialogStore.State = state()
    internal val work get() = scope

    override fun executeAction(action: Unit) {
        client.updates()
            .onEach { update ->
                when (update) {
                    is MtprotoUpdate.NewMessage -> if (update.message.id.chatId == chatId) {
                        if (state().searchQuery.isNotBlank()) return@onEach
                        if (threadTopMsgId > 0 && !inCommentThread(update.message)) return@onEach
                        absorbIncoming(update.message)
                    }
                    is MtprotoUpdate.MessageEdited -> if (update.message.id.chatId == chatId) {
                        val existing = state().messages.firstOrNull { it.id.id == update.message.id.id }
                            ?: state().pinnedMessages.firstOrNull { it.id.id == update.message.id.id }
                        val next = applyMessageEdit(
                            listOf(existing ?: update.message),
                            update.message,
                        ).first()
                        if (existing != next) {
                            dispatch(Msg.MessageEdited(update.message))
                            rememberPinned()
                        }
                    }
                    is MtprotoUpdate.MessagesDeleted -> {
                        if (update.chatId == null || update.chatId == chatId) {
                            val deleted = update.messageIds.toSet()
                            if (deleted.isNotEmpty()) {
                                dispatch(Msg.MessagesDeleted(deleted))
                                rememberPinned()
                                scope.launch {
                                    warmup?.deleteMessages(chatId, deleted)
                                }
                            }
                        }
                    }
                    is MtprotoUpdate.PeerTyping -> if (update.chatId == chatId) {
                        applyTyping(update.userId, update.typing, update.action)
                    }
                    is MtprotoUpdate.UnreadMentions,
                    is MtprotoUpdate.UnreadReactions,
                    is MtprotoUpdate.UnreadMentionsDelta,
                    is MtprotoUpdate.UnreadReactionsDelta -> handleUnreadCounterUpdate(update)
                    is MtprotoUpdate.ReadInbox -> if (update.chatId == chatId) {
                        val current = state()
                        if (threadTopMsgId > 0 || update.maxId < current.readInboxMaxId) return@onEach
                        if (current.readInboxMaxId != update.maxId) {
                            dispatch(Msg.ReadInbox(update.maxId))
                        }
                        val unread = update.stillUnread.coerceAtLeast(0)
                        if (current.unreadCount != unread) {
                            dispatch(Msg.UnreadCount(unread))
                        }
                    }
                    is MtprotoUpdate.ReadOutbox -> if (update.chatId == chatId) {
                        if (state().readOutboxMaxId != update.maxId) {
                            dispatch(Msg.ReadOutbox(update.maxId))
                        }
                    }
                    is MtprotoUpdate.PeerEmojiStatus -> {
                        if (update.userId == chatId &&
                            state().emojiStatusDocumentId != update.documentId
                        ) {
                            dispatch(Msg.EmojiStatus(update.documentId))
                        }
                        val sender = state().senders[update.userId]
                        if (sender != null && sender.emojiStatusDocumentId != update.documentId) {
                            dispatch(
                                Msg.Senders(
                                    mapOf(
                                        update.userId to sender.copy(
                                            emojiStatusDocumentId = update.documentId,
                                        ),
                                    ),
                                ),
                            )
                        }
                        scope.launch {
                            sessionStore?.updatePeerEmojiStatus(
                                update.userId.value,
                                update.documentId,
                            )
                        }
                    }
                    is MtprotoUpdate.PeerStatus -> if (update.userId == chatId) {
                        val current = state()
                        if (!LastSeen.affectsUi(
                                current.peerStatus,
                                current.peerStatusAt,
                                update.status,
                                update.statusAt,
                            )
                        ) {
                            return@onEach
                        }
                        dispatch(Msg.PeerStatus(update.status, update.statusAt))
                        scope.launch {
                            sessionStore?.updatePeerStatus(
                                update.userId.value,
                                update.status,
                                update.statusAt,
                            )
                        }
                    }
                    is MtprotoUpdate.ChatsChanged -> {
                        val chat = update.chats.firstOrNull { it.id == chatId } ?: return@onEach
                        val current = state()
                        val title = preferredPeerTitle(chat.title, current.title, chat.id.value)
                        if (current.title != title) dispatch(Msg.Title(title))
                        if (chat.readInboxMaxId >= current.readInboxMaxId && current.unreadCount != chat.unreadCount) {
                            dispatch(Msg.UnreadCount(chat.unreadCount))
                        }
                        applyUnreadCountersFromChat(chat.unreadMentionsCount, chat.unreadReactionsCount)
                        if (chat.readInboxMaxId > current.readInboxMaxId) {
                            dispatch(Msg.ReadInbox(chat.readInboxMaxId))
                        }
                        if (current.readOutboxMaxId != chat.readOutboxMaxId) {
                            dispatch(Msg.ReadOutbox(chat.readOutboxMaxId))
                        }
                        if (current.peerStatus != chat.peerStatus ||
                            current.peerStatusAt != chat.peerStatusAt
                        ) {
                            dispatch(Msg.PeerStatus(chat.peerStatus, chat.peerStatusAt))
                        }
                        if (current.emojiStatusDocumentId != chat.emojiStatusDocumentId) {
                            dispatch(Msg.EmojiStatus(chat.emojiStatusDocumentId))
                        }
                        if (current.photoCacheKey != chat.photoCacheKey) {
                            dispatch(Msg.PhotoCacheKey(chat.photoCacheKey))
                        }
                        val rights = rightsMsg(chat)
                        if (current.canView != rights.canView ||
                            current.canSendPlain != rights.canSendPlain ||
                            current.canSendPhotos != rights.canSendPhotos ||
                            current.canForward != rights.canForward ||
                            current.canDeleteOthers != rights.canDeleteOthers
                        ) {
                            dispatch(rights)
                        }
                    }
                    is MtprotoUpdate.SavedGifsChanged -> {
                        SavedGifMemory.clear()
                        dispatch(Msg.SavedGifsLoaded(false))
                        loadSavedGifs()
                    }
                    is MtprotoUpdate.MessageReactions -> if (update.chatId == chatId) {
                        val current = state().messages.firstOrNull { it.id.id == update.messageId }
                        if (current?.reactionsJson != update.reactionsJson) {
                            dispatch(Msg.Reactions(update.messageId, update.reactionsJson))
                        }
                    }
                    is MtprotoUpdate.DiscussionInbox -> if (update.channelId == chatId &&
                        update.topMessageId == ForumIo.historyThreadId(threadTopMsgId)
                    ) {
                        val confirmed = maxOf(state().readInboxMaxId, update.readMaxId)
                        dispatch(Msg.ReadInbox(confirmed))
                        dispatch(Msg.UnreadCount(state().messages.count { !it.outgoing && it.id.id > confirmed }))
                    }
                    is MtprotoUpdate.Ignored -> {
                        parseUpdateMessageId(update.kind)?.let { (randomId, messageId) ->
                            bindPendingId(randomId, messageId)
                            return@onEach
                        }
                        if (update.kind == "difference_too_long" &&
                            state().searchQuery.isBlank()
                        ) {
                            // updates.differenceTooLong advances pts but
                            // cannot carry the missing messages. Refill
                            // the visible dialog range from history.
                            refresh()
                        }
                    }
                    else -> Unit
                }
            }
            .launchIn(scope)
        refresh()
        loadRecentReactions()
    }

    override fun executeIntent(intent: DialogStore.Intent) {
        when (intent) {
            DialogStore.Intent.Refresh -> refresh()
            DialogStore.Intent.RefreshPresence -> {
                loadChatProfile()
                if (state().inForumTopic) {
                    loadTopicHeader()
                } else if (ForumIo.showTopicList(state().isForum, threadTopMsgId)) {
                    loadTopics(reset = true)
                }
            }
            DialogStore.Intent.LoadOlder -> loadOlder()
            DialogStore.Intent.LoadNewer -> loadNewer()
            is DialogStore.Intent.DraftChanged -> {
                if (state().draft != intent.value) {
                    applyDraft(intent.value)
                }
                publishTyping(intent.value.isNotBlank())
                scheduleComposerAt(intent.value)
                scheduleLinkPreview(intent.value)
            }
            DialogStore.Intent.FixLinkPreview -> fixLinkPreview()
            DialogStore.Intent.DismissLinkPreview -> dismissLinkPreview()
            DialogStore.Intent.RestoreLinkPreview -> restoreLinkPreview()
            is DialogStore.Intent.SelectLinkPreview -> selectLinkPreview(intent.url)
            is DialogStore.Intent.AttachPhoto -> dispatch(
                Msg.PendingAttach(
                    listOf(
                        UploadItem(
                            path = intent.path,
                            kind = "photo",
                            fileName = java.io.File(intent.path).name,
                        ),
                    ),
                ),
            )
            is DialogStore.Intent.AttachMedia -> dispatch(
                Msg.PendingAttach(intent.items.take(10)),
            )
            is DialogStore.Intent.AppendMedia -> dispatch(
                Msg.PendingAttach(
                    (state().pendingAttach + intent.items)
                        .distinctBy(UploadItem::path)
                        .take(10),
                ),
            )
            DialogStore.Intent.ClearAttach -> dispatch(Msg.PendingAttach(emptyList()))
            is DialogStore.Intent.ReplyTo -> {
                dispatch(Msg.Editing(null))
                dispatch(Msg.ReplyTo(intent.message, focusComposer = intent.focusComposer))
            }
            DialogStore.Intent.ClearReply -> dispatch(Msg.ReplyTo(null))
            is DialogStore.Intent.Edit -> {
                dispatch(Msg.ReplyTo(null))
                dispatch(Msg.PendingAttach(emptyList()))
                dispatch(Msg.Editing(intent.message))
                applyDraft(intent.message.text.orEmpty())
            }
            DialogStore.Intent.CancelEdit -> {
                dispatch(Msg.Editing(null))
                applyDraft("")
            }
            is DialogStore.Intent.Delete -> delete(intent.messageId, intent.revoke)
            is DialogStore.Intent.ForwardPick -> openForward(intent.message)
            DialogStore.Intent.ClearForward -> {
                dispatch(Msg.ForwardMessage(null))
                dispatch(Msg.ForwardQuery(""))
            }
            is DialogStore.Intent.ForwardQuery -> dispatch(Msg.ForwardQuery(intent.value))
            is DialogStore.Intent.ForwardTo -> forwardTo(intent.chatId)
            DialogStore.Intent.ClearForwardHint -> dispatch(Msg.ForwardHint(null))
            is DialogStore.Intent.Send -> send(intent.text)
            DialogStore.Intent.RetryFailed -> retryFailed()
            is DialogStore.Intent.Search -> search(intent.query)
            is DialogStore.Intent.JumpToDate -> jump(intent.epochSeconds)
            DialogStore.Intent.ClearSearch -> {
                dispatch(Msg.SearchQuery(""))
                refresh()
            }
            is DialogStore.Intent.SaveScroll -> {
                scope.launch {
                    warmup?.setDialogScroll(chatId, intent.messageId)
                }
            }
            DialogStore.Intent.NextPinned -> nextPinned()
            DialogStore.Intent.OpenPinnedList -> dispatch(Msg.PinnedListOpen(true))
            DialogStore.Intent.ClosePinnedList -> dispatch(Msg.PinnedListOpen(false))
            is DialogStore.Intent.JumpToPinned -> jumpToPinned(intent.messageId)
            is DialogStore.Intent.JumpToMessage -> jumpToMessage(intent.messageId)
            DialogStore.Intent.JumpMention -> jumpMention()
            DialogStore.Intent.JumpUnreadReaction -> jumpUnreadReaction()
            DialogStore.Intent.JumpUnread -> {
                val current = state()
                val index = unreadDividerIndex(
                    current.messages,
                    current.unreadCount,
                    current.readInboxMaxId,
                    current.hasNewer,
                )
                val id = index?.let { current.messages.getOrNull(it)?.id?.id }
                if (id != null) dispatch(Msg.Anchor(id, atTop = true))
            }
            DialogStore.Intent.JumpLatest -> {
                val searching = state().searchQuery.isNotBlank()
                if (searching) {
                    searchJob?.cancel()
                    dispatch(Msg.SearchQuery(""))
                    dispatch(Msg.Searching(false))
                }
                if (state().hasNewer || searching) {
                    refresh()
                } else {
                    val newest = state().messages.maxByOrNull { it.id.id } ?: return
                    dispatch(Msg.Anchor(newest.id.id))
                }
                markRead()
            }
            DialogStore.Intent.MarkRead -> markRead()
            is DialogStore.Intent.VisibleRead -> visibleRead(intent.messageId, intent.atLiveEdge)
            is DialogStore.Intent.VisibleWindow -> consumeVisibleUnread(intent.messageIds)
            DialogStore.Intent.ToggleGifPicker -> openEmojiTab(ComposerPanels.TAB_GIFS)
            DialogStore.Intent.ToggleAttachSheet -> {
                val open = state().composerPanel != ComposerPanels.ATTACH
                dispatch(Msg.ComposerPanel(if (open) ComposerPanels.ATTACH else null))
                dispatch(Msg.GifPicker(false))
            }
            DialogStore.Intent.CloseAttachSheet -> {
                if (state().composerPanel == ComposerPanels.ATTACH) {
                    dispatch(Msg.ComposerPanel(null))
                }
                dispatch(Msg.GifPicker(false))
            }
            DialogStore.Intent.ToggleEmojiPanel -> {
                if (state().composerPanel == ComposerPanels.EMOJI) {
                    dispatch(Msg.ComposerPanel(null))
                } else {
                    openEmojiTab(state().emojiTab.ifBlank { ComposerPanels.TAB_EMOJI })
                }
            }
            is DialogStore.Intent.SetEmojiTab -> openEmojiTab(intent.tab)
            DialogStore.Intent.ReloadPickerContent -> {
                loadSavedGifs()
                loadStickerCatalog(emoji = false)
            }
            is DialogStore.Intent.InsertEmoji -> {
                if (intent.glyph.isNotEmpty()) {
                    applyDraft(state().draft + intent.glyph)
                }
            }
            is DialogStore.Intent.SendUpload -> sendUpload(
                path = intent.path,
                kind = intent.kind,
                fileName = intent.fileName,
                mimeType = intent.mimeType,
                duration = intent.duration,
                width = intent.width,
                height = intent.height,
            )
            is DialogStore.Intent.SendAlbum -> sendAlbum(intent.items)
            is DialogStore.Intent.OpenStickerPack -> loadStickerPack(intent.setId, intent.accessHash)
            DialogStore.Intent.LoadSavedGifs -> loadSavedGifs()
            is DialogStore.Intent.LoadReadReceipts -> loadReadReceipts(intent.messageId)
            is DialogStore.Intent.LoadReactionUsers -> loadReactionUsers(intent.messageId)
            is DialogStore.Intent.LoadPollVoters -> loadPollVoters(intent.messageId)
            is DialogStore.Intent.SendSavedGif -> sendSavedGif(intent.documentId)
            is DialogStore.Intent.React -> scope.launch {
                val current = state().messages.firstOrNull { it.id.id == intent.messageId }
                val json = org.monogram.core.models.toggleChosenReaction(
                    current?.reactionsJson,
                    intent.emoticon,
                    intent.documentId,
                )
                if (current?.reactionsJson != json) {
                    dispatch(Msg.Reactions(intent.messageId, json))
                }
                when (val result = client.sendReaction(
                    chatId,
                    intent.messageId,
                    intent.emoticon,
                    intent.documentId,
                )) {
                    is Outcome.Ok -> Unit
                    is Outcome.Err -> {
                        current?.reactionsJson?.let {
                            dispatch(Msg.Reactions(intent.messageId, it))
                        }
                        handleError(result.telegramError, false)
                    }
                }
            }
            is DialogStore.Intent.OpenComments -> scope.launch {
                when (val result = client.getDiscussionMessage(chatId, intent.message.id.id)) {
                    is Outcome.Ok -> dispatch(
                        Msg.PendingChat(result.value.chatId, result.value.messageId),
                    )
                    is Outcome.Err -> handleError(result.telegramError, false)
                }
            }
            DialogStore.Intent.ClearPendingChat -> dispatch(Msg.PendingChat(null, null))
            DialogStore.Intent.LoadMoreTopics -> loadMoreTopics()
            is DialogStore.Intent.SendInlineResult -> sendInlineResult(intent.resultId)
            DialogStore.Intent.LoadMoreInlineResults -> loadMoreInlineResults()
            DialogStore.Intent.RetryInlineResults -> retryInlineResults()
            is DialogStore.Intent.SelectMention -> selectMention(intent.candidate)
            DialogStore.Intent.LoadMoreMentions -> loadMoreMentions()
            is DialogStore.Intent.ToggleChecklist -> toggleChecklist(intent.messageId, intent.itemId)
            is DialogStore.Intent.VotePoll -> votePoll(intent.messageId, intent.options)
            is DialogStore.Intent.AppendChecklistItems ->
                appendChecklistItems(intent.messageId, intent.firstId, intent.titles)
            is DialogStore.Intent.SendLocation ->
                sendLocation(intent.latitude, intent.longitude, intent.livePeriodSeconds)
            is DialogStore.Intent.BotButton -> pressBotButton(
                intent.messageId,
                intent.button,
                intent.fromKeyboard,
            )
            DialogStore.Intent.ClearBotNotice -> dispatch(Msg.BotNotice(null))
            DialogStore.Intent.DismissBotAlert -> dispatch(Msg.BotNotice(null))
            DialogStore.Intent.ClearBotUrl -> dispatch(Msg.BotUrl(null))
            DialogStore.Intent.ClearCopyText -> dispatch(Msg.CopyText(null))
        }
    }

    internal fun refresh() {
        historyGen += 1
        val gen = historyGen
        // Refresh invalidates in-flight pagination just like a jump. Clear both owner
        // tokens and gates so stale completions cannot strand the next page request.
        olderRequestId++
        activeOlderRequestId = null
        newerRequestId++
        activeNewerRequestId = null
        dispatch(Msg.LoadingOlder(false))
        dispatch(Msg.LoadingNewer(false))
        val hasMemory = state().messages.isNotEmpty()
        dispatch(Msg.Error(null))
        scope.launch {
            client.setDialogForeground(true)
            val cachedChat = warmup?.let { cache ->
                if (cache.usesIoDispatcher) {
                    withContext(Dispatchers.IO) { cache.chat(chatId) }
                } else {
                    cache.chat(chatId)
                }
            }
            val isChannel = cachedChat?.isChannel == true || state().isChannel
            val isGroup = cachedChat?.isGroup == true || state().isGroup
            val forum = !confirmedNonForum && probesTopics(cachedChat)
            val chatTitle = cachedChat?.title
            val unread = cachedChat?.unreadCount ?: 0
            // Unread work outranks the remembered position: opening a dialog with unread
            // messages must land on the first unread one, like Telegram does.
            anchorToUnread = unread > 0 && threadTopMsgId <= 0 &&
                pendingJump <= 0 && state().anchorMessageId == null
            if (!chatTitle.isNullOrBlank() && threadTopMsgId <= 0) {
                dispatch(Msg.Title(chatTitle))
            }
            dispatch(Msg.IsSelf(sessionStore?.readAuthorizedUserId() == chatId))
            dispatch(Msg.IsGroup(isGroup))
            dispatch(Msg.IsChannel(isChannel))
            dispatch(Msg.IsForum(forum))
            cachedChat?.let { dispatch(rightsMsg(it)) }
            cachedChat?.unreadCount?.let { dispatch(Msg.UnreadCount(it)) }
            cachedChat?.let { applyUnreadCountersFromChat(it.unreadMentionsCount, it.unreadReactionsCount) }
            cachedChat?.readInboxMaxId?.let { dispatch(Msg.ReadInbox(it)) }
            cachedChat?.readOutboxMaxId?.let { dispatch(Msg.ReadOutbox(it)) }
            dispatch(Msg.PeerStatus(cachedChat?.peerStatus, cachedChat?.peerStatusAt))
            dispatch(Msg.EmojiStatus(cachedChat?.emojiStatusDocumentId))
            dispatch(Msg.PhotoCacheKey(cachedChat?.photoCacheKey))
            sessionStore?.readProfile(chatId.value)?.let { cachedProfile ->
                if (cachedProfile.membersCount != null || cachedProfile.onlineCount != null) {
                    dispatch(
                        Msg.ChatProfile(
                            members = cachedProfile.membersCount,
                            online = cachedProfile.onlineCount,
                        ),
                    )
                }
            }
            val savedDraft = warmup?.let { cache ->
                if (cache.usesIoDispatcher) {
                    withContext(Dispatchers.IO) { cache.draft(chatId, threadTopMsgId) }
                } else {
                    cache.draft(chatId, threadTopMsgId)
                }
            }.orEmpty()
            if (savedDraft.isNotEmpty() && state().draft.isEmpty()) {
                dispatch(Msg.Draft(savedDraft))
                scheduleComposerAt(savedDraft)
            }
            if (state().anchorMessageId == null && threadTopMsgId <= 0 && unread <= 0) {
                dispatch(Msg.Anchor(cachedChat?.dialogScrollMessageId))
            }
            if (ForumIo.showTopicList(forum, threadTopMsgId)) {
                hydrateTopics()
                if (state().topics.isNotEmpty()) dispatch(Msg.Loading(false))
                loadTopics(reset = true, gen = gen)
                loadChatProfile()
                return@launch
            }
            // Comment threads reuse getReplies, not forum topics.
            // https://core.telegram.org/api/discussion
            if (threadTopMsgId > 0 && forum) {
                loadTopicHeader()
            }
            val historyTop = ForumIo.historyThreadId(threadTopMsgId)
            if (historyTop > 0) {
                val savedRead = warmup?.let { cache ->
                    if (cache.usesIoDispatcher) {
                        withContext(Dispatchers.IO) { cache.discussionReadMax(chatId, historyTop) }
                    } else {
                        cache.discussionReadMax(chatId, historyTop)
                    }
                } ?: 0
                dispatch(Msg.ReadInbox(maxOf(state().readInboxMaxId, savedRead)))
                if (!hasMemory) dispatch(Msg.Loading(true))
                AppLog.api("dialog", "getReplies start chat=${chatId.value} top=$historyTop")
                when (
                    val result = client.getReplies(
                        chatId,
                        historyTop,
                        HISTORY_FIRST_LIMIT,
                    )
                ) {
                    is Outcome.Ok -> {
                        if (gen != historyGen) return@launch
                        serverHistoryBoundaryId = result.value.minOfOrNull { it.id.id }
                        publishPaintedHistory(result.value, fromCache = false, liveEdge = true, generation = gen)
                        if (gen != historyGen) return@launch
                        resolveSenders(state().messages)
                        dispatch(Msg.HasOlder(historyHasMore(result.value.size, HISTORY_FIRST_LIMIT) || cachedOlderTail.isNotEmpty()))
                    }
                    is Outcome.Err -> handleError(result.telegramError, true)
                }
                if (gen != historyGen) return@launch
                dispatch(Msg.Loading(false))
                loadChatProfile()
                return@launch
            }
            val cached = warmup?.let { cache ->
                if (cache.usesIoDispatcher) {
                    withContext(Dispatchers.IO) { cache.messages(chatId, HISTORY_FIRST_LIMIT) }
                } else {
                    cache.messages(chatId, HISTORY_FIRST_LIMIT)
                }
            }.orEmpty().replaceComposedDialogSeed(cachedChat)
            val lastId = cachedChat?.lastMessageId ?: 0
            val newestCachedId = cached.maxOfOrNull { it.id.id } ?: 0
            if (cached.isNotEmpty()) {
                AppLog.api(
                    "dialog",
                    "cache hit chat=${chatId.value} count=${cached.size} lastId=$lastId",
                )
                publishPaintedHistory(cached, fromCache = true, liveEdge = true, generation = gen)
                if (gen != historyGen) return@launch
                dispatch(Msg.Loading(false))
                resolveSenders(state().messages)
            } else if (!hasMemory) {
                dispatch(Msg.Loading(true))
            }
            hydratePinned()
            hydrateSenderTags()
            // Paint cache first, then always fetch. Skipping getHistory when a
            // stale Room lastMessageId matched the cached tail hid new messages.
            AppLog.api(
                "dialog",
                "getHistory start chat=${chatId.value} lastId=$lastId newest=$newestCachedId",
            )
            val historyAt = PerfLog.nowMs()
            PerfLog.event("history", "phase=start")
            when (val result = client.getHistory(chatId, HISTORY_FIRST_LIMIT)) {
                is Outcome.Ok -> {
                    PerfLog.event(
                        "history",
                        "phase=page elapsed_ms=${PerfLog.nowMs() - historyAt} result=ok count=${result.value.size}",
                    )
                    if (gen != historyGen) return@launch
                    val vanished = cached.map { it.id.id }.toSet() -
                        result.value.map { it.id.id }.toSet()
                    AppLog.api(
                        "dialog",
                        "network chat=${chatId.value} count=${result.value.size} " +
                            "vanished=${vanished.size}" +
                            if (vanished.isEmpty()) {
                                ""
                            } else {
                                " ids=${vanished.take(12).joinToString(",")}"
                            },
                    )
                    serverHistoryBoundaryId = result.value.minOfOrNull { it.id.id }
                    publishPaintedHistory(result.value, fromCache = false, liveEdge = true, generation = gen)
                    if (gen != historyGen) return@launch
                    dispatch(
                        Msg.HasOlder(
                            historyHasMore(result.value.size, HISTORY_FIRST_LIMIT) ||
                                cached.size > result.value.size ||
                                cachedOlderTail.isNotEmpty(),
                        ),
                    )
                    val persist = suspend {
                        warmup?.upsertMessages(result.value)
                        warmup?.pruneMissingLatest(chatId, result.value)
                        sessionStore?.upsertPeerMins(result.value)
                    }
                    if (warmup?.usesIoDispatcher == true) {
                        withContext(Dispatchers.IO) { persist() }
                    } else {
                        persist()
                    }
                    resolveSenders(state().messages)
                }
                is Outcome.Err -> {
                    PerfLog.event(
                        "history",
                        "phase=page elapsed_ms=${PerfLog.nowMs() - historyAt} result=err",
                    )
                    handleError(result.telegramError, cached.isEmpty() && !hasMemory)
                }
            }
            if (gen != historyGen) return@launch
            dispatch(Msg.Loading(false))
            loadPinned()
            loadChatProfile()
            val jump = pendingJump
            if (jump > 0) {
                pendingJump = 0
                jumpToMessageId(jump)
            } else {
                continueUnreadPaging()
            }
        }
    }

    internal fun rememberPinned() {
        val current = state()
        PinnedBarMemory.put(chatId.value, current.pinnedMessages, current.pinnedIndex)
        val ids = current.pinnedMessages.map { it.id.id }
        scope.launch {
            sessionStore?.writeMeta(pinnedMetaKey(chatId.value), ids.joinToString(","))
            if (current.pinnedMessages.isNotEmpty()) {
                warmup?.upsertMessages(current.pinnedMessages)
            }
        }
    }

    internal fun loadChatProfile() {
        scope.launch {
            when (val result = client.getProfile(chatId)) {
                is Outcome.Ok -> {
                    val profile = result.value
                    val keepTopicTitle = state().inForumTopic ||
                        ForumIo.historyThreadId(threadTopMsgId) > 0 ||
                        threadTopMsgId == org.monogram.core.models.GENERAL_FORUM_TOPIC_ID
                    if (!keepTopicTitle) {
                        val title = preferredPeerTitle(profile.title, state().title, chatId.value)
                        if (!isPlaceholderPeerTitle(title, chatId.value) && state().title != title) {
                            dispatch(Msg.Title(title))
                        }
                    }
                    when (profile.kind) {
                        "channel" -> {
                            dispatch(Msg.IsChannel(true))
                            dispatch(Msg.IsGroup(false))
                        }
                        "group", "chat" -> {
                            dispatch(Msg.IsGroup(true))
                            dispatch(Msg.IsChannel(profile.kind == "channel"))
                        }
                        else -> Unit
                    }
                    dispatch(
                        Msg.ChatProfile(
                            members = profile.membersCount,
                            online = profile.onlineCount,
                            authoritative = true,
                        ),
                    )
                    dispatch(Msg.EmojiStatus(profile.emojiStatusDocumentId))
                    if (profile.avatarCacheKey != null) {
                        dispatch(Msg.PhotoCacheKey(profile.avatarCacheKey))
                    }
                    if (!state().isGroup && !state().isChannel) {
                        dispatch(Msg.PeerStatus(profile.status, profile.statusAt))
                    }
                    sessionStore?.upsertProfile(profile)
                    warmup?.applyProfileToChat(profile)
                    if (state().isGroup) loadAdminTags()
                }
                is Outcome.Err -> Unit
            }
        }
    }

    internal fun loadAdminTags() {
        if (!state().isGroup) return
        scope.launch {
            when (val result = client.getGroupAdminTags(chatId)) {
                is Outcome.Ok -> {
                    val tags = mergeSenderTags(result.value, state().senders)
                    dispatch(Msg.SenderTags(tags))
                    SenderTagMemory.put(chatId.value, tags)
                    sessionStore?.writeMeta(tagsMetaKey(chatId.value), encodeSenderTags(tags))
                }
                is Outcome.Err -> Unit
            }
        }
    }

    internal fun loadRecentReactions() {
        scope.launch {
            when (val result = client.getRecentReactions()) {
                is Outcome.Ok -> dispatch(Msg.RecentReactions(result.value))
                is Outcome.Err -> Unit
            }
        }
    }

    internal fun rightsMsg(chat: Chat) = Msg.Rights(
        canView = chat.canView,
        canSendPlain = chat.canSendPlain,
        canSendPhotos = chat.canSendPhotos,
        canForward = chat.canForward,
        canDeleteOthers = chat.canDeleteOthers,
    )

    internal suspend fun resolveSenders(messages: List<Message>) {
        if (!state().isGroup) return
        val resolved = LinkedHashMap<PeerId, Profile>()
        messages.forEach { message ->
            if (message.outgoing) return@forEach
            val senderId = message.senderId ?: return@forEach
            if (senderId in state().senders || senderId in resolved) return@forEach
            val cached = withContext(Dispatchers.IO) {
                sessionStore?.readProfile(senderId.value)
            }
            val title = cached?.title?.takeIf { it.isNotBlank() }
                ?: message.senderName?.takeIf { it.isNotBlank() }
                ?: return@forEach
            resolved[senderId] = (cached ?: Profile(
                id = senderId,
                kind = "user",
                title = title,
            )).copy(
                title = title,
                emojiStatusDocumentId = cached?.emojiStatusDocumentId
                    ?: message.senderEmojiStatusDocumentId,
            )
        }
        if (resolved.isNotEmpty()) {
            dispatch(Msg.Senders(resolved))
            val tags = mergeSenderTags(state().senderTags, state().senders)
            dispatch(Msg.SenderTags(tags))
            SenderTagMemory.put(chatId.value, tags)
        }
        resolved.entries
            .filter { it.value.avatarCacheKey.isNullOrBlank() }
            .take(8)
            .forEach { (id, _) ->
                when (val result = client.getProfile(id)) {
                    is Outcome.Ok -> {
                        dispatch(Msg.Senders(mapOf(id to result.value)))
                        sessionStore?.upsertProfile(result.value)
                    }
                    is Outcome.Err -> Unit
                }
            }
    }
}
