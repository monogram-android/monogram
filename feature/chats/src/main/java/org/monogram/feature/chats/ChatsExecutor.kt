package org.monogram.feature.chats

import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.monogram.core.common.AppLog
import org.monogram.core.common.Outcome
import org.monogram.core.common.PerfLog
import org.monogram.core.common.push.NotificationLocalStore
import org.monogram.core.common.telegram.TelegramError
import org.monogram.core.database.ChatCacheCursor
import org.monogram.core.database.OfflineWarmup
import org.monogram.core.database.SessionMetadataStore
import org.monogram.core.database.dao.ChatReadState
import org.monogram.core.models.ARCHIVE_FOLDER_ID
import org.monogram.core.models.AuthSession
import org.monogram.core.models.Chat
import org.monogram.core.models.ChatActionKind
import org.monogram.core.models.ContactsSearch
import org.monogram.core.models.Folder
import org.monogram.core.models.GlobalMessageSearch
import org.monogram.core.models.LastSeen
import org.monogram.core.models.Message
import org.monogram.core.models.NotifyDefaults
import org.monogram.core.models.NotifySettings
import org.monogram.core.models.PeerId
import org.monogram.core.models.SearchPeer
import org.monogram.core.models.TypingPresence
import org.monogram.core.models.displayedChatAction
import org.monogram.core.models.isPlaceholderPeerTitle
import org.monogram.core.ui.components.PeerPresence
import org.monogram.network.bridge.DialogOffset
import org.monogram.network.bridge.DialogPage
import org.monogram.network.bridge.MtprotoClient
import org.monogram.network.bridge.MtprotoUpdate
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val FOLDER_DISCOVERY_ATTEMPTS = 8

