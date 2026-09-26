package org.monogram.feature.chats

import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds
import org.monogram.core.common.AppLog
import org.monogram.core.common.Outcome
import org.monogram.core.common.push.NotificationLocalStore
import org.monogram.core.common.telegram.TelegramError
import org.monogram.core.database.OfflineWarmup
import org.monogram.core.database.SessionMetadataStore
import org.monogram.core.database.dao.ChatReadState
import org.monogram.core.models.ARCHIVE_FOLDER_ID
import org.monogram.core.models.AuthSession
import org.monogram.core.models.Chat
import org.monogram.core.models.ChatActionKind
import org.monogram.core.models.ContactsSearch
import org.monogram.core.models.GlobalMessageSearch
import org.monogram.core.models.LastSeen
import org.monogram.core.models.Message
import org.monogram.core.models.NotifyDefaults
import org.monogram.core.models.SearchPeer
import org.monogram.core.models.NotifySettings
import org.monogram.core.models.PeerId
import org.monogram.core.models.TypingPresence
import org.monogram.core.models.displayedChatAction
import org.monogram.core.models.isPlaceholderPeerTitle
import org.monogram.network.bridge.MtprotoClient
import org.monogram.network.bridge.MtprotoUpdate

internal class ChatsExecutor(
    private val client: MtprotoClient,
    private val warmup: OfflineWarmup?,
    private val sessionStore: SessionMetadataStore?,
    private val notifications: NotificationLocalStore?,
    private val refreshMergeHook: (suspend () -> Unit)? = null,
    private val readStateHook: (suspend () -> Unit)? = null,
) : CoroutineExecutor<ChatsStore.Intent, Unit, ChatsStore.State, Msg, Nothing>() {
    private val refreshInFlight = AtomicBoolean(false)
    private val listPublicationMutex = Mutex()
    private var lastNetworkPage: List<Chat> = emptyList()
    private var mainNetworkHasMore = true
    private val failedOffsetPeers = mutableSetOf<Long>()

    /** Per-type notification defaults, inherited by dialogs without their own mute setting. */
    private var notifyDefaults = NotifyDefaults()
    private var notifyDefaultsLoaded = false
    private var notifyDefaultsRefreshed = false
    private val notifyDefaultsMutex = Mutex()

    /** Folder paging state, keyed by the wire folder id (0 = main list). */
    private var activeFolderId: Int? = null
    private var folderSelectionGeneration = 0L
    private var pendingFolderInitialLoad: Int? = null
    private val folderPaging = HashMap<Int, FolderPaging>()
    /** Archive cursor used when a custom filter has no server-side dialog stream. */
    private val folderArchivePaging = HashMap<Int, FolderPaging>()

    /**
     * Dialog filters whose id the server refuses in `messages.getDialogs`
     * (`FOLDER_ID_INVALID`). Those folders fall back to paging both main and archive streams and
     * filtering locally; the failure is remembered so it is probed once per process.
     */
    private val folderStreamRejected = mutableSetOf<Int>()
    private val typingByChat = HashMap<Long, LinkedHashMap<Long, TypingPresence>>()
    private val typingJobs = HashMap<String, Job>()
    private var pendingReadStates: Map<Long, ChatReadState>? = null
    private var cachedTail: List<Chat> = emptyList()
    private var roomMainCount = 0
    private var titleRecoveryJob: Job? = null
    private val attemptedTitleRecovery = mutableSetOf<PeerId>()
    private var searchJob: Job? = null
    private var searchMoreJob: Job? = null
    private var searchGen = 0
    private var searchNextRate = 0
    private var searchNextPeerId = 0L
    private var searchNextOffsetId = 0
    private var lastRunSearchQuery = ""
    override fun executeAction(action: Unit) {
        warmup?.observeReadStates()
            ?.map { rows -> rows.associateBy { it.id } }
            ?.distinctUntilChanged()
            ?.flowOn(Dispatchers.Default)
            ?.onEach { rows ->
                if (state().chats.isEmpty()) {
                    pendingReadStates = rows
                    return@onEach
                }
                pendingReadStates = null
                readStateHook?.invoke()
                listPublicationMutex.withLock {
                    dispatch(Msg.ReadStates(rows))
                }
            }
            ?.launchIn(scope)
        loadSelf()
        // A previous run cached the account defaults: the list must keep the right bell before
        // the settings RPC (or its failure) decides anything.
        notifications?.notifyDefaults?.let { cached ->
            notifyDefaults = cached
            notifyDefaultsLoaded = true
        }
        scope.launch { loadNotifyDefaults() }
        refresh(force = false)
        client.updates()
            .onEach { update ->
                when (update) {
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
                                    withContext(Dispatchers.IO) { mergeChats(listed, mapped) }
                                } else {
                                    mergeChats(listed, mapped)
                                }
                                val merged = mergeChats(allListed(), rebuilt)
                                republishListedLocked(
                                    merged,
                                    fromCache = false,
                                    hasMore = null,
                                    keep = paintKeep(),
                                )
                                changed = mapped.filter { row ->
                                    val previous = before[row.id.value] ?: return@filter true
                                    previous.muted != row.muted ||
                                        previous.title != row.title ||
                                        previous.muteOverride != row.muteOverride ||
                                        previous.unreadMark != row.unreadMark
                                }
                            }
                            if (changed.isNotEmpty()) scope.launch { warmup?.upsertChats(changed) }
                        }
                        // Empty payload is peer metadata. Full getChats is
                        // only for differenceTooLong (Ignored below).
                    }
                    is MtprotoUpdate.Ignored -> if (update.kind == "difference_too_long") {
                        refresh(force = true)
                    }
                    is MtprotoUpdate.PeerTyping -> scope.launch {
                        applyPeerTyping(update)
                    }
                    is MtprotoUpdate.PeerStatus -> {
                        val current = state().chats.firstOrNull { it.id == update.userId }
                        val visible = current == null || LastSeen.affectsUi(
                            current.peerStatus,
                            current.peerStatusAt,
                            update.status,
                            update.statusAt,
                        )
                        if (visible && current != null) {
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
                        absorbIncoming(named)
                    }
                    is MtprotoUpdate.MessageEdited -> {
                        listPublicationMutex.withLock {
                            dispatch(Msg.Edited(update.message))
                        }
                    }
                    is MtprotoUpdate.MessagesDeleted -> {
                        scope.launch {
                            warmup?.deleteMessages(update.chatId, update.messageIds)
                            val ids = update.messageIds.toSet()
                            listPublicationMutex.withLock {
                                // Re-read after acquiring the publication boundary: refresh or
                                // an incoming message may already have advanced this dialog.
                                val affected = state().chats.filter { chat ->
                                    (update.chatId == null || update.chatId == chat.id) &&
                                        chat.lastMessageId in ids
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
                        if (current == null ||
                            current.readInboxMaxId != update.maxId ||
                            current.unreadCount != update.stillUnread.coerceAtLeast(0)
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
                        if (current == null || current.unreadMentionsCount != update.stillUnread.coerceAtLeast(0)) {
                            listPublicationMutex.withLock {
                                dispatch(Msg.UnreadMentions(update.chatId, update.stillUnread))
                            }
                        }
                    }
                    is MtprotoUpdate.UnreadReactions -> {
                        val current = state().chats.firstOrNull { it.id == update.chatId }
                        if (current == null || current.unreadReactionsCount != update.stillUnread.coerceAtLeast(0)) {
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
                    is MtprotoUpdate.NotifySettingsChanged -> scope.launch {
                        if (update.peerKind == PEER_NOTIFY_KIND && update.chatId.value != 0L) {
                            applyPeerMute(update.chatId, update.muteUntil)
                        } else {
                            loadNotifyDefaults(force = true)
                        }
                    }
                    else -> Unit
                }
            }
            .launchIn(scope)
        client.sessionLost()
            .onEach {
                // Local sign-out signal; the real cause was logged when it died.
                dispatch(Msg.Error(TelegramError.sessionInvalidated()))
            }
            .launchIn(scope)
    }

    override fun executeIntent(intent: ChatsStore.Intent) {
        when (intent) {
            ChatsStore.Intent.Refresh -> refresh(force = true)
            ChatsStore.Intent.LoadMore -> {
                if (state().query.isNotBlank()) loadMoreSearch() else loadMore()
            }
            is ChatsStore.Intent.FolderSelected -> {
                val changed = activeFolderId != intent.folderId
                if (changed) folderSelectionGeneration++
                activeFolderId = intent.folderId
                dispatch(Msg.HasMore(hasMoreFor(intent.folderId)))
                // A folder that owns a server stream (the archive) fetches its first page on
                // open; custom folders keep growing through the main stream. If another folder
                // request is still active, defer this initial load until that request releases
                // the shared loading gate.
                val wire = wireFolderId(intent.folderId)
                val needsInitial = changed && isFolderScopedStream(intent.folderId) &&
                    folderPaging[wire]?.started != true
                if (needsInitial) {
                    dispatch(Msg.HasMore(true))
                    if (state().loadingMore) {
                        pendingFolderInitialLoad = intent.folderId
                    } else {
                        loadMore()
                    }
                }
                if (changed) {
                    scope.launch {
                        listPublicationMutex.withLock {
                            republishListedLocked(
                                allListed(),
                                fromCache = state().fromCache,
                                hasMore = hasMoreFor(intent.folderId),
                                keep = paintKeep(),
                            )
                        }
                    }
                }
            }
            is ChatsStore.Intent.MarkUnread -> markUnread(intent.chatId, intent.unread)
            is ChatsStore.Intent.QueryChanged -> {
                dispatch(Msg.Query(intent.value))
                startSearch(intent.value, immediate = false)
            }
            ChatsStore.Intent.RetrySearch -> startSearch(state().query, immediate = true)
            is ChatsStore.Intent.MarkRead -> markRead(intent.chatIds)
        }
    }

    private fun markUnread(chatId: PeerId, unread: Boolean) {
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
        val users = notifySettingsFor("users") ?: return@withLock
        val chats = notifySettingsFor("chats") ?: return@withLock
        val broadcasts = notifySettingsFor("broadcasts") ?: return@withLock
        notifyDefaults = NotifyDefaults(users = users, chats = chats, broadcasts = broadcasts)
        notifyDefaultsLoaded = true
        notifyDefaultsRefreshed = true
        // Cached for the next offline start; push decisions read the same copy.
        notifications?.notifyDefaults = notifyDefaults
        AppLog.api(
            "chats",
            "notify defaults users=${users.muteUntil} chats=${chats.muteUntil} " +
                "broadcasts=${broadcasts.muteUntil}",
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
    private suspend fun applyPeerMute(chatId: PeerId, muteUntil: Int) {
        val updated = listPublicationMutex.withLock {
            val current = state().chats.firstOrNull { it.id == chatId } ?: return
            val next = current.withOwnMute(muteUntil, nowEpochSeconds())
            if (next == current) return
            dispatch(Msg.Chats(listOf(next), fromCache = false, replace = false))
            next
        }
        warmup?.upsertChats(listOf(updated))
    }

    private fun wireFolderId(folderId: Int?): Int = when (folderId) {
        null -> MAIN_FOLDER_WIRE_ID
        ARCHIVE_FOLDER_ID -> ARCHIVE_FOLDER_WIRE_ID
        else -> if (folderId in folderStreamRejected) MAIN_FOLDER_WIRE_ID else folderId
    }

    /** A folder has its own server-side dialog stream unless the server refused its id. */
    private fun isFolderScopedStream(folderId: Int?): Boolean = when (folderId) {
        null, MAIN_FOLDER_WIRE_ID -> false
        ARCHIVE_FOLDER_ID -> true
        else -> folderId !in folderStreamRejected
    }

    /** A custom folder needs several main-stream pages per gesture to surface new members. */
    private fun pagesPerRequest(folderId: Int?): Int =
        if (folderId != null && folderId != ARCHIVE_FOLDER_ID) CUSTOM_FOLDER_PAGES else 1

    private fun fallbackArchiveFolderId(folderId: Int?): Int? = folderId?.takeIf {
        it != ARCHIVE_FOLDER_ID && it in folderStreamRejected
    }

    private fun hasMoreFor(folderId: Int?): Boolean {
        val wire = wireFolderId(folderId)
        val archiveFallback = fallbackArchiveFolderId(folderId)
        return when {
            archiveFallback != null ->
                mainNetworkHasMore || (folderArchivePaging[archiveFallback]?.hasMore ?: true)
            folderId == null || folderId == MAIN_FOLDER_WIRE_ID ->
                cachedTail.any { it.isMainListRow() } || mainNetworkHasMore
            folderId == ARCHIVE_FOLDER_ID ->
                cachedTail.any { it.isArchiveListRow() } ||
                    (folderPaging[wire]?.hasMore ?: true)
            else -> folderPaging[wire]?.hasMore ?: true
        }
    }

    private fun markRead(chatIds: List<PeerId>) {
        if (chatIds.isEmpty()) return
        scope.launch {
            chatIds.distinct().forEach { id ->
                val chat = state().chats.firstOrNull { it.id == id } ?: return@forEach
                if (chat.unreadCount <= 0 || chat.lastMessageId <= 0) return@forEach
                when (val result = client.readHistory(chat.id, chat.lastMessageId)) {
                    is Outcome.Ok -> listPublicationMutex.withLock {
                        dispatch(Msg.InboxRead(chat.id, chat.lastMessageId, unread = 0))
                    }
                    is Outcome.Err -> AppLog.warn("chats", result.telegramError.logLine())
                }
            }
        }
    }

    private suspend fun withSenderName(message: Message): Message {
        if (message.outgoing) return message
        if (!message.senderName.isNullOrBlank()) return message
        val senderId = message.senderId?.value ?: return message
        val title = withContext(Dispatchers.IO) {
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
            if (cached?.avatarCacheKey != null && cached.status != null) return@launch
            when (val result = client.getProfile(PeerId(0))) {
                is Outcome.Ok -> {
                    dispatch(Msg.Self(result.value))
                    store?.upsertProfile(result.value)
                    if (store?.readAuthorizedUserId() == null) {
                        store?.saveAuthorized(AuthSession(result.value.id, 0))
                    }
                }
                is Outcome.Err -> Unit
            }
        }
    }

    private fun refresh(force: Boolean) {
        if (!refreshInFlight.compareAndSet(false, true)) return
        attemptedTitleRecovery.clear()
        val hasMemory = state().chats.isNotEmpty()
        if (!hasMemory) dispatch(Msg.Loading(true))
        dispatch(Msg.Error(null))
        scope.launch {
            try {
                val cached = warmup?.chatsWindow(DIALOGS_NETWORK_PAGE).orEmpty()
                roomMainCount = warmup?.mainListCount()?.takeIf { it > 0 }
                    ?: cached.count { it.isMainListRow() }
                if (cached.isNotEmpty() && (!hasMemory || force)) {
                    AppLog.api(
                        "chats",
                        "cache hit count=${cached.size} main=$roomMainCount",
                    )
                    val muted = muteChats(cached)
                    val (first, tail) = if (!hasMemory) {
                        if (muted.size > 64) {
                            withContext(Dispatchers.IO) { paintDialogsWindow(muted) }
                        } else {
                            paintDialogsWindow(muted)
                        }
                    } else if (muted.size > 64) {
                        withContext(Dispatchers.IO) { sortChats(muted) } to emptyList()
                    } else {
                        sortChats(muted) to emptyList()
                    }
                    dispatch(
                        Msg.Chats(
                            first,
                            fromCache = true,
                            replace = !hasMemory,
                        ),
                    )
                    dispatch(Msg.Loading(false))
                    dispatch(Msg.Syncing(true))
                    cachedTail = tail
                    recoverMissingTitles()
                    if (cachedTail.none { it.isMainListRow() }) {
                        val extra = warmup?.chatsExcluding(
                            first.map { it.id.value },
                            DIALOGS_NETWORK_PAGE,
                        ).orEmpty().filter { it.isMainListRow() }
                        cachedTail = extra + cachedTail
                    }
                    val loadedMain = first.count { it.isMainListRow() } +
                        cachedTail.count { it.isMainListRow() }
                    dispatch(
                        Msg.HasMore(
                            cachedTail.any { it.isMainListRow() } || loadedMain < roomMainCount,
                        ),
                    )
                    if (!isFolderScopedStream(activeFolderId) &&
                        cachedTail.any { it.isMainListRow() }
                    ) {
                        loadMore()
                    }
                    pendingReadStates?.let { rows ->
                        pendingReadStates = null
                        dispatch(Msg.ReadStates(rows))
                    }
                } else if (!hasMemory && cached.isEmpty() && !state().loading) {
                    dispatch(Msg.Loading(true))
                } else if (hasMemory || cached.isNotEmpty()) {
                    dispatch(Msg.Syncing(true))
                }
                // The first network page must already carry inherited mutes; a failure here is
                // retried on the next refresh so a slow RPC never blocks the list.
                if (!notifyDefaultsLoaded) loadNotifyDefaults()
                var result = client.getChats()
                if (result is Outcome.Err &&
                    state().chats.isNotEmpty() &&
                    !result.telegramError.requiresReauth
                ) {
                    delay(400)
                    result = client.getChats()
                }
                when (result) {
                    is Outcome.Ok -> {
                        AppLog.api("chats", "network count=${result.value.size} merge=${hasMemory || cached.isNotEmpty()}")
                        lastNetworkPage = result.value
                        mainNetworkHasMore = dialogsHasMore(result.value.size)
                        failedOffsetPeers.clear()
                        val mapped = muteChats(result.value)
                        AppLog.api(
                            "chats",
                            "mute state overrides=${mapped.count { it.muteOverride }} " +
                                "muted=${mapped.count { it.muted }} of ${mapped.size}",
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
                                withContext(Dispatchers.IO) {
                                    withNetworkPinOrder(mergeChats(source, mapped), mapped)
                                }
                            } else {
                                withNetworkPinOrder(mergeChats(source, mapped), mapped)
                            }
                            persisted = merged
                            pruneRows = mapped.filter { net ->
                                val local = merged.firstOrNull { it.id == net.id }
                                local == null || net.lastMessageId >= local.lastMessageId
                            }
                            republishListedLocked(
                                merged,
                                fromCache = false,
                                hasMore = if (activeFolderId == ARCHIVE_FOLDER_ID) {
                                    null
                                } else {
                                    dialogsHasMore(result.value.size)
                                },
                                keep = paintKeep(),
                            )
                        }
                        val hidden = mapped.filter { !it.isShownInChatList() }
                        warmup?.upsertChats(if (hidden.isEmpty()) persisted else persisted + hidden)
                        sessionStore?.upsertPeersFromChats(persisted)
                        if (!isFolderScopedStream(activeFolderId) &&
                            cachedTail.any { it.isMainListRow() }
                        ) {
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
                                    "prune chat=${row.chatId} lastId=${row.lastMessageId} " +
                                        "cachedMax=${row.cachedMaxId} dropped=${row.droppedIds.size} " +
                                        "ids=${row.droppedIds.take(12).joinToString(",")}",
                                )
                            }
                        }
                    }
                    is Outcome.Err -> {
                        AppLog.warn("chats", result.telegramError.logLine())
                        if (state().chats.isEmpty()) {
                            dispatch(Msg.Error(result.telegramError))
                        }
                    }
                }
                if (state().loading) dispatch(Msg.Loading(false))
                if (state().syncing) dispatch(Msg.Syncing(false))
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Throwable) {
                if (state().loading) dispatch(Msg.Loading(false))
                if (state().syncing) dispatch(Msg.Syncing(false))
            } finally {
                refreshInFlight.set(false)
            }
        }
    }

    private fun loadMore() {
        // Folder-scoped streams must own their first request; main-list cache rows cannot
        // satisfy an archive/custom-folder selection.
        if (isFolderScopedStream(activeFolderId)) {
            if (state().loadingMore) return
            if (activeFolderId == ARCHIVE_FOLDER_ID) {
                val tailArchived = cachedTail.filter { it.isArchiveListRow() }
                if (tailArchived.isNotEmpty()) {
                    dispatch(Msg.LoadingMore(true))
                    val next = tailArchived.take(DIALOGS_NETWORK_PAGE)
                    cachedTail = cachedTail.filter { chat -> next.none { it.id == chat.id } }
                    dispatch(Msg.Append(next))
                    dispatch(
                        Msg.HasMore(
                            cachedTail.any { it.isArchiveListRow() } ||
                                (folderPaging[ARCHIVE_FOLDER_WIRE_ID]?.hasMore ?: true),
                        ),
                    )
                    scope.launch {
                        delay(100)
                        finishCachedPaging()
                    }
                    return
                }
                if (warmup != null) {
                    dispatch(Msg.LoadingMore(true))
                    scope.launch {
                        try {
                            val loadedIds = state().chats.map { it.id.value } +
                                cachedTail.map { it.id.value }
                            val roomPage = warmup.chatsArchiveExcluding(
                                loadedIds,
                                DIALOGS_NETWORK_PAGE,
                            ).filter { it.isArchiveListRow() }
                            AppLog.api("chats", "loadMore archive room count=${roomPage.size}")
                            if (roomPage.isNotEmpty()) {
                                dispatch(Msg.Append(roomPage))
                                dispatch(
                                    Msg.HasMore(
                                        folderPaging[ARCHIVE_FOLDER_WIRE_ID]?.hasMore ?: true,
                                    ),
                                )
                                delay(100)
                                return@launch
                            }
                        } finally {
                            finishCachedPaging()
                        }
                    }
                    return
                }
            }
            loadMoreFromNetwork()
            return
        }
        val tailMain = cachedTail.filter { it.isMainListRow() }
        if (tailMain.isNotEmpty()) {
            if (state().loadingMore) return
            dispatch(Msg.LoadingMore(true))
            val next = tailMain.take(DIALOGS_NETWORK_PAGE)
            cachedTail = cachedTail.filter { chat -> next.none { it.id == chat.id } }
            dispatch(Msg.Append(next))
            val loadedMain = state().chats.count { it.isMainListRow() }
            dispatch(
                Msg.HasMore(
                    cachedTail.any { it.isMainListRow() } || loadedMain < roomMainCount,
                ),
            )
            scope.launch {
                delay(100)
                finishCachedPaging()
            }
            return
        }
        if (state().loadingMore) return
        if (!isFolderScopedStream(activeFolderId) && warmup != null) {
            dispatch(Msg.LoadingMore(true))
            scope.launch {
                try {
                    val loadedIds = state().chats.map { it.id.value } +
                        cachedTail.map { it.id.value }
                    val roomPage = warmup.chatsExcluding(loadedIds, DIALOGS_NETWORK_PAGE)
                        .filter { it.isMainListRow() }
                    AppLog.api("chats", "loadMore room count=${roomPage.size}")
                    if (roomPage.isNotEmpty()) {
                        dispatch(Msg.Append(roomPage))
                        val loaded = state().chats.count { it.isMainListRow() }
                        dispatch(Msg.HasMore(loaded < roomMainCount || state().hasMore))
                        delay(100)
                        return@launch
                    }
                } finally {
                    finishCachedPaging()
                }
                if (!state().loadingMore) loadMoreFromNetwork()
            }
            return
        }
        loadMoreFromNetwork()
    }

    private fun loadMoreFromNetwork() {
        if (state().loadingMore) return
        val requestFolderId = activeFolderId
        val requestGeneration = folderSelectionGeneration
        val fallbackArchiveFolderId = fallbackArchiveFolderId(requestFolderId)
        if (isFolderScopedStream(requestFolderId)) {
            if (folderPaging[wireFolderId(requestFolderId)]?.hasMore == false) {
                dispatch(Msg.HasMore(false))
                return
            }
        } else if (fallbackArchiveFolderId == null && !mainNetworkHasMore) {
            dispatch(Msg.HasMore(false))
            return
        } else if (fallbackArchiveFolderId != null &&
            !mainNetworkHasMore && folderArchivePaging[fallbackArchiveFolderId]?.hasMore == false
        ) {
            dispatch(Msg.HasMore(false))
            return
        }
        if (state().error?.requiresReauth == true) return
        if (fallbackArchiveFolderId == null && !isFolderScopedStream(requestFolderId) &&
            dialogsPageCursor(lastNetworkPage, failedOffsetPeers) == null
        ) {
            dispatch(Msg.HasMore(false))
            AppLog.api("chats", "loadMore end")
            return
        }
        if (!state().loadingMore) dispatch(Msg.LoadingMore(true))
        scope.launch {
            var paged = false
            var fetched = 0
            run {
                repeat(5) {
                    if (requestGeneration != folderSelectionGeneration || requestFolderId != activeFolderId) {
                        paged = true
                        return@run
                    }
                    val wire = wireFolderId(requestFolderId)
                    val archiveFallback = fallbackArchiveFolderId(requestFolderId)
                    val useArchiveFallback = archiveFallback != null &&
                        folderArchivePaging[archiveFallback]?.hasMore != false &&
                        (!mainNetworkHasMore || fetched % 2 == 0)
                    val folderScoped = isFolderScopedStream(requestFolderId)
                    val pageBudget = pagesPerRequest(requestFolderId)
                    val stateful = when {
                        useArchiveFallback -> folderArchivePaging.getOrPut(archiveFallback) { FolderPaging() }
                        folderScoped -> folderPaging.getOrPut(wire) { FolderPaging() }
                        else -> null
                    }
                    val cursor = when {
                        stateful != null && !stateful.started -> Triple(0, 0, 0L)
                        stateful != null -> dialogsPageCursor(
                            stateful.page,
                            failedOffsetPeers,
                            skipArchived = false,
                        )
                        archiveFallback != null && !mainNetworkHasMore -> null
                        else -> dialogsPageCursor(lastNetworkPage, failedOffsetPeers)
                    }
                    if (cursor == null) {
                        if (stateful != null) {
                            stateful.hasMore = false
                        } else {
                            mainNetworkHasMore = false
                        }
                        val otherStreamHasMore = archiveFallback != null &&
                            if (useArchiveFallback) mainNetworkHasMore
                            else folderArchivePaging[archiveFallback]?.hasMore == true
                        if (otherStreamHasMore) {
                            // One stream ended, but the other still has pages. Continue
                            // the same request rather than declaring the folder exhausted.
                            return@repeat
                        }
                        if (archiveFallback != null) {
                            dispatch(
                                Msg.HasMore(
                                    mainNetworkHasMore ||
                                        (folderArchivePaging[archiveFallback]?.hasMore == true),
                                ),
                            )
                        } else if (!folderScoped) {
                            dispatch(Msg.HasMore(false))
                        }
                        AppLog.api(
                            "chats",
                            "loadMore end folder=${if (useArchiveFallback) ARCHIVE_FOLDER_WIRE_ID else wire}",
                        )
                        paged = true
                        return@run
                    }
                    val (date, offsetId, peerId) = cursor
                    val requestFolder = if (useArchiveFallback) ARCHIVE_FOLDER_WIRE_ID else wire
                    AppLog.api(
                        "chats",
                        "loadMore folder=$requestFolder pages=$fetched/$pageBudget " +
                            "offsetDate=$date offsetId=$offsetId peer=$peerId",
                    )
                    val result = if (useArchiveFallback || folderScoped) {
                        client.loadMoreFolderChats(requestFolder, date, offsetId, peerId)
                    } else {
                        client.loadMoreChats(date, offsetId, peerId)
                    }
                    if (requestGeneration != folderSelectionGeneration || requestFolderId != activeFolderId) {
                        paged = true
                        return@run
                    }
                    when (result) {
                        is Outcome.Ok -> {
                            val page = result.value
                            if (stateful != null) {
                                stateful.page = page
                                stateful.started = true
                                stateful.hasMore = dialogsHasMore(page.size)
                                dispatch(
                                    Msg.HasMore(
                                        if (archiveFallback != null) {
                                            mainNetworkHasMore || stateful.hasMore
                                        } else {
                                            stateful.hasMore
                                        },
                                    ),
                                )
                            } else {
                                lastNetworkPage = page
                                mainNetworkHasMore = dialogsHasMore(page.size)
                                dispatch(
                                    Msg.HasMore(
                                        if (archiveFallback != null) {
                                            mainNetworkHasMore ||
                                                (folderArchivePaging[archiveFallback]?.hasMore == true)
                                        } else {
                                            mainNetworkHasMore
                                        },
                                    ),
                                )
                            }
                            AppLog.api("chats", "loadMore network count=${page.size}")
                            val mapped = withEffectiveMutes(
                                page,
                                notifyDefaults,
                                notifyDefaultsLoaded,
                            )
                            dispatch(Msg.Append(mapped))
                            if (mapped.isNotEmpty()) {
                                warmup?.upsertChats(mapped)
                                sessionStore?.upsertPeersFromChats(mapped)
                            }
                            fetched += 1
                            paged = true
                            // A custom folder filters this stream locally, so one page is not
                            // enough: keep going until the budget is spent or the stream ends.
                            if (fetched >= pageBudget) return@run
                        }
                        is Outcome.Err -> {
                            val error = result.telegramError
                            AppLog.warn("chats", error.logLine())
                            when {
                                folderScoped && error.type == FOLDER_ID_INVALID -> {
                                    // The server keeps no dialog stream for this filter id:
                                    // remember it and fill both main and archive streams locally.
                                    AppLog.api(
                                        "chats",
                                        "folder stream rejected folder=$wire, falling back to main+archive",
                                    )
                                    folderStreamRejected += wire
                                    folderPaging.remove(wire)
                                    folderArchivePaging.getOrPut(wire) { FolderPaging() }
                                }
                                useArchiveFallback && error.type == FOLDER_ID_INVALID -> {
                                    stateful?.hasMore = false
                                }
                                error.kind == TelegramError.Kind.Peer -> failedOffsetPeers += peerId
                                else -> {
                                    dispatch(Msg.Error(error))
                                    if (stateful != null) {
                                        stateful.hasMore = false
                                    } else {
                                        mainNetworkHasMore = false
                                    }
                                    if (archiveFallback != null) {
                                        dispatch(
                                            Msg.HasMore(
                                                mainNetworkHasMore ||
                                                    (folderArchivePaging[archiveFallback]?.hasMore == true),
                                            ),
                                        )
                                    } else {
                                        dispatch(Msg.HasMore(false))
                                    }
                                    paged = true
                                    return@run
                                }
                            }
                        }
                    }
                }
            }
            if (!paged) {
                val stillCurrent = requestGeneration == folderSelectionGeneration &&
                    requestFolderId == activeFolderId
                val archiveFallback = fallbackArchiveFolderId(requestFolderId)
                if (!stillCurrent) {
                    // A replacement folder owns the next loading transition.
                } else if (archiveFallback != null) {
                    mainNetworkHasMore = false
                    folderArchivePaging[archiveFallback]?.hasMore = false
                    dispatch(Msg.HasMore(false))
                } else if (isFolderScopedStream(requestFolderId)) {
                    folderPaging.getOrPut(wireFolderId(requestFolderId)) { FolderPaging() }.hasMore = false
                    dispatch(Msg.HasMore(false))
                } else {
                    mainNetworkHasMore = false
                    dispatch(Msg.HasMore(false))
                }
            }
            finishCachedPaging()
        }
    }

    private fun finishCachedPaging() {
        if (state().loadingMore) dispatch(Msg.LoadingMore(false))
        val pendingFolder = pendingFolderInitialLoad
        pendingFolderInitialLoad = null
        if (pendingFolder != null && pendingFolder == activeFolderId &&
            isFolderScopedStream(pendingFolder) &&
            folderPaging[wireFolderId(pendingFolder)]?.started != true
        ) {
            dispatch(Msg.HasMore(true))
            loadMore()
            return
        }
        if (activeFolderId == ARCHIVE_FOLDER_ID &&
            folderPaging[ARCHIVE_FOLDER_WIRE_ID]?.started != true &&
            !state().loadingMore
        ) {
            loadMoreFromNetwork()
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
                ?: chatMap[userId.value]?.name
                ?: ""
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
            activeFolderId == ARCHIVE_FOLDER_ID -> known.coerceAtLeast(ARCHIVE_PAINT_LIMIT)
            activeFolderId != null && activeFolderId != MAIN_FOLDER_WIRE_ID ->
                known.coerceAtLeast(ARCHIVE_PAINT_LIMIT)
            else -> ARCHIVE_PAINT_LIMIT
        }
    }

    private suspend fun republishListed(
        merged: List<Chat>,
        fromCache: Boolean,
        hasMore: Boolean? = null,
        keep: Int = paintKeep(),
    ) = listPublicationMutex.withLock {
        republishListedLocked(merged, fromCache, hasMore, keep)
    }

    private suspend fun republishListedLocked(
        merged: List<Chat>,
        fromCache: Boolean,
        hasMore: Boolean?,
        keep: Int,
    ) {
        val archiveLimit = archiveKeep(merged)
        val (first, tail) = if (merged.size > 64) {
            withContext(Dispatchers.IO) { paintDialogsWindow(merged, keep, archiveLimit) }
        } else {
            paintDialogsWindow(merged, keep, archiveLimit)
        }
        cachedTail = tail
        val painted = state().chats
        val sameWindow = painted == first
        if (!sameWindow || state().fromCache != fromCache) {
            dispatch(Msg.Chats(first, fromCache = fromCache, replace = true))
        }
        val nextHasMore = publishedHasMore(first, tail, hasMore)
        if (state().hasMore != nextHasMore) {
            dispatch(Msg.HasMore(nextHasMore))
        }
        recoverMissingTitles()
    }

    private fun publishedHasMore(
        first: List<Chat>,
        tail: List<Chat>,
        hasMoreOverride: Boolean?,
    ): Boolean {
        if (activeFolderId == ARCHIVE_FOLDER_ID) {
            val archiveHasMore = hasMoreOverride
                ?: (folderPaging[ARCHIVE_FOLDER_WIRE_ID]?.hasMore ?: true)
            return tail.any { it.isArchiveListRow() } || archiveHasMore
        }
        val loadedMain = first.count { it.isMainListRow() } + tail.count { it.isMainListRow() }
        // An incoming update does not carry pagination metadata. Preserve the active
        // network stream's state instead of turning an unfinished stream off.
        val streamHasMore = hasMoreOverride ?: state().hasMore
        return tail.any { it.isMainListRow() } ||
            streamHasMore ||
            loadedMain < roomMainCount
    }

    private fun recoverMissingTitles() {
        if (titleRecoveryJob?.isActive == true) return
        titleRecoveryJob = scope.launch {
            // Resolve sequentially, once per refresh, so missing peers cannot flood the RPC queue.
            while (true) {
                val missing = state().chats.firstOrNull {
                    it.id !in attemptedTitleRecovery && isPlaceholderPeerTitle(it.title, it.id.value)
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
                    if (!isPlaceholderPeerTitle(current.title, current.id.value)) return@withLock null
                    val next = current.copy(
                        title = profile.title,
                        isChannel = profile.kind == "channel",
                        isGroup = profile.kind == "group" || profile.kind == "chat",
                        photoCacheKey = profile.avatarCacheKey ?: current.photoCacheKey,
                        isVerified = profile.isVerified,
                    )
                    dispatch(Msg.Chats(listOf(next), fromCache = false, replace = false))
                    next
                } ?: continue
                warmup?.upsertChats(listOf(updated))
            }
        }
    }

    private fun absorbIncoming(message: Message) {
        scope.launch {
            var persistHidden: Chat? = null
            val published = listPublicationMutex.withLock {
                val current = listedChat(message.id.chatId)
                when {
                    current == null -> false
                    !current.isShownInChatList() -> {
                        persistHidden = current.withIncomingMessage(message)
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
                            applyIncomingMessage(listed, message),
                            fromCache = false,
                            hasMore = null,
                            keep = paintKeep(),
                        )
                        true
                    }
                }
            }
            persistHidden?.let { warmup?.upsertChats(listOf(it)) }
            if (published) return@launch
            val stored = withContext(Dispatchers.IO) {
                warmup?.chat(message.id.chatId)
            } ?: return@launch
            if (!stored.isMainListRow()) {
                warmup?.upsertChats(listOf(stored.withIncomingMessage(message)))
                return@launch
            }
            listPublicationMutex.withLock {
                republishListedLocked(
                    applyIncomingMessage(allListed() + stored, message),
                    fromCache = false,
                    hasMore = null,
                    keep = paintKeep(),
                )
            }
        }
    }

    private suspend fun muteChats(chats: List<Chat>): List<Chat> {
        val apply = {
            withEffectiveMutes(chats, notifyDefaults, notifyDefaultsLoaded)
        }
        return if (chats.size > 64) {
            withContext(Dispatchers.IO) { apply() }
        } else {
            apply()
        }
    }

    private fun startSearch(query: String, immediate: Boolean) {
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
        if (query.isEmpty() ||
            query != lastRunSearchQuery ||
            state().searchLoading ||
            state().searchLoadingMore ||
            state().searchError != null ||
            !state().searchHasMore
        ) {
            return
        }
        val gen = searchGen
        searchMoreJob?.cancel()
        searchMoreJob = scope.launch {
            runSearch(query, gen, reset = false)
        }
    }

    private suspend fun runSearch(query: String, gen: Int, reset: Boolean) {
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
            is Outcome.Err -> contactsErr = contacts.telegramError
        }
        when (global) {
            is Outcome.Ok -> {
                val page = global.value
                searchNextRate = page.nextRate
                searchNextPeerId = page.nextPeerId.value
                searchNextOffsetId = page.nextOffsetId
                val existing = if (reset) emptySet() else {
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