internal class ChatsExecutor(
    private val client: MtprotoClient,
    private val warmup: OfflineWarmup?,
    private val sessionStore: SessionMetadataStore?,
    private val notifications: NotificationLocalStore?,
    private val refreshMergeHook: (suspend () -> Unit)? = null,
    private val readStateHook: (suspend () -> Unit)? = null,
    private val ioContext: kotlin.coroutines.CoroutineContext = Dispatchers.IO,
) : CoroutineExecutor<ChatsStore.Intent, Unit, ChatsStore.State, Msg, Nothing>() {
    private val refreshInFlight = AtomicBoolean(false)
    private var pendingRefresh = false
    private var pendingRefreshForce = false
    private val listPublicationMutex = Mutex()
    private var lastNetworkPage: List<Chat> = emptyList()
    private var mainNextOffset: DialogOffset? = null
    private var mainPagingGeneration = 0L
    private var refreshSyncing = false
    private val protocolSyncing = MutableStateFlow(false)

    private fun publishSyncing() {
        val syncing = refreshSyncing || protocolSyncing.value
        if (state().syncing != syncing) dispatch(Msg.Syncing(syncing))
    }

    private fun setRefreshSyncing(value: Boolean) {
        refreshSyncing = value
        publishSyncing()
    }

    private var mainNetworkHasMore = true
    private val failedOffsetPeers = mutableSetOf<Long>()
    private var preferMainOnNextFallbackLoad = false

    /** Per-type notification defaults, inherited by dialogs without their own mute setting. */
    private var notifyDefaults = NotifyDefaults()
    private var notifyDefaultsLoaded = false
    private var notifyDefaultsRefreshed = false
    private val notifyDefaultsMutex = Mutex()

    /** Folder paging state, keyed by the wire folder id (0 = main list). */
    private var activeFolderId: Int? = null
    private var activeFolder: Folder? = null
    private var folderSelectionGeneration = 0L
    private var pendingFolderInitialLoad: ChatsStore.Intent.FolderSelected? = null
    private val folderPaging = HashMap<Int, FolderPaging>()

    private val folderArchivePaging get() = folderPaging
    private val typingByChat = HashMap<Long, LinkedHashMap<Long, TypingPresence>>()
    private val typingJobs = HashMap<String, Job>()
    private var pendingReadStates: Map<Long, ChatReadState>? = null
    private var cachedTail: List<Chat> = emptyList()
    private var roomMainCount = 0
    private var titleRecoveryJob: Job? = null
    private var catalogGeneration = 0
    private var catalogJob: Job? = null
    private val attemptedTitleRecovery = mutableSetOf<PeerId>()
    private var searchJob: Job? = null
    private var searchMoreJob: Job? = null
    private var searchGen = 0
    private var searchNextRate = 0
    private var searchNextPeerId = 0L
    private var searchNextOffsetId = 0
    private var lastRunSearchQuery = ""

    override fun executeAction(action: Unit) {
        warmup?.observeReadStates()?.map { rows -> rows.associateBy { it.id } }
            ?.distinctUntilChanged()?.flowOn(Dispatchers.Default)?.onEach { rows ->
                if (state().chats.isEmpty()) {
                    pendingReadStates = rows
                    return@onEach
                }
                pendingReadStates = null
                readStateHook?.invoke()
                listPublicationMutex.withLock {
                    dispatch(Msg.ReadStates(rows))
                }
            }?.launchIn(scope)
        loadSelf()
        // A previous run cached the account defaults: the list must keep the right bell before
        // the settings RPC (or its failure) decides anything.
        notifications?.notifyDefaults?.let { cached ->
            notifyDefaults = cached
            notifyDefaultsLoaded = true
        }
        scope.launch { loadNotifyDefaults() }
        refresh(force = false)
        client.updates().onEach { update ->
                when (update) {
                    is MtprotoUpdate.SyncState -> {
                        protocolSyncing.value = update.isSyncing
                        publishSyncing()
                    }

                    is MtprotoUpdate.ChatsChanged -> {
                        if (update.chats.isNotEmpty()) {
                            val mapped = withEffectiveMutes(
                                update.chats,
                                notifyDefaults,
                                notifyDefaultsLoaded,
                            )
                            // Capture, merge, and publish under one lock. If refresh or an
                            // incoming-message rebuild owns it, this update waits before taking
                            // its snapshot, so it cannot queue a stale replacement behind them.
                            val changed: List<Chat>
                            listPublicationMutex.withLock {
                                val before = listedById()
                                val listed = allListed()
                                refreshMergeHook?.invoke()
                                val rebuilt = if (listed.size > 64) {
                                    withContext(ioContext) { mergeChats(listed, mapped) }
                                } else {
                                    mergeChats(listed, mapped)
                                }
                                republishListedLocked(
                                    rebuilt,
                                    fromCache = false,
                                    keep = paintKeep(),
                                    hidden = mapped.filterNot { it.isShownInChatList() },
                                )
                                val rebuiltById = rebuilt.associateBy { it.id }
                                changed = mapped.filter { row ->
                                        val previous = before[row.id.value] ?: return@filter true
                                        previous.photoCacheKey != rebuiltById[row.id]?.photoCacheKey || previous.muted != row.muted || previous.title != row.title || previous.muteOverride != row.muteOverride || previous.unreadMark != row.unreadMark || previous.left != row.left || previous.archived != row.archived || previous.pinned != row.pinned || (row.pinnedOrder != Int.MAX_VALUE && previous.pinnedOrder != row.pinnedOrder)
                                    }.map { row -> rebuiltById[row.id] ?: row }
                            }
                            if (changed.isNotEmpty()) scope.launch { warmup?.upsertChats(changed) }
                        }
                        // Empty payload is peer metadata. Full getChats is
                        // only for differenceTooLong (Ignored below).
                    }

                    is MtprotoUpdate.Ignored -> {
                        if (update.kind == "difference_too_long") {
                            refresh(force = false)
                        }
                    }

                    is MtprotoUpdate.PeerTyping -> {
                        scope.launch {
                            applyPeerTyping(update)
                        }
                    }

                    is MtprotoUpdate.PeerStatus -> {
                        val current = state().chats.firstOrNull { it.id == update.userId }
                        val visible = current == null || LastSeen.affectsUi(
                            current.peerStatus,
                            current.peerStatusAt,
                            update.status,
                            update.statusAt,
                        )
                        if (current != null && visible) {
                            PeerPresence.record(
                                update.userId.value,
                                update.status,
                                update.statusAt,
                            )
                        }
                        val dotChanged = current != null && onlineDotChanged(
                            current.peerStatus,
                            current.peerStatusAt,
                            update.status,
                            update.statusAt,
                        )
                        if (dotChanged) {
                            listPublicationMutex.withLock {
                                dispatch(Msg.Status(update.userId, update.status, update.statusAt))
                            }
                        }
                        if (visible) {
                            scope.launch {
                                sessionStore?.updatePeerStatus(
                                    update.userId.value,
                                    update.status,
                                    update.statusAt,
                                )
                            }
                        }
                    }

                    is MtprotoUpdate.PeerEmojiStatus -> {
                        val current = state().chats.firstOrNull { it.id == update.userId }
                        if (current == null || current.emojiStatusDocumentId != update.documentId) {
                            if (current != null) {
                                listPublicationMutex.withLock {
                                    dispatch(Msg.EmojiStatus(update.userId, update.documentId))
                                }
                            }
                            scope.launch {
                                sessionStore?.updatePeerEmojiStatus(
                                    update.userId.value,
                                    update.documentId,
                                )
                            }
                        }
                    }

                    is MtprotoUpdate.NewMessage -> {
                        val named = withSenderName(update.message)
                        absorbIncoming(named, update.accountGeneration, update.acceptedOrder)
                    }

                    is MtprotoUpdate.MessageEdited -> {
                        listPublicationMutex.withLock {
                            dispatch(Msg.Edited(update.message))
                        }
                    }

                    is MtprotoUpdate.MessagesDeleted -> {
                        scope.launch {
                            if (!update.persisted) {
                                warmup?.deleteMessages(
                                    update.chatId,
                                    update.messageIds,
                                )
                            }
                            val ids = update.messageIds.toSet()
                            listPublicationMutex.withLock {
                                // Re-read after acquiring the publication boundary: refresh or
                                // an incoming message may already have advanced this dialog.
                                val affected = state().chats.filter { chat ->
                                    (update.chatId == null || update.chatId == chat.id) && chat.lastMessageId in ids
                                }
                                for (chat in affected) {
                                    val latest = warmup?.messages(chat.id, 1)?.firstOrNull()
                                    dispatch(Msg.LatestReplaced(chat.id, latest))
                                }
                            }
                        }
                    }

                    is MtprotoUpdate.ReadInbox -> {
                        val current = state().chats.firstOrNull { it.id == update.chatId }
                        if (current == null || current.readInboxMaxId != update.maxId || current.unreadCount != update.stillUnread.coerceAtLeast(
                                0,
                            )
                        ) {
                            listPublicationMutex.withLock {
                                dispatch(
                                    Msg.InboxRead(update.chatId, update.maxId, update.stillUnread),
                                )
                            }
                        }
                    }

                    is MtprotoUpdate.ReadOutbox -> {
                        val current = state().chats.firstOrNull { it.id == update.chatId }
                        if (current == null || current.readOutboxMaxId != update.maxId) {
                            listPublicationMutex.withLock {
                                dispatch(Msg.OutboxRead(update.chatId, update.maxId))
                            }
                        }
                    }

                    is MtprotoUpdate.ReadHistoryConfirmed -> {
                        val current = state().chats.firstOrNull { it.id == update.chatId }
                        if (current != null && current.readInboxMaxId < update.maxId) {
                            listPublicationMutex.withLock {
                                dispatch(Msg.InboxRead(update.chatId, update.maxId, unread = 0))
                            }
                        }
                    }

                    is MtprotoUpdate.UnreadMentions -> {
                        val current = state().chats.firstOrNull { it.id == update.chatId }
                        if (current == null || current.unreadMentionsCount != update.stillUnread.coerceAtLeast(
                                0,
                            )
                        ) {
                            listPublicationMutex.withLock {
                                dispatch(Msg.UnreadMentions(update.chatId, update.stillUnread))
                            }
                        }
                    }

                    is MtprotoUpdate.UnreadReactions -> {
                        val current = state().chats.firstOrNull { it.id == update.chatId }
                        if (current == null || current.unreadReactionsCount != update.stillUnread.coerceAtLeast(
                                0,
                            )
                        ) {
                            listPublicationMutex.withLock {
                                dispatch(Msg.UnreadReactions(update.chatId, update.stillUnread))
                            }
                        }
                    }

                    is MtprotoUpdate.UnreadMentionsDelta -> {
                        if (state().chats.any { it.id == update.chatId }) {
                            listPublicationMutex.withLock {
                                dispatch(Msg.UnreadMentionsDelta(update.chatId, update.delta))
                            }
                        }
                    }

                    is MtprotoUpdate.UnreadReactionsDelta -> {
                        if (state().chats.any { it.id == update.chatId }) {
                            listPublicationMutex.withLock {
                                dispatch(Msg.UnreadReactionsDelta(update.chatId, update.delta))
                            }
                        }
                    }

                    is MtprotoUpdate.DialogUnreadMark -> {
                        val current = state().chats.firstOrNull { it.id == update.chatId }
                        if (current == null || current.unreadMark != update.unread) {
                            listPublicationMutex.withLock {
                                dispatch(Msg.UnreadMark(update.chatId, update.unread))
                            }
                            // The mark is server state and now a cached column: keep both in step.
                            current?.let { row ->
                                scope.launch {
                                    warmup?.upsertChats(listOf(row.copy(unreadMark = update.unread)))
                                }
                            }
                        }
                    }

                    is MtprotoUpdate.NotifySettingsChanged -> {
                        scope.launch {
                            if (update.topicId != null) return@launch
                            if (update.peerKind == PEER_NOTIFY_KIND && update.chatId.value != 0L) {
                                applyPeerMute(update.chatId, update.muteUntil, update.muteSpecified)
                            } else {
                                loadNotifyDefaults(force = true)
                            }
                        }
                    }

                    else -> {
                        Unit
                    }
                }
            }.launchIn(scope)
        client.sessionLost().onEach {
                // Local sign-out signal; the real cause was logged when it died.
                dispatch(Msg.Error(TelegramError.sessionInvalidated()))
            }.launchIn(scope)
    }

    override fun executeIntent(intent: ChatsStore.Intent) {
        when (intent) {
            is ChatsStore.Intent.ConnectionStatusChanged -> {
                dispatch(Msg.ConnectionStatusChanged(intent.value))
            }

            ChatsStore.Intent.Refresh -> {
                refresh(force = true)
            }

            ChatsStore.Intent.LoadMore -> {
                if (state().query.isNotBlank()) loadMoreSearch() else loadMore()
            }

            is ChatsStore.Intent.FolderUnderfilled -> {
                val activation = state().folderActivation
                if (activation != null && activation.folderId == intent.folderId && activation.remainingPages == intent.remainingPages && activation.remainingPages > 0 && state().hasMore && state().error == null && state().query.isBlank() && !state().loadingMore && !state().loading && !refreshInFlight.get()) {
                    dispatch(Msg.FolderActivated(activation.copy(remainingPages = activation.remainingPages - 1)))
                    loadMore(discoverFolder = true)
                }
            }

            is ChatsStore.Intent.FolderSelected -> {
                val changed = activeFolderId != intent.folderId || activeFolder != intent.folder
                if (changed) {
                    val selection = ++folderSelectionGeneration
                    pendingFolderInitialLoad = null
                    dispatch(Msg.FolderActivated(null))
                    activeFolderId = intent.folderId
                    activeFolder = intent.folder
                    publishHasMore()
                    if (intent.folder == null && needsArchivePage(intent.folderId)) {
                        pendingFolderInitialLoad = intent
                        startPendingFolderLoad()
                    }
                    scope.launch {
                        listPublicationMutex.withLock {
                            if (selection != folderSelectionGeneration) return@launch
                            republishListedLocked(
                                allListed(),
                                fromCache = state().fromCache,
                                keep = paintKeep(),
                            )
                            intent.folder?.let { activateFolder(it, selection) }
                        }
                    }
                }
            }

            is ChatsStore.Intent.MarkUnread -> {
                markUnread(intent.chatId, intent.unread)
            }

            is ChatsStore.Intent.QueryChanged -> {
                dispatch(Msg.Query(intent.value))
                startSearch(intent.value, immediate = false)
            }

            ChatsStore.Intent.RetrySearch -> {
                startSearch(state().query, immediate = true)
            }

            is ChatsStore.Intent.MarkRead -> {
                markRead(intent.chatIds)
            }
        }
    }

    private fun markUnread(
        chatId: PeerId,
        unread: Boolean,
    ) {
        scope.launch {
            when (val result = client.markDialogUnread(chatId, unread)) {
                // The bridge echoes a local DialogUnreadMark update on success.
                is Outcome.Ok -> Unit

                is Outcome.Err -> AppLog.warn("chats", result.telegramError.logLine())
            }
        }
    }

    /**
     * Peer type defaults are what a dialog without its own mute setting inherits
     * (`account.getNotifySettings` for users / chats / broadcasts).
     */
    private suspend fun loadNotifyDefaults(force: Boolean = false) = notifyDefaultsMutex.withLock {
        if (notifyDefaultsRefreshed && !force) return@withLock
        val loggedOut =
            sessionStore?.isAuthorized() == false && client.isLocallyAuthorized() != Outcome.Ok(
                true,
            )
        if (loggedOut) return@withLock
        val (users, chats, broadcasts) = coroutineScope {
            val users = async { notifySettingsFor("users") }
            val chats = async { notifySettingsFor("chats") }
            val broadcasts = async { notifySettingsFor("broadcasts") }
            Triple(users.await(), chats.await(), broadcasts.await())
        }
        notifyDefaults = NotifyDefaults(
            users = users ?: return@withLock,
            chats = chats ?: return@withLock,
            broadcasts = broadcasts ?: return@withLock,
        )
        notifyDefaultsLoaded = true
        notifyDefaultsRefreshed = true
        // Cached for the next offline start; push decisions read the same copy.
        notifications?.notifyDefaults = notifyDefaults
        AppLog.api(
            "chats",
            "notify defaults users=${users.muteUntil} chats=${chats.muteUntil} " + "broadcasts=${broadcasts.muteUntil}",
        )
        listPublicationMutex.withLock {
            val current = state().chats
            if (current.isNotEmpty()) {
                val remapped = withEffectiveMutes(current, notifyDefaults, notifyDefaultsLoaded)
                if (remapped != current) {
                    dispatch(Msg.Chats(remapped, fromCache = false, replace = true))
                    // Keep the cache honest: a restart reads it before the defaults arrive again.
                    warmup?.upsertChats(remapped)
                }
            }
        }
    }

    private suspend fun notifySettingsFor(kind: String): NotifySettings? =
        when (val result = client.getNotifySettings(kind)) {
            is Outcome.Ok -> result.value
            is Outcome.Err -> null
        }

    /**
     * `account.updateNotifySettings` for one peer: the row and the cache show the new bell
     * straight away instead of waiting for the next dialog page.
     */
    private suspend fun applyPeerMute(
        chatId: PeerId,
        muteUntil: Int,
        specified: Boolean,
    ) {
        val updated = listPublicationMutex.withLock {
            val current = state().chats.firstOrNull { it.id == chatId } ?: return
            val next = if (specified) {
                current.withOwnMute(muteUntil, nowEpochSeconds())
            } else if (current.muteOverride || !notifyDefaultsLoaded) {
                current
            } else {
                withEffectiveMutes(listOf(current), notifyDefaults, true).single()
            }
            if (next == current) return
            dispatch(Msg.Chats(listOf(next), fromCache = false, replace = false))
            next
        }
        warmup?.upsertChats(listOf(updated))
    }

    private fun wireFolderId(folderId: Int?): Int = when (folderId) {
        null -> MAIN_FOLDER_WIRE_ID
        ARCHIVE_FOLDER_ID -> ARCHIVE_FOLDER_WIRE_ID
        else -> MAIN_FOLDER_WIRE_ID
    }

    private fun isFolderScopedStream(folderId: Int?): Boolean = folderId == ARCHIVE_FOLDER_ID

    /** A custom folder needs several main-stream pages per gesture to surface new members. */
    private fun pagesPerRequest(folderId: Int?): Int =
        if (folderId != null && folderId != ARCHIVE_FOLDER_ID) CUSTOM_FOLDER_PAGES else 1

    private fun fallbackArchiveFolderId(folderId: Int?): Int? = ARCHIVE_FOLDER_WIRE_ID.takeIf {
        folderId != null && folderId != MAIN_FOLDER_WIRE_ID && folderId != ARCHIVE_FOLDER_ID
    }

    private fun needsArchivePage(folderId: Int?): Boolean =
        (isFolderScopedStream(folderId) || fallbackArchiveFolderId(folderId) != null) && folderPaging[ARCHIVE_FOLDER_WIRE_ID]?.started != true && folderPaging[ARCHIVE_FOLDER_WIRE_ID]?.hasMore != false

    private fun networkHasMoreFor(folderId: Int?): Boolean {
        val archiveFallback = fallbackArchiveFolderId(folderId)
        return when {
            archiveFallback != null -> mainNetworkHasMore || folderArchivePaging[archiveFallback]?.hasMore != false
            isFolderScopedStream(folderId) -> folderPaging[wireFolderId(folderId)]?.hasMore != false
            else -> mainNetworkHasMore
        }
    }

    private fun publishHasMore() {
        val hasMore =
            state().folderActivation?.explicitComplete != true && (networkHasMoreFor(activeFolderId) || if (isFolderScopedStream(
                    activeFolderId
                )
            ) {
                cachedTail.any { it.isArchiveListRow() }
            } else {
                cachedTail.any { it.isMainListRow() } || state().chats.count { it.isMainListRow() } < roomMainCount
            })
        if (state().hasMore != hasMore) dispatch(Msg.HasMore(hasMore))
    }

    private fun markRead(chatIds: List<PeerId>) {
        if (chatIds.isEmpty()) return
        scope.launch {
            chatIds.distinct().forEach { id ->
                val chat = state().chat(id) ?: return@forEach
                if (chat.unreadCount <= 0 || chat.lastMessageId <= 0) return@forEach
                when (val result = client.readHistory(chat.id, chat.lastMessageId)) {
                    is Outcome.Ok -> {
                        listPublicationMutex.withLock {
                            dispatch(Msg.InboxRead(chat.id, chat.lastMessageId, unread = 0))
                        }
                    }

                    is Outcome.Err -> {
                        AppLog.warn("chats", result.telegramError.logLine())
                    }
                }
            }
        }
    }

    private suspend fun withSenderName(message: Message): Message {
        if (message.outgoing) return message
        if (!message.senderName.isNullOrBlank()) return message
        val senderId = message.senderId?.value ?: return message
        val title = withContext(ioContext) {
            sessionStore?.readProfile(senderId)?.title?.trim().orEmpty()
        }
        return if (title.isEmpty()) message else message.copy(senderName = title)
    }

    private fun loadSelf() {
        scope.launch {
            val store = sessionStore
            val id = store?.readAuthorizedUserId()
            val cached = id?.let { store.readProfile(it.value) }
            if (cached != null) dispatch(Msg.Self(cached))
            when (val result = client.getProfile(PeerId(0))) {
                is Outcome.Ok -> {
                    dispatch(Msg.Self(result.value))
                    store?.upsertProfile(result.value)
                    if (store?.readAuthorizedUserId() == null) {
                        store?.saveAuthorized(AuthSession(result.value.id, 0))
                    }
                }

                is Outcome.Err -> {
                    Unit
                }
            }
        }
    }

    private suspend fun getChatsTimed(label: String): Outcome<DialogPage> {
        if (!PerfLog.isEnabled()) return client.getChatsPage()
        val startedAt = PerfLog.nowMs()
        return try {
            client.getChatsPage()
        } finally {
            PerfLog.mark("chats:$label", PerfLog.nowMs() - startedAt)
        }
    }

    private fun refresh(force: Boolean) {
        if (!refreshInFlight.compareAndSet(false, true)) {
            pendingRefresh = true
            pendingRefreshForce = pendingRefreshForce || force
            return
        }
        attemptedTitleRecovery.clear()
        val hasMemory = state().chats.isNotEmpty()
        if (!hasMemory) dispatch(Msg.Loading(true))
        dispatch(Msg.Error(null))
        catalogJob?.cancel()
        val generation = ++catalogGeneration
        scope.launch {
            var catalogSeed: List<Chat>? = null
            try {
                val snapshot = if (!force) warmup?.takeStartupSnapshot() else null
                val cached = snapshot?.chats ?: warmup?.chatsWindow(DIALOGS_NETWORK_PAGE).orEmpty()
                val cachedMainCount =
                    snapshot?.mainCount ?: warmup?.mainListCount()?.takeIf { it > 0 }
                    ?: cached.count { it.isMainListRow() }
                if (cached.isNotEmpty() && (!hasMemory || force || snapshot != null)) {
                    AppLog.api(
                        "chats",
                        "cache hit count=${cached.size} main=$cachedMainCount",
                    )
                    val muted = muteChats(cached)
                    val cursor = listPublicationMutex.withLock {
                        val replace = allListed().isEmpty()
                        val (first, tail) = if (replace) {
                            if (muted.size > 64) {
                                withContext(ioContext) { paintDialogsWindow(muted) }
                            } else {
                                paintDialogsWindow(muted)
                            }
                        } else if (muted.size > 64) {
                            withContext(ioContext) { sortChats(muted) } to cachedTail
                        } else {
                            sortChats(muted) to cachedTail
                        }
                        roomMainCount = cachedMainCount
                        val shownIds = first.mapTo(HashSet()) { it.id }
                        cachedTail = if (replace) tail else tail.filterNot { it.id in shownIds }
                        dispatch(
                            Msg.Chats(
                                first, fromCache = true, replace = replace, offWindow = cachedTail
                            )
                        )
                        dispatch(Msg.Loading(false))
                        setRefreshSyncing(true)
                        publishHasMore()
                        recoverMissingTitles()
                        if (cachedTail.none { it.isMainListRow() }) cacheCursor(archived = false) else null
                    }
                    if (warmup != null && cursor != null) {
                        val extra = warmup.chatsAfter(cursor, DIALOGS_NETWORK_PAGE)
                            .filter { it.isMainListRow() }
                        listPublicationMutex.withLock {
                            if (cacheCursor(archived = false) == cursor && cachedTail.none { it.isMainListRow() }) {
                                cachedTail = extra + cachedTail
                                publishHasMore()
                            }
                        }
                    }
                    if (!isFolderScopedStream(activeFolderId) && cachedTail.any { it.isMainListRow() }) {
                        loadMore()
                    }
                    pendingReadStates?.let { rows ->
                        pendingReadStates = null
                        dispatch(Msg.ReadStates(rows))
                    }
                } else {
                    listPublicationMutex.withLock {
                        roomMainCount = cachedMainCount
                        publishHasMore()
                    }
                    if (!hasMemory && cached.isEmpty() && !state().loading) {
                        dispatch(Msg.Loading(true))
                    } else if (hasMemory || cached.isNotEmpty()) {
                        setRefreshSyncing(true)
                    }
                }
                // The first network page must already carry inherited mutes; a failure here is
                // retried on the next refresh so a slow RPC never blocks the list.
                var result = coroutineScope {
                    val dialogs = async { getChatsTimed("get") }
                    if (!notifyDefaultsLoaded) loadNotifyDefaults()
                    dialogs.await()
                }
                if (result is Outcome.Err && state().chats.isNotEmpty() && result.telegramError.kind == TelegramError.Kind.Network) {
                    delay(400)
                    result = getChatsTimed("retry")
                }
                when (result) {
                    is Outcome.Ok -> {
                        val firstPage = result.value
                        AppLog.api(
                            "chats",
                            "network count=${firstPage.chats.size} merge=${hasMemory || cached.isNotEmpty()}",
                        )
                        val mapped = muteChats(firstPage.chats)
                        val hidden = mapped.filterNot { it.isShownInChatList() }
                        AppLog.api(
                            "chats",
                            "mute state overrides=${mapped.count { it.muteOverride }} " + "muted=${mapped.count { it.muted }} of ${mapped.size}",
                        )
                        // Keep the live-list snapshot, expensive merge, and replace publication
                        // under one lock. A read/update completion cannot land between the
                        // snapshot and a stale large-list refresh publication.
                        var persisted = mapped
                        var pruneRows = mapped
                        listPublicationMutex.withLock {
                            val source = allListed()
                            refreshMergeHook?.invoke()
                            val merged = if (source.size > 64) {
                                withContext(ioContext) {
                                    withNetworkPinOrder(mergeChats(source, mapped), mapped)
                                }
                            } else {
                                withNetworkPinOrder(mergeChats(source, mapped), mapped)
                            }
                            persisted = merged
                            val mergedById = merged.associateBy { it.id }
                            pruneRows = mapped.filter { net ->
                                val local = mergedById[net.id]
                                local == null || net.lastMessageId >= local.lastMessageId
                            }
                            mainPagingGeneration++
                            lastNetworkPage = firstPage.chats
                            mainNextOffset = firstPage.nextOffset
                            mainNetworkHasMore = firstPage.nextOffset != null
                            failedOffsetPeers.clear()
                            preferMainOnNextFallbackLoad = false
                            republishListedLocked(
                                merged,
                                fromCache = false,
                                keep = paintKeep(),
                                hidden = hidden,
                            )
                        }
                        warmup?.upsertChats(if (hidden.isEmpty()) persisted else persisted + hidden)
                        sessionStore?.upsertPeersFromChats(persisted)
                        catalogSeed = mapped
                        if (!isFolderScopedStream(activeFolderId) && cachedTail.any { it.isMainListRow() }) {
                            loadMore()
                        }
                        val pruned = warmup?.pruneAheadOfLastMessage(pruneRows).orEmpty()
                        if (pruned.isNotEmpty()) {
                            val dropped = pruned.sumOf { it.droppedIds.size }
                            AppLog.api(
                                "chats",
                                "prune force=$force chats=${pruned.size} dropped=$dropped",
                            )
                            pruned.take(8).forEach { row ->
                                AppLog.api(
                                    "chats",
                                    "prune chat=${row.chatId} lastId=${row.lastMessageId} " + "cachedMax=${row.cachedMaxId} dropped=${row.droppedIds.size} " + "ids=${
                                        row.droppedIds.take(12).joinToString(",")
                                    }",
                                )
                            }
                        }
                        PerfLog.event("chats:refresh_complete", "result=ok")
                    }

                    is Outcome.Err -> {
                        PerfLog.event("chats:refresh_complete", "result=err")
                        AppLog.warn("chats", result.telegramError.logLine())
                        if (state().chats.isEmpty()) {
                            dispatch(Msg.Error(result.telegramError))
                        }
                    }
                }
                if (state().loading) dispatch(Msg.Loading(false))
                setRefreshSyncing(false)
                catalogSeed?.let { seed ->
                    if (generation == catalogGeneration) {
                        catalogJob = scope.launch {
                            try {
                                fillDialogCatalog(generation, archived = false, seed = seed)
                                fillDialogCatalog(
                                    generation,
                                    archived = true,
                                    seed = emptyList(),
                                )
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                AppLog.warn("chats", "catalog loading failed")
                            }
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                if (state().loading) dispatch(Msg.Loading(false))
                setRefreshSyncing(false)
            } finally {
                refreshInFlight.set(false)
                if (pendingRefresh) {
                    val rerunForce = pendingRefreshForce
                    pendingRefresh = false
                    pendingRefreshForce = false
                    refresh(rerunForce)
                } else {
                    startPendingFolderLoad()
                }
            }
        }
    }

    private suspend fun activateFolder(
        folder: Folder,
        selection: Long,
    ) {
        val explicitIds =
            (folder.chatIds + folder.pinnedChatIds).toSet() - folder.excludeChatIds.toSet()
        val listed =
            (state().chats + cachedTail).filter { it.id in explicitIds }.distinctBy { it.id }
        val missing = explicitIds - listed.map { it.id }.toSet()
        val roomMembers =
            if (missing.isEmpty()) emptyList() else warmup?.chatsByIds(missing.toList()).orEmpty()
        if (selection != folderSelectionGeneration) return
        val cachedMembers =
            withEffectiveMutes(listed + roomMembers, notifyDefaults, notifyDefaultsLoaded)
        val hasRules =
            folder.includeContacts || folder.includeNonContacts || folder.includeGroups || folder.includeChannels || folder.includeBots
        val activation = ChatsStore.FolderActivation(
            folder.id, cachedMembers, FOLDER_DISCOVERY_ATTEMPTS, explicitIds, hasRules
        )
        // Point lookups are a folder-only projection, not progress through either paging stream.
        dispatch(Msg.FolderActivated(if (activation.explicitComplete) activation.copy(remainingPages = 0) else activation))
        publishHasMore()
    }

    private fun startPendingFolderLoad(): Boolean {
        val pending = pendingFolderInitialLoad ?: return false
        if (state().loadingMore) return false
        pendingFolderInitialLoad = null
        if (!needsArchivePage(pending.folderId)) return false
        loadMore()
        return true
    }

    private fun loadMore(
        skipCache: Boolean = false,
        discoverFolder: Boolean = false,
    ) {
        if (state().loadingMore || state().folderActivation?.explicitComplete == true) return
        val requestGeneration = folderSelectionGeneration
        val requestFolderId = activeFolderId
        dispatch(Msg.LoadingMore(true))
        scope.launch {
            var hitAttemptLimit = false
            try {
                val archived = isFolderScopedStream(requestFolderId)
                val loadedCache = !skipCache && loadCachedPage(requestGeneration, archived)
                if (loadedCache) {
                    if (!discoverFolder) delay(100)
                } else {
                    hitAttemptLimit =
                        loadMoreFromNetwork(requestGeneration, requestFolderId, discoverFolder)
                }
            } finally {
                finishCachedPaging(retryArchive = !hitAttemptLimit || requestGeneration != folderSelectionGeneration)
            }
        }
    }

    private suspend fun loadCachedPage(
        requestGeneration: Long,
        archived: Boolean,
    ): Boolean {
        fun Chat.inStream(): Boolean = if (archived) isArchiveListRow() else isMainListRow()

        while (true) {
            val cursor = listPublicationMutex.withLock {
                if (requestGeneration != folderSelectionGeneration) return false
                val next = cachedTail.filter { it.inStream() }.take(DIALOGS_NETWORK_PAGE)
                if (next.isNotEmpty()) {
                    cachedTail = cachedTail.filter { chat -> next.none { it.id == chat.id } }
                    dispatch(Msg.Append(next))
                    publishHasMore()
                    return true
                }
                cacheCursor(archived)
            }
            if (warmup == null) return false
            val roomPage = if (cursor == null) {
                emptyList()
            } else {
                warmup.chatsAfter(cursor, DIALOGS_NETWORK_PAGE).filter { it.inStream() }
            }
            AppLog.api("chats", "loadMore room archived=$archived count=${roomPage.size}")
            listPublicationMutex.withLock {
                if (requestGeneration != folderSelectionGeneration) return false
                if (cacheCursor(archived) == cursor && cachedTail.none { it.inStream() }) {
                    if (roomPage.isNotEmpty()) {
                        dispatch(Msg.Append(roomPage))
                    } else if (!archived) {
                        roomMainCount = state().chats.count { it.isMainListRow() }
                    }
                    publishHasMore()
                    return roomPage.isNotEmpty()
                }
            }
        }
    }

    private fun cacheCursor(archived: Boolean): ChatCacheCursor? =
        listTail(allListed(), archived)?.let(ChatCacheCursor::from)

    private suspend fun loadMoreFromNetwork(
        requestGeneration: Long,
        requestFolderId: Int?,
        discoverFolder: Boolean,
    ): Boolean {
        var preferArchive = !preferMainOnNextFallbackLoad
        var fetched = 0
        val wire = wireFolderId(requestFolderId)
        val archiveFallback = fallbackArchiveFolderId(requestFolderId)
        val folderScoped = isFolderScopedStream(requestFolderId)
        val pageBudget = pagesPerRequest(requestFolderId)
        repeat(if (discoverFolder) 1 else 5) {
            val (stateful, cursor, useArchiveFallback, pagingGeneration) = listPublicationMutex.withLock {
                if (requestGeneration != folderSelectionGeneration || requestFolderId != activeFolderId) {
                    return false
                }
                if (state().folderActivation?.explicitComplete == true) return false
                if (state().error?.requiresReauth == true || !networkHasMoreFor(requestFolderId)) return false
                if (discoverFolder && (state().error != null || state().query.isNotBlank())) return false
                val useArchiveFallback =
                    archiveFallback != null && folderArchivePaging[archiveFallback]?.hasMore != false && (!mainNetworkHasMore || preferArchive)
                val stateful = when {
                    useArchiveFallback -> folderArchivePaging.getOrPut(archiveFallback) { FolderPaging() }
                    folderScoped -> folderPaging.getOrPut(wire) { FolderPaging() }
                    else -> null
                }
                val cursor = when {
                    stateful != null && !stateful.started -> {
                        Triple(0, 0, 0L)
                    }

                    stateful != null -> {
                        pagingCursor(
                            stateful.nextOffset,
                            stateful.page,
                            skipArchived = false,
                        )
                    }

                    archiveFallback != null && !mainNetworkHasMore -> {
                        null
                    }

                    else -> {
                        pagingCursor(mainNextOffset, lastNetworkPage, skipArchived = true)
                    }
                }
                if (cursor == null) {
                    if (stateful != null) {
                        stateful.hasMore = false
                    } else {
                        mainNetworkHasMore = false
                    }
                    publishHasMore()
                    if (networkHasMoreFor(requestFolderId)) return@withLock null
                    AppLog.api(
                        "chats",
                        "loadMore end folder=${if (useArchiveFallback) ARCHIVE_FOLDER_WIRE_ID else wire}",
                    )
                    return false
                }
                if (discoverFolder) {
                    preferMainOnNextFallbackLoad = useArchiveFallback
                } else if (stateful == null && archiveFallback != null) {
                    preferMainOnNextFallbackLoad = false
                }
                NetworkPageRequest(stateful, cursor, useArchiveFallback, mainPagingGeneration)
            } ?: return@repeat
            val (date, offsetId, peerId) = cursor
            val requestFolder = if (useArchiveFallback) ARCHIVE_FOLDER_WIRE_ID else wire
            AppLog.api(
                "chats",
                "loadMore folder=$requestFolder pages=$fetched/$pageBudget " + "offsetDate=$date offsetId=$offsetId peer=$peerId",
            )
            val result = client.loadMoreFolderChatsPage(requestFolder, date, offsetId, peerId)
            val mapped = listPublicationMutex.withLock {
                if (requestGeneration != folderSelectionGeneration && !(archiveFallback != null && fallbackArchiveFolderId(
                        activeFolderId
                    ) != null)
                ) {
                    return false
                }
                // A refresh resets the main cursor, but not the independently paged archive.
                if (stateful == null && pagingGeneration != mainPagingGeneration) return@withLock null
                when (result) {
                    is Outcome.Ok -> {
                        val page = result.value
                        if (stateful != null) {
                            stateful.page = page.chats
                            stateful.nextOffset = page.nextOffset
                            stateful.started = true
                            stateful.hasMore = page.nextOffset != null && pagingCursor(
                                page.nextOffset,
                                page.chats,
                                skipArchived = false,
                            )?.let { it != cursor } == true
                        } else {
                            lastNetworkPage = page.chats
                            mainNextOffset = page.nextOffset
                            mainNetworkHasMore =
                                mainNetworkHasMore && page.nextOffset != null && pagingCursor(
                                    page.nextOffset,
                                    page.chats,
                                    skipArchived = true,
                                )?.let { it != cursor } == true
                        }
                        AppLog.api("chats", "loadMore network count=${page.chats.size}")
                        val mapped = withEffectiveMutes(
                            page.chats,
                            notifyDefaults,
                            notifyDefaultsLoaded,
                        )
                        val ids = mapped.mapTo(HashSet()) { it.id }
                        cachedTail = cachedTail.filterNot { it.id in ids }
                        dispatch(Msg.Append(mapped))
                        publishHasMore()
                        mapped
                    }

                    is Outcome.Err -> {
                        val error = result.telegramError
                        AppLog.warn("chats", error.logLine())
                        when {
                            useArchiveFallback && error.type == FOLDER_ID_INVALID -> {
                                stateful?.hasMore = false
                                publishHasMore()
                                if (!networkHasMoreFor(requestFolderId)) return false
                            }

                            error.kind == TelegramError.Kind.Peer -> {
                                failedOffsetPeers += peerId
                                if (useArchiveFallback) preferMainOnNextFallbackLoad = true
                            }

                            error.kind == TelegramError.Kind.Network || error.kind == TelegramError.Kind.Flood || error.kind == TelegramError.Kind.Internal -> {
                                dispatch(Msg.Error(error))
                                return false
                            }

                            else -> {
                                dispatch(Msg.Error(error))
                                if (stateful != null) {
                                    stateful.hasMore = false
                                } else {
                                    mainNetworkHasMore = false
                                }
                                publishHasMore()
                                return false
                            }
                        }
                        null
                    }
                }
            } ?: return@repeat
            if (mapped.isNotEmpty()) {
                warmup?.upsertChats(mapped)
                sessionStore?.upsertPeersFromChats(mapped)
            }
            fetched += 1
            preferArchive = !preferArchive
            // Custom folders filter these streams locally, so a gesture may need several pages.
            if (fetched >= pageBudget) return false
        }
        return true
    }

    private fun pagingCursor(
        nextOffset: DialogOffset?,
        page: List<Chat>,
        skipArchived: Boolean,
    ): Triple<Int, Int, Long>? {
        val offset = nextOffset?.let { Triple(it.date, it.id, it.peerId) }
        if (offset != null && offset.third !in failedOffsetPeers) return offset
        return dialogsPageCursor(page, failedOffsetPeers, skipArchived = skipArchived)
    }

    private data class NetworkPageRequest(
        val stateful: FolderPaging?,
        val cursor: Triple<Int, Int, Long>,
        val useArchiveFallback: Boolean,
        val mainPagingGeneration: Long,
    )

    private fun finishCachedPaging(retryArchive: Boolean = true) {
        if (state().loadingMore) dispatch(Msg.LoadingMore(false))
        if (startPendingFolderLoad()) return
        if (retryArchive && activeFolderId == ARCHIVE_FOLDER_ID && needsArchivePage(activeFolderId) && state().error == null && !state().loadingMore) {
            loadMore(skipCache = true)
        }
    }

    private suspend fun applyPeerTyping(update: MtprotoUpdate.PeerTyping) {
        val chatId = update.chatId
        val userId = update.userId
        val jobKey = "${chatId.value}:${userId.value}"
        typingJobs.remove(jobKey)?.cancel()
        val chatMap = typingByChat.getOrPut(chatId.value) { LinkedHashMap() }
        val active = update.typing
        if (!active) {
            chatMap.remove(userId.value)
            if (chatMap.isEmpty()) typingByChat.remove(chatId.value)
            dispatch(typingMessage(chatId))
            return
        }
        val chat = state().chats.firstOrNull { it.id == chatId }
        val named = chat?.isGroup == true || chat?.isChannel == true
        val name = if (named) {
            sessionStore?.readProfile(userId.value)?.title
                ?: state().chats.firstOrNull { it.id == userId }?.title
                ?: chatMap[userId.value]?.name ?: ""
        } else {
            ""
        }
        chatMap.remove(userId.value)
        chatMap[userId.value] = TypingPresence(
            name = name,
            action = update.action.ifBlank { ChatActionKind.Typing.wire },
        )
        dispatch(typingMessage(chatId))
        typingJobs[jobKey] = scope.launch {
            delay(6_000.milliseconds)
            typingJobs.remove(jobKey)
            typingByChat[chatId.value]?.remove(userId.value)
            if (typingByChat[chatId.value].isNullOrEmpty()) {
                typingByChat.remove(chatId.value)
            }
            dispatch(typingMessage(chatId))
        }
    }

    private fun allListed(): List<Chat> = state().chats + cachedTail

    private fun listedById(): Map<Long, Chat> {
        val out = LinkedHashMap<Long, Chat>(state().chats.size + cachedTail.size)
        cachedTail.forEach { out[it.id.value] = it }
        state().chats.forEach { out[it.id.value] = it }
        return out
    }

    private fun listedChat(id: PeerId): Chat? =
        state().chats.firstOrNull { it.id == id } ?: cachedTail.firstOrNull { it.id == id }

    private fun paintKeep(): Int =
        state().chats.count { it.isMainListRow() }.coerceAtLeast(DIALOGS_PAINT_LIMIT)

    private fun archiveKeep(merged: List<Chat>): Int {
        val known = merged.count { it.isArchiveListRow() }
        return when {
            activeFolderId == ARCHIVE_FOLDER_ID -> {
                known.coerceAtLeast(ARCHIVE_PAINT_LIMIT)
            }

            activeFolderId != null && activeFolderId != MAIN_FOLDER_WIRE_ID -> {
                known.coerceAtLeast(ARCHIVE_PAINT_LIMIT)
            }

            else -> {
                ARCHIVE_PAINT_LIMIT
            }
        }
    }

    private suspend fun republishListedLocked(
        merged: List<Chat>,
        fromCache: Boolean,
        keep: Int,
        hidden: List<Chat> = emptyList(),
    ) {
        val archiveLimit = archiveKeep(merged)
        val (first, tail) = if (merged.size > 64) {
            withContext(ioContext) { paintDialogsWindow(merged, keep, archiveLimit) }
        } else {
            paintDialogsWindow(merged, keep, archiveLimit)
        }
        cachedTail = tail
        val painted = state().chats
        val sameWindow = painted == first
        val activation = state().folderActivation
        val offWindow =
            if (activation == null) emptyList() else (tail + hidden).filter { it.id in activation.explicitIds }
        if (!sameWindow || state().fromCache != fromCache || offWindow.any { it !in activation?.cachedMembers.orEmpty() }) {
            dispatch(Msg.Chats(first, fromCache = fromCache, replace = true, offWindow = offWindow))
        }
        publishHasMore()
        recoverMissingTitles()
    }

    private fun recoverMissingTitles() {
        if (titleRecoveryJob?.isActive == true) return
        titleRecoveryJob = scope.launch {
            // Resolve sequentially, once per refresh, so missing peers cannot flood the RPC queue.
            while (true) {
                val missing = state().chats.firstOrNull {
                    it.id !in attemptedTitleRecovery && isPlaceholderPeerTitle(
                        it.title,
                        it.id.value,
                    )
                } ?: break
                attemptedTitleRecovery += missing.id
                val cached = sessionStore?.readProfile(missing.id.value)
                val profile = cached?.takeUnless { isPlaceholderPeerTitle(it.title, it.id.value) }
                    ?: when (val result = client.getProfile(missing.id)) {
                        is Outcome.Ok -> result.value
                        is Outcome.Err -> continue
                    }
                if (isPlaceholderPeerTitle(profile.title, missing.id.value)) continue
                sessionStore?.upsertProfile(profile)
                // Re-read after the RPC: messages and read state may have changed while waiting.
                val updated = listPublicationMutex.withLock {
                    val current = listedChat(missing.id) ?: return@withLock null
                    if (!isPlaceholderPeerTitle(
                            current.title,
                            current.id.value,
                        )
                    ) {
                        return@withLock null
                    }
                    val next = current.copy(
                        title = profile.title,
                        isChannel = profile.kind == "channel",
                        isGroup = profile.kind == "group" || profile.kind == "chat",
                        photoCacheKey = if (profile.avatarKnown) {
                            profile.avatarCacheKey
                        } else {
                            profile.avatarCacheKey ?: current.photoCacheKey
                        },
                        photoKnown = profile.avatarKnown || profile.avatarCacheKey != null || current.photoKnown,
                        isVerified = profile.isVerified,
                    )
                    dispatch(Msg.Chats(listOf(next), fromCache = false, replace = false))
                    next
                } ?: continue
                warmup?.upsertChats(listOf(updated))
            }
        }
    }

    private fun absorbIncoming(
        message: Message,
        accountGeneration: Long = 0,
        acceptedOrder: Long = 0,
    ) {
        scope.launch {
            var persistHidden: Chat? = null
            val published = listPublicationMutex.withLock {
                val current = listedChat(message.id.chatId)
                when {
                    current == null -> {
                        false
                    }

                    !current.isShownInChatList() -> {
                        persistHidden = current.withIncomingMessage(
                            message,
                            accountGeneration,
                            acceptedOrder,
                        )
                        true
                    }

                    else -> {
                        cachedTail = cachedTail.filter { it.id != current.id }
                        val listed = buildList {
                            addAll(state().chats)
                            addAll(cachedTail)
                            if (none { it.id == current.id }) add(current)
                        }
                        republishListedLocked(
                            applyIncomingMessage(
                                listed,
                                message,
                                accountGeneration,
                                acceptedOrder,
                            ),
                            fromCache = false,
                            keep = paintKeep(),
                        )
                        true
                    }
                }
            }
            persistHidden?.let { warmup?.upsertChats(listOf(it)) }
            if (published) return@launch
            val stored = withContext(ioContext) {
                warmup?.chat(message.id.chatId)
            } ?: return@launch
            if (!stored.isMainListRow()) {
                warmup?.upsertChats(
                    listOf(
                        stored.withIncomingMessage(
                            message,
                            accountGeneration,
                            acceptedOrder,
                        ),
                    ),
                )
                return@launch
            }
            listPublicationMutex.withLock {
                republishListedLocked(
                    applyIncomingMessage(
                        allListed() + stored,
                        message,
                        accountGeneration,
                        acceptedOrder,
                    ),
                    fromCache = false,
                    keep = paintKeep(),
                )
            }
        }
    }

    private suspend fun fillDialogCatalog(
        generation: Int,
        archived: Boolean,
        seed: List<Chat>,
    ) {
        var cursor = catalogCursor(archived, seed) ?: return
        val startOffset = DialogOffset(cursor.first, cursor.second, cursor.third)
        val foregroundGeneration = if (archived) {
            null
        } else {
            listPublicationMutex.withLock {
                mainPagingGeneration.takeIf { mainNextOffset == startOffset }
            }
        }
        var requests = 0
        var sawRows = false
        // Warm at most one foreground page budget; scrolling owns further fetches.
        while (requests < CUSTOM_FOLDER_PAGES && generation == catalogGeneration) {
            coroutineContext.ensureActive()
            // Catalog hydration is optional; don't make catch-up yield to another page.
            protocolSyncing.first { !it }
            requests++
            val (date, offsetId, peerId) = cursor
            val result = client.loadMoreFolderChatsPage(
                if (archived) ARCHIVE_FOLDER_WIRE_ID else MAIN_FOLDER_WIRE_ID,
                date,
                offsetId,
                peerId,
            )
            coroutineContext.ensureActive()
            if (generation != catalogGeneration) return
            when (result) {
                is Outcome.Err -> {
                    val wait = result.telegramError.retryAfterSeconds
                    if (result.telegramError.kind == TelegramError.Kind.Flood && wait != null && wait > 0) {
                        delay(wait.seconds)
                        continue
                    }
                    AppLog.warn("chats", result.telegramError.logLine())
                    return
                }

                is Outcome.Ok -> {
                    val page = result.value
                    val mapped = muteChats(page.chats)
                    coroutineContext.ensureActive()
                    if (generation != catalogGeneration) return
                    if (page.chats.isNotEmpty()) sawRows = true
                    if (mapped.isNotEmpty()) warmup?.upsertChats(mapped)
                    coroutineContext.ensureActive()
                    if (generation != catalogGeneration) return
                    val nextOffset = page.nextOffset
                    if (nextOffset == null) {
                        if (!archived) {
                            val count = warmup?.mainListCount() ?: roomMainCount
                            coroutineContext.ensureActive()
                            listPublicationMutex.withLock {
                                if (generation != catalogGeneration) return
                                // Cached rows may be outside the visible Room cursor or carry unpublished changes.
                                if (!sawRows && foregroundGeneration == mainPagingGeneration && mainNextOffset == startOffset && startOffset.peerId !in failedOffsetPeers) {
                                    mainNetworkHasMore = false
                                }
                                roomMainCount = count
                                publishHasMore()
                            }
                        }
                        return
                    }
                    val next = Triple(nextOffset.date, nextOffset.id, nextOffset.peerId)
                    if (next == cursor) return
                    cursor = next
                }
            }
        }
    }

    private suspend fun catalogCursor(
        archived: Boolean,
        seed: List<Chat>,
    ): Triple<Int, Int, Long>? {
        val seedCursor = dialogsPageCursor(seed, skipArchived = !archived)
        val saved = warmup?.oldestListed(archived)?.let { dialogsPageOffset(it) }
        if (!archived && seed.isNotEmpty() && !mainNetworkHasMore) {
            if (saved != null && seedCursor != null && offsetIsOlder(
                    saved,
                    seedCursor,
                )
            ) {
                return saved
            }
            return null
        }
        if (saved != null && (seedCursor == null || offsetIsOlder(saved, seedCursor))) return saved
        if (!archived) return seedCursor
        // A real database can keep the archive catalog. In-memory tests have nothing to store,
        // and a zero-offset probe would consume the page the visible archive loader still owns.
        return if (warmup?.usesIoDispatcher == true) Triple(0, 0, 0L) else null
    }

    private fun offsetIsOlder(
        left: Triple<Int, Int, Long>,
        right: Triple<Int, Int, Long>,
    ): Boolean =
        left.first < right.first || (left.first == right.first && left.second < right.second) || (left.first == right.first && left.second == right.second && left.third < right.third)

    private suspend fun muteChats(chats: List<Chat>): List<Chat> {
        val apply = {
            withEffectiveMutes(chats, notifyDefaults, notifyDefaultsLoaded)
        }
        return if (chats.size > 64) {
            withContext(ioContext) { apply() }
        } else {
            apply()
        }
    }

    private fun startSearch(
        query: String,
        immediate: Boolean,
    ) {
        searchJob?.cancel()
        searchMoreJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            searchGen++
            lastRunSearchQuery = ""
            searchNextRate = 0
            searchNextPeerId = 0L
            searchNextOffsetId = 0
            dispatch(Msg.SearchCleared)
            return
        }
        val gen = ++searchGen
        lastRunSearchQuery = ""
        searchNextRate = 0
        searchNextPeerId = 0L
        searchNextOffsetId = 0
        searchJob = scope.launch {
            if (!immediate) delay(GLOBAL_SEARCH_DEBOUNCE_MS)
            if (gen != searchGen) return@launch
            runSearch(trimmed, gen, reset = true)
        }
    }

    private fun loadMoreSearch() {
        val query = state().query.trim()
        if (query.isEmpty() || query != lastRunSearchQuery || state().searchLoading || state().searchLoadingMore || state().searchError != null || !state().searchHasMore) {
            return
        }
        val gen = searchGen
        searchMoreJob?.cancel()
        searchMoreJob = scope.launch {
            runSearch(query, gen, reset = false)
        }
    }

    private suspend fun runSearch(
        query: String,
        gen: Int,
        reset: Boolean,
    ) {
        if (reset) {
            dispatch(Msg.SearchLoading(true))
            searchNextRate = 0
            searchNextPeerId = 0L
            searchNextOffsetId = 0
        } else {
            dispatch(Msg.SearchLoadingMore(true))
        }
        if (reset) lastRunSearchQuery = query
        val folderId = searchFolderId(activeFolderId)
        val knownIds = localSearchMatchIds(state().chats, query)
        try {
            if (reset) {
                coroutineScope {
                    val contactsDeferred = async {
                        client.contactsSearch(query, GLOBAL_SEARCH_LIMIT)
                    }
                    val globalDeferred = async {
                        client.searchGlobal(
                            query = query,
                            offsetRate = 0,
                            offsetPeerId = PeerId(0),
                            offsetId = 0,
                            limit = GLOBAL_SEARCH_LIMIT,
                            folderId = folderId,
                        )
                    }
                    val contacts = contactsDeferred.await()
                    val global = globalDeferred.await()
                    if (gen != searchGen) return@coroutineScope
                    applySearchResults(
                        gen = gen,
                        contacts = contacts,
                        global = global,
                        knownIds = knownIds,
                        reset = true,
                    )
                }
            } else {
                val global = client.searchGlobal(
                    query = query,
                    offsetRate = searchNextRate,
                    offsetPeerId = PeerId(searchNextPeerId),
                    offsetId = searchNextOffsetId,
                    limit = GLOBAL_SEARCH_LIMIT,
                    folderId = folderId,
                )
                if (gen != searchGen) return
                applySearchResults(
                    gen = gen,
                    contacts = Outcome.Ok(
                        ContactsSearch(
                            people = state().searchPeople,
                            chats = state().searchChats,
                        ),
                    ),
                    global = global,
                    knownIds = knownIds,
                    reset = false,
                )
            }
        } catch (e: CancellationException) {
            throw e
        }
    }

    private fun applySearchResults(
        gen: Int,
        contacts: Outcome<ContactsSearch>,
        global: Outcome<GlobalMessageSearch>,
        knownIds: Set<Long>,
        reset: Boolean,
    ) {
        if (gen != searchGen) return
        var people = emptyList<SearchPeer>()
        var foundChats = emptyList<SearchPeer>()
        var messages = emptyList<Message>()
        var contactsErr: TelegramError? = null
        var globalErr: TelegramError? = null
        when (contacts) {
            is Outcome.Ok -> {
                people = excludeKnownPeers(contacts.value.people, knownIds)
                foundChats = excludeKnownPeers(
                    contacts.value.chats,
                    knownIds + people.map { it.id.value },
                )
            }

            is Outcome.Err -> {
                contactsErr = contacts.telegramError
            }
        }
        when (global) {
            is Outcome.Ok -> {
                val page = global.value
                searchNextRate = page.nextRate
                searchNextPeerId = page.nextPeerId.value
                searchNextOffsetId = page.nextOffsetId
                val existing = if (reset) {
                    emptySet()
                } else {
                    state().searchMessages.map { it.id }.toSet()
                }
                messages = page.messages.filter { it.id !in existing }
                val hasMore = globalSearchHasMore(
                    page.messages.size,
                    page.nextRate,
                    page.nextPeerId.value,
                    page.nextOffsetId,
                )
                dispatch(
                    Msg.SearchPage(
                        people = people,
                        chats = foundChats,
                        messages = messages,
                        replace = reset,
                        hasMore = hasMore,
                    ),
                )
                AppLog.api(
                    "chats",
                    "search people=${people.size} chats=${foundChats.size} messages=${messages.size}",
                )
            }

            is Outcome.Err -> {
                globalErr = global.telegramError
                if (reset) {
                    dispatch(
                        Msg.SearchPage(
                            people = people,
                            chats = foundChats,
                            messages = emptyList(),
                            replace = true,
                            hasMore = false,
                        ),
                    )
                } else {
                    dispatch(Msg.SearchLoadingMore(false))
                }
            }
        }
        val err = globalErr ?: contactsErr
        if (err != null && !isEmptySearchQuery(err)) {
            dispatch(Msg.SearchError(err))
        } else if (err != null) {
            dispatch(Msg.SearchLoading(false))
            dispatch(Msg.SearchLoadingMore(false))
        } else if (global is Outcome.Ok && contacts is Outcome.Err) {
            dispatch(Msg.SearchError(contacts.telegramError))
        }
    }

    private fun typingMessage(chatId: PeerId): Msg.Typing {
        val chat = state().chats.firstOrNull { it.id == chatId }
        val named = chat?.isGroup == true || chat?.isChannel == true
        val shown = displayedChatAction(
            typingByChat[chatId.value]?.values.orEmpty(),
            named = named,
        )
        return Msg.Typing(
            chatId = chatId,
            typing = shown != null,
            names = shown?.names.orEmpty(),
            action = shown?.action,
        )
    }
}

private fun onlineDotChanged(
    previousStatus: String?,
    previousAt: Long?,
    nextStatus: String?,
    nextAt: Long?,
): Boolean {
    val now = System.currentTimeMillis()
    val wasOnline = LastSeen.resolve(previousStatus, previousAt, now) == LastSeen.Online
    val nowOnline = LastSeen.resolve(nextStatus, nextAt, now) == LastSeen.Online
    return wasOnline != nowOnline
}
