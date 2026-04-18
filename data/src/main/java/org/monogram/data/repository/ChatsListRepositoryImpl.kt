package org.monogram.data.repository

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import org.monogram.core.DispatcherProvider
import org.monogram.data.chats.ChatCache
import org.monogram.data.chats.ChatFileManager
import org.monogram.data.chats.ChatFolderManager
import org.monogram.data.chats.ChatListManager
import org.monogram.data.chats.ChatModelFactory
import org.monogram.data.chats.ChatPersistenceManager
import org.monogram.data.chats.ChatSearchManager
import org.monogram.data.chats.ChatTypingManager
import org.monogram.data.chats.ChatUpdateHandler
import org.monogram.data.chats.ForumTopicsManager
import org.monogram.data.core.coRunCatching
import org.monogram.data.datasource.cache.ChatLocalDataSource
import org.monogram.data.datasource.remote.ChatRemoteSource
import org.monogram.data.datasource.remote.ChatsRemoteDataSource
import org.monogram.data.db.dao.ChatFolderDao
import org.monogram.data.db.dao.SearchHistoryDao
import org.monogram.data.db.dao.UserFullInfoDao
import org.monogram.data.gateway.TdLibException
import org.monogram.data.gateway.TelegramGateway
import org.monogram.data.gateway.UpdateDispatcher
import org.monogram.data.infra.ConnectionManager
import org.monogram.data.infra.FileDownloadQueue
import org.monogram.data.infra.FileUpdateHandler
import org.monogram.data.infra.SynchronizedLruMap
import org.monogram.data.mapper.ChatMapper
import org.monogram.data.mapper.MessageMapper
import org.monogram.domain.models.ChatModel
import org.monogram.domain.models.ChatPermissionsModel
import org.monogram.domain.models.FolderModel
import org.monogram.domain.models.TopicModel
import org.monogram.domain.repository.AppPreferencesProvider
import org.monogram.domain.repository.CacheProvider
import org.monogram.domain.repository.ChatCreationRepository
import org.monogram.domain.repository.ChatFolderRepository
import org.monogram.domain.repository.ChatListRepository
import org.monogram.domain.repository.ChatOperationsRepository
import org.monogram.domain.repository.ChatSearchRepository
import org.monogram.domain.repository.ChatSettingsRepository
import org.monogram.domain.repository.ConnectionStatus
import org.monogram.domain.repository.FolderChatsUpdate
import org.monogram.domain.repository.FolderLoadingUpdate
import org.monogram.domain.repository.ForumTopicsRepository
import org.monogram.domain.repository.SearchMessagesResult
import org.monogram.domain.repository.StringProvider
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.system.measureTimeMillis

class ChatsListRepositoryImpl(
    private val remoteDataSource: ChatsRemoteDataSource,
    private val chatRemoteSource: ChatRemoteSource,
    private val updates: UpdateDispatcher,
    private val appPreferences: AppPreferencesProvider,
    private val cacheProvider: CacheProvider,
    private val dispatchers: DispatcherProvider,
    private val cache: ChatCache,
    private val chatMapper: ChatMapper,
    private val messageMapper: MessageMapper,
    private val gateway: TelegramGateway,
    private val scope: CoroutineScope,
    private val chatLocalDataSource: ChatLocalDataSource,
    private val connectionManager: ConnectionManager,
    private val databaseFile: File,
    private val searchHistoryDao: SearchHistoryDao,
    private val chatFolderDao: ChatFolderDao,
    private val userFullInfoDao: UserFullInfoDao,
    private val fileQueue: FileDownloadQueue,
    private val fileUpdateHandler: FileUpdateHandler,
    private val stringProvider: StringProvider
) : ChatListRepository,
    ChatFolderRepository,
    ChatOperationsRepository,
    ChatSearchRepository,
    ForumTopicsRepository,
    ChatSettingsRepository,
    ChatCreationRepository {

    private val _chatListFlow = MutableStateFlow<List<ChatModel>>(emptyList())
    override val chatListFlow: StateFlow<List<ChatModel>> = _chatListFlow.asStateFlow()

    private val _folderChatsFlow = MutableSharedFlow<FolderChatsUpdate>(
        replay = 1,
        extraBufferCapacity = 10,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val folderChatsFlow: Flow<FolderChatsUpdate> = _folderChatsFlow.asSharedFlow()

    private val _foldersFlow = MutableStateFlow(listOf(FolderModel(-1, "")))
    override val foldersFlow: StateFlow<List<FolderModel>> = _foldersFlow.asStateFlow()

    private val _isLoadingFlow = MutableStateFlow(false)
    override val isLoadingFlow: StateFlow<Boolean> = _isLoadingFlow.asStateFlow()

    private val _folderLoadingFlow = MutableSharedFlow<FolderLoadingUpdate>(
        replay = 1,
        extraBufferCapacity = 10,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val folderLoadingFlow: Flow<FolderLoadingUpdate> = _folderLoadingFlow.asSharedFlow()

    override val connectionStateFlow = connectionManager.connectionStateFlow
    override val isArchivePinned = appPreferences.isArchivePinned
    override val isArchiveAlwaysVisible = appPreferences.isArchiveAlwaysVisible

    private val fileManager = ChatFileManager(
        gateway = gateway,
        dispatchers = dispatchers,
        scope = scope,
        fileQueue = fileQueue,
        fileUpdateHandler = fileUpdateHandler,
        onUpdate = {
            triggerUpdate()
            refreshActiveForumTopics()
        }
    )

    private val typingManager = ChatTypingManager(
        scope = scope,
        usersCache = cache.usersCache,
        allChats = cache.allChats,
        stringProvider = stringProvider,
        onUpdate = { chatId -> triggerUpdate(chatId) },
        onUserNeeded = { userId -> fetchUser(userId) }
    )

    private val listManager = ChatListManager(cache) { chatId ->
        if (cache.pendingChats.add(chatId)) {
            scope.launch {
                refreshChat(chatId)
                cache.pendingChats.remove(chatId)
            }
        }
    }

    private val modelFactory = ChatModelFactory(
        gateway = gateway,
        dispatchers = dispatchers,
        scope = scope,
        cache = cache,
        chatMapper = chatMapper,
        fileManager = fileManager,
        typingManager = typingManager,
        appPreferences = appPreferences,
        userFullInfoDao = userFullInfoDao,
        triggerUpdate = { chatId -> triggerUpdate(chatId) },
        fetchUser = { userId -> fetchUser(userId) }
    )

    private val persistenceManager = ChatPersistenceManager(
        scope = scope,
        dispatchers = dispatchers,
        cache = cache,
        chatLocalDataSource = chatLocalDataSource,
        chatMapper = chatMapper,
        modelFactory = modelFactory,
        listManager = listManager,
        activeChatListProvider = { activeChatList }
    )

    private val folderManager = ChatFolderManager(
        gateway = gateway,
        dispatchers = dispatchers,
        scope = scope,
        foldersFlow = _foldersFlow,
        cacheProvider = cacheProvider,
        chatFolderDao = chatFolderDao
    )

    private val forumTopicsManager = ForumTopicsManager(
        chatRemoteSource = chatRemoteSource,
        chatMapper = chatMapper,
        cache = cache,
        fileManager = fileManager,
        chatLocalDataSource = chatLocalDataSource,
        dispatchers = dispatchers,
        scope = scope,
        fetchUser = { userId -> fetchUser(userId) }
    )
    override val forumTopicsFlow: Flow<Pair<Long, List<TopicModel>>> = forumTopicsManager.forumTopicsFlow

    private val searchManager = ChatSearchManager(
        chatRemoteSource = chatRemoteSource,
        messageMapper = messageMapper,
        cacheProvider = cacheProvider,
        searchHistoryDao = searchHistoryDao,
        dispatchers = dispatchers,
        scope = scope,
        resolveChatById = { chatId -> getChatById(chatId) }
    )
    override val searchHistory: Flow<List<ChatModel>> = searchManager.searchHistory

    private var myUserId: Long = 0L

    @Volatile
    private var activeFolderId: Int = -1

    @Volatile
    private var activeChatList: TdApi.ChatList = TdApi.ChatListMain()

    @Volatile
    private var activeRequestId: Long = 0L

    private val requestIdGenerator = AtomicLong(0L)
    private val cacheHydrated = CompletableDeferred<Unit>()
    private val updateChannel = Channel<Unit>(Channel.CONFLATED)
    private var pendingSelectFolderJob: Job? = null

    private val maxChatListLimit = 10_000
    private val initialChatListLimit = 200
    private var currentLimit = initialChatListLimit

    @Volatile
    private var lastRecoveryAttemptAtMs = 0L

    @Volatile
    private var lastConnectionStatus: ConnectionStatus? = null

    private val modelCache = SynchronizedLruMap<Long, ChatModel>(MODEL_CACHE_SIZE)
    private val invalidatedModels = ConcurrentHashMap.newKeySet<Long>()
    @Volatile
    private var invalidateAllModels = true
    private var lastList: List<ChatModel>? = null
    private var lastListFolderId: Int = -1

    private val updateHandler = ChatUpdateHandler(
        cache = cache,
        listManager = listManager,
        typingManager = typingManager,
        fileManager = fileManager,
        folderManager = folderManager,
        chatLocalDataSource = chatLocalDataSource,
        dispatchers = dispatchers,
        scope = scope,
        activeChatListProvider = { activeChatList },
        myUserIdProvider = { myUserId },
        onSaveChat = { chatId -> persistenceManager.scheduleChatSave(chatId) },
        onSaveChatsBySupergroupId = { supergroupId -> persistenceManager.scheduleSavesBySupergroupId(supergroupId) },
        onSaveChatsByBasicGroupId = { basicGroupId -> persistenceManager.scheduleSavesByBasicGroupId(basicGroupId) },
        onTriggerUpdate = { chatId -> triggerUpdate(chatId) },
        onRefreshChat = { chatId -> refreshChat(chatId) },
        onRefreshForumTopics = { refreshActiveForumTopics() },
        onAuthorizationStateClosed = {
            clearTransientState()
            scope.launch(dispatchers.io) {
                chatLocalDataSource.clearAll()
            }
        }
    )

    init {
        scope.launch(dispatchers.io) {
            myUserId = chatRemoteSource.getMyUserId()
        }

        scope.launch(dispatchers.io) {
            hydrateCacheFromPersistence()
        }

        scope.launch(dispatchers.io) {
            for (unit in updateChannel) {
                rebuildAndEmit()
                delay(REBUILD_THROTTLE_MS)
            }
        }

        scope.launch {
            updates.chatsListUpdates.collect { update ->
                updateHandler.handle(update)
            }
        }

        scope.launch {
            updates.authorizationState.collect { update ->
                if (update.authorizationState is TdApi.AuthorizationStateReady) {
                    scheduleRecoveryIfNeeded("auth_ready", force = true)
                }
            }
        }

        scope.launch {
            connectionStateFlow
                .collect { status ->
                    val previous = lastConnectionStatus
                    lastConnectionStatus = status
                    if (status is ConnectionStatus.Connected && previous !is ConnectionStatus.Connected) {
                        scheduleRecoveryIfNeeded("connection_ready")
                    }
                }
        }

        scope.launch {
            updates.chatFolders.collect { update ->
                folderManager.handleChatFoldersUpdate(update)
                triggerUpdate()
            }
        }
    }

    private suspend fun rebuildAndEmit() {
        if (!cacheHydrated.isCompleted) {
            return
        }
        coRunCatching {
            val folderId = activeFolderId
            val limit = currentLimit.coerceAtMost(maxChatListLimit)
            val newList = rebuildChatModels(limit)

            if (folderId != activeFolderId) {
                return@coRunCatching
            }

            if (!shouldEmitList(folderId, newList)) {
                return@coRunCatching
            }

            if (newList.isEmpty() && cache.activeListPositions.isNotEmpty()) {
                Log.w(
                    TAG,
                    "Rebuild produced empty list with non-empty active positions: folder=$folderId list=${activeChatList.debugName()} positions=${cache.activeListPositions.size} chats=${cache.allChats.size} limit=$limit"
                )
            }

            emitListUpdate(folderId, newList)
            persistenceManager.persistChatModels(newList, activeChatList)
        }.onFailure { error ->
            Log.e(TAG, "Error rebuilding chat list", error)
        }
    }

    private fun rebuildChatModels(limit: Int): List<ChatModel> {
        if (!invalidateAllModels) {
            rebuildVisibleModels(limit)?.let { return it }
        }

        val rebuilt = listManager.rebuildChatList(limit, emptyList()) { chat, order, isPinned ->
            val cached = modelCache[chat.id]
            if (cached != null &&
                cached.order == order &&
                cached.isPinned == isPinned &&
                !invalidatedModels.contains(chat.id)
            ) {
                cached
            } else {
                modelFactory.mapChatToModel(chat, order, isPinned).also { mapped ->
                    modelCache[chat.id] = mapped
                    invalidatedModels.remove(chat.id)
                }
            }
        }
        invalidatedModels.clear()
        invalidateAllModels = false
        return rebuilt
    }

    private fun rebuildVisibleModels(limit: Int): List<ChatModel>? {
        val previous = lastList ?: return null
        if (lastListFolderId != activeFolderId) return null
        if (invalidatedModels.isEmpty()) return previous

        val visibleIndexes = previous.mapIndexed { index, chat -> chat.id to index }.toMap()
        val updated = previous.toMutableList()

        for (chatId in invalidatedModels.toList()) {
            val index = visibleIndexes[chatId] ?: return null
            val chat = cache.allChats[chatId] ?: return null
            val position = cache.activeListPositions[chatId] ?: return null
            val oldModel = previous[index]
            if (oldModel.order != position.order || oldModel.isPinned != position.isPinned) {
                return null
            }
            updated[index] = modelFactory.mapChatToModel(chat, position.order, position.isPinned).also { mapped ->
                modelCache[chatId] = mapped
            }
            invalidatedModels.remove(chatId)
        }

        if (updated.size > limit) {
            return null
        }
        return updated
    }

    private fun shouldEmitList(folderId: Int, newList: List<ChatModel>): Boolean {
        return folderId != lastListFolderId || newList != lastList
    }

    private fun emitListUpdate(folderId: Int, newList: List<ChatModel>) {
        _chatListFlow.value = newList
        _folderChatsFlow.tryEmit(FolderChatsUpdate(folderId, newList))
        lastList = newList
        lastListFolderId = folderId
    }

    private fun clearTransientState() {
        modelCache.clear()
        invalidatedModels.clear()
        invalidateAllModels = true
        lastList = null
        lastListFolderId = -1
        _chatListFlow.value = emptyList()
        persistenceManager.clear()
    }

    private fun triggerUpdate(chatId: Long? = null) {
        if (chatId == null) {
            invalidateAllModels = true
            invalidatedModels.addAll(cache.activeListPositions.keys)
        } else {
            invalidatedModels.add(chatId)
        }
        updateChannel.trySend(Unit)
    }

    private fun isRequestActive(folderId: Int, requestId: Long): Boolean {
        return activeFolderId == folderId && activeRequestId == requestId
    }

    private fun setLoadingState(folderId: Int, requestId: Long, isLoading: Boolean) {
        if (!isRequestActive(folderId, requestId)) return
        _isLoadingFlow.value = isLoading
        _folderLoadingFlow.tryEmit(FolderLoadingUpdate(folderId, isLoading))
    }

    private fun refreshActiveForumTopics() {
        forumTopicsManager.refreshActiveForumTopics()
    }

    override fun retryConnection() {
        connectionManager.retryConnection()
    }

    override fun selectFolder(folderId: Int) {
        if (!cacheHydrated.isCompleted) {
            pendingSelectFolderJob?.cancel()
            pendingSelectFolderJob = scope.launch(dispatchers.io) {
                coRunCatching { cacheHydrated.await() }
                    .onSuccess { selectFolder(folderId) }
            }
            return
        }

        pendingSelectFolderJob = null
        val newList: TdApi.ChatList = when (folderId) {
            -1 -> TdApi.ChatListMain()
            -2 -> TdApi.ChatListArchive()
            else -> TdApi.ChatListFolder(folderId)
        }
        if (folderId == activeFolderId &&
            listManager.isSameChatList(newList, activeChatList) &&
            activeRequestId != 0L
        ) {
            return
        }

        activeFolderId = folderId
        activeChatList = newList
        updateActiveListPositionsFromCache()

        val initialLoadLimit = initialChatListLimit.coerceAtMost(maxChatListLimit)
        currentLimit = initialLoadLimit

        val requestId = requestIdGenerator.incrementAndGet()
        activeRequestId = requestId
        setLoadingState(folderId, requestId, true)
        Log.i(
            TAG,
            "Select folder start: folder=$folderId requestId=$requestId list=${newList.debugName()} limit=$initialLoadLimit cachedChats=${cache.allChats.size} activePositions=${cache.activeListPositions.size}"
        )
        triggerUpdate()

        scope.launch(dispatchers.io) {
            val durationMs = measureTimeMillis {
                val result = loadAndStabilizeChats(
                    source = "selectFolder",
                    folderId = folderId,
                    requestId = requestId,
                    chatList = newList,
                    limit = initialLoadLimit
                )
                logLoadChatsResult(
                    source = "selectFolder",
                    folderId = folderId,
                    requestId = requestId,
                    chatList = newList,
                    limit = initialLoadLimit,
                    result = result
                )
            }
            setLoadingState(folderId, requestId, false)
            if (isRequestActive(folderId, requestId)) {
                Log.d(
                    TAG,
                    "Select folder complete: folder=$folderId requestId=$requestId list=${newList.debugName()} durationMs=$durationMs rendered=${_chatListFlow.value.size} activePositions=${cache.activeListPositions.size}"
                )
                triggerUpdate()
                if (_chatListFlow.value.isEmpty()) {
                    scheduleRecoveryIfNeeded("selectFolder_empty:$folderId", force = true)
                }
            }
        }
    }

    private fun updateActiveListPositionsFromCache() {
        val currentActivePositions = HashMap<Long, TdApi.ChatPosition>()
        cache.activeListPositions.forEach { (chatId, position) ->
            if (position.order != 0L && listManager.isSameChatList(position.list, activeChatList)) {
                currentActivePositions[chatId] = position
            }
        }

        val savedAuthoritative = HashMap<Long, TdApi.ChatPosition>()
        cache.authoritativeActiveListChatIds.forEach { chatId ->
            val currentPos = cache.activeListPositions[chatId] ?: return@forEach
            if (currentPos.order != 0L && listManager.isSameChatList(currentPos.list, activeChatList)) {
                savedAuthoritative[chatId] = currentPos
            }
        }

        cache.activeListPositions.clear()
        cache.authoritativeActiveListChatIds.clear()
        cache.protectedPinnedChatIds.clear()
        cache.allChats.values.forEach { chat ->
            chat.positions.find { listManager.isSameChatList(it.list, activeChatList) }?.let { position ->
                if (position.order != 0L) {
                    cache.activeListPositions[chat.id] = position
                    if (position.isPinned) {
                        cache.protectedPinnedChatIds.add(chat.id)
                    }
                }
            }
        }

        savedAuthoritative.forEach { (chatId, position) ->
            cache.activeListPositions.putIfAbsent(chatId, position)
            cache.authoritativeActiveListChatIds.add(chatId)
            if (position.isPinned) {
                cache.protectedPinnedChatIds.add(chatId)
            }
        }

        currentActivePositions.forEach { (chatId, position) ->
            val rebuilt = cache.activeListPositions[chatId]
            val shouldRestore =
                rebuilt == null ||
                        rebuilt.order == 0L ||
                        (position.isPinned && !rebuilt.isPinned) ||
                        position.order > rebuilt.order

            if (shouldRestore) {
                cache.activeListPositions[chatId] = position
                if (position.isPinned) {
                    cache.protectedPinnedChatIds.add(chatId)
                }
            }
        }

        Log.d(
            TAG,
            "Active list positions refreshed from cache: list=${activeChatList.debugName()} positions=${cache.activeListPositions.size} chats=${cache.allChats.size} authoritative=${cache.authoritativeActiveListChatIds.size} restoredCurrent=${currentActivePositions.size}"
        )
    }

    override fun refresh() {
        if (!cacheHydrated.isCompleted) {
            scope.launch(dispatchers.io) {
                coRunCatching { cacheHydrated.await() }
                    .onSuccess { refresh() }
            }
            return
        }

        retryConnection()
        updateActiveListPositionsFromCache()
        triggerUpdate()

        val folderId = activeFolderId
        val requestId = activeRequestId
        val chatList = activeChatList
        scope.launch(dispatchers.io) {
            val limit = currentLimit.coerceAtLeast(initialChatListLimit).coerceAtMost(maxChatListLimit)
            val durationMs = measureTimeMillis {
                val result = loadAndStabilizeChats(
                    source = "refresh",
                    folderId = folderId,
                    requestId = requestId,
                    chatList = chatList,
                    limit = limit
                )
                logLoadChatsResult(
                    source = "refresh",
                    folderId = folderId,
                    requestId = requestId,
                    chatList = chatList,
                    limit = limit,
                    result = result
                )
            }
            if (isRequestActive(folderId, requestId)) {
                Log.d(
                    TAG,
                    "Refresh complete: folder=$folderId requestId=$requestId list=${chatList.debugName()} durationMs=$durationMs rendered=${_chatListFlow.value.size} activePositions=${cache.activeListPositions.size}"
                )
                triggerUpdate()
            }
        }
    }

    override fun loadNextChunk(limit: Int) {
        if (!cacheHydrated.isCompleted) return
        if (_isLoadingFlow.value || currentLimit >= maxChatListLimit) return

        val folderId = activeFolderId
        val requestId = activeRequestId
        val chatList = activeChatList
        currentLimit = (currentLimit + limit).coerceAtMost(maxChatListLimit)
        val requestedLimit =
            currentLimit.coerceAtLeast(initialChatListLimit).coerceAtMost(maxChatListLimit)
        setLoadingState(folderId, requestId, true)

        scope.launch(dispatchers.io) {
            if (!isRequestActive(folderId, requestId)) {
                return@launch
            }

            val currentCount = _chatListFlow.value.size
            if (currentCount < currentLimit) {
                val totalAvailable = cache.activeListPositions.size
                if (totalAvailable > currentCount) {
                    triggerUpdate()
                }
            }

            val durationMs = measureTimeMillis {
                val result = loadAndStabilizeChats(
                    source = "loadNextChunk",
                    folderId = folderId,
                    requestId = requestId,
                    chatList = chatList,
                    limit = requestedLimit
                )
                logLoadChatsResult(
                    source = "loadNextChunk",
                    folderId = folderId,
                    requestId = requestId,
                    chatList = chatList,
                    limit = requestedLimit,
                    result = result
                )
            }
            setLoadingState(folderId, requestId, false)
            if (isRequestActive(folderId, requestId)) {
                Log.d(
                    TAG,
                    "Load next chunk complete: folder=$folderId requestId=$requestId list=${chatList.debugName()} durationMs=$durationMs rendered=${_chatListFlow.value.size} activePositions=${cache.activeListPositions.size} targetLimit=$requestedLimit"
                )
                triggerUpdate()
            }
        }
    }

    override suspend fun getChatById(chatId: Long): ChatModel? {
        val chatObj = cache.getChat(chatId)
            ?: chatLocalDataSource.getChat(chatId)?.let { entity ->
                cache.putChatFromEntity(entity)
                persistenceManager.rememberSavedEntity(entity)
                cache.getChat(chatId)
            }
            ?: remoteDataSource.getChat(chatId)?.also { chat ->
                val existingPositions = cache.getChat(chat.id)?.positions ?: emptyArray()
                chat.positions = listManager.sanitizePositionsForActiveList(
                    chatId = chat.id,
                    currentPositions = existingPositions,
                    incomingPositions = chat.positions,
                    activeChatList = activeChatList,
                    source = "GetChatById"
                )
                cache.putChat(chat)
                listManager.updateActiveListPositions(chat.id, chat.positions, activeChatList)
                persistenceManager.scheduleChatSave(chat.id)
            }
            ?: return null

        val position = chatObj.positions.find { listManager.isSameChatList(it.list, activeChatList) }
        return coRunCatching {
            modelFactory.mapChatToModel(chatObj, position?.order ?: 0L, position?.isPinned ?: false)
        }.getOrNull()
    }

    override suspend fun searchChats(query: String): List<ChatModel> {
        return searchManager.searchChats(query)
    }

    override suspend fun searchPublicChats(query: String): List<ChatModel> {
        return searchManager.searchPublicChats(query)
    }

    override suspend fun searchMessages(query: String, offset: String, limit: Int): SearchMessagesResult {
        return searchManager.searchMessages(query, offset, limit)
    }

    override fun toggleMuteChats(chatIds: Set<Long>, mute: Boolean) {
        val muteFor = if (mute) Int.MAX_VALUE else 0
        chatIds.forEach { chatId ->
            scope.launch(dispatchers.io) {
                chatRemoteSource.muteChat(chatId, muteFor)
            }
        }
    }

    override fun toggleArchiveChats(chatIds: Set<Long>, archive: Boolean) {
        chatIds.forEach { chatId ->
            scope.launch(dispatchers.io) {
                chatRemoteSource.archiveChat(chatId, archive)
            }
        }
    }

    override fun togglePinChats(chatIds: Set<Long>, pin: Boolean, folderId: Int) {
        val chatList: TdApi.ChatList = when (folderId) {
            -1 -> TdApi.ChatListMain()
            -2 -> TdApi.ChatListArchive()
            else -> TdApi.ChatListFolder(folderId)
        }

        chatIds.forEach { chatId ->
            scope.launch(dispatchers.io) {
                chatRemoteSource.toggleChatIsPinned(chatList, chatId, pin)
            }
        }
    }

    override fun toggleReadChats(chatIds: Set<Long>, markAsUnread: Boolean) {
        chatIds.forEach { chatId ->
            scope.launch(dispatchers.io) {
                if (markAsUnread) {
                    chatRemoteSource.toggleChatIsMarkedAsUnread(chatId, true)
                } else {
                    chatRemoteSource.markChatAsRead(chatId)
                }
            }
        }
    }

    override fun deleteChats(chatIds: Set<Long>) {
        chatIds.forEach { chatId ->
            scope.launch(dispatchers.io) {
                chatRemoteSource.deleteChat(chatId)
            }
        }
    }

    override fun leaveChat(chatId: Long) {
        scope.launch(dispatchers.io) {
            chatRemoteSource.leaveChat(chatId)
        }
    }

    override fun setArchivePinned(pinned: Boolean) {
        appPreferences.setArchivePinned(pinned)
    }

    override suspend fun createFolder(title: String, iconName: String?, includedChatIds: List<Long>) {
        folderManager.createFolder(title, iconName, includedChatIds)
    }

    override suspend fun deleteFolder(folderId: Int) {
        chatRemoteSource.deleteFolder(folderId)
    }

    override suspend fun updateFolder(
        folderId: Int,
        title: String,
        iconName: String?,
        includedChatIds: List<Long>
    ) {
        folderManager.updateFolder(folderId, title, iconName, includedChatIds)
    }

    override suspend fun reorderFolders(folderIds: List<Int>) {
        folderManager.reorderFolders(folderIds)
    }

    override suspend fun getForumTopics(
        chatId: Long,
        query: String,
        offsetDate: Int,
        offsetMessageId: Long,
        offsetForumTopicId: Int,
        limit: Int
    ): List<TopicModel> {
        return forumTopicsManager.getForumTopics(
            chatId = chatId,
            query = query,
            offsetDate = offsetDate,
            offsetMessageId = offsetMessageId,
            offsetForumTopicId = offsetForumTopicId,
            limit = limit
        )
    }

    override fun clearChatHistory(chatId: Long, revoke: Boolean) {
        scope.launch(dispatchers.io) {
            chatRemoteSource.clearChatHistory(chatId, revoke)
        }
    }

    override suspend fun getChatLink(chatId: Long): String? {
        return chatRemoteSource.getChatLink(chatId)
    }

    override fun reportChat(chatId: Long, reason: String, messageIds: List<Long>) {
        scope.launch(dispatchers.io) {
            chatRemoteSource.reportChat(chatId, reason, messageIds)
        }
    }

    override fun addSearchChatId(chatId: Long) {
        searchManager.addSearchChatId(chatId)
    }

    override fun removeSearchChatId(chatId: Long) {
        searchManager.removeSearchChatId(chatId)
    }

    override fun clearSearchHistory() {
        searchManager.clearSearchHistory()
    }

    override suspend fun createGroup(title: String, userIds: List<Long>, messageAutoDeleteTime: Int): Long {
        return chatRemoteSource.createGroup(title, userIds, messageAutoDeleteTime)
    }

    override suspend fun createChannel(
        title: String,
        description: String,
        isMegagroup: Boolean,
        messageAutoDeleteTime: Int
    ): Long {
        return chatRemoteSource.createChannel(title, description, isMegagroup, messageAutoDeleteTime)
    }

    override suspend fun setChatPhoto(chatId: Long, photoPath: String) {
        chatRemoteSource.setChatPhoto(chatId, photoPath)
    }

    override suspend fun setChatTitle(chatId: Long, title: String) {
        chatRemoteSource.setChatTitle(chatId, title)
    }

    override suspend fun setChatDescription(chatId: Long, description: String) {
        chatRemoteSource.setChatDescription(chatId, description)
    }

    override suspend fun setChatUsername(chatId: Long, username: String) {
        chatRemoteSource.setChatUsername(chatId, username)
    }

    override suspend fun setChatPermissions(chatId: Long, permissions: ChatPermissionsModel) {
        chatRemoteSource.setChatPermissions(chatId, permissions)
    }

    override suspend fun setChatSlowModeDelay(chatId: Long, slowModeDelay: Int) {
        chatRemoteSource.setChatSlowModeDelay(chatId, slowModeDelay)
    }

    override suspend fun toggleChatIsForum(chatId: Long, isForum: Boolean) {
        chatRemoteSource.toggleChatIsForum(chatId, isForum)
    }

    override suspend fun toggleChatIsTranslatable(chatId: Long, isTranslatable: Boolean) {
        chatRemoteSource.toggleChatIsTranslatable(chatId, isTranslatable)
    }

    override fun getDatabaseSize(): Long {
        return if (databaseFile.exists()) databaseFile.length() else 0L
    }

    override fun clearDatabase() {
        scope.launch(dispatchers.io) {
            chatLocalDataSource.clearAll()
            clearTransientState()
            triggerUpdate()
        }
    }

    fun clearMemoryCaches() {
        modelCache.clear()
        invalidatedModels.clear()
    }

    fun memoryCacheSnapshot(): MemoryCacheSnapshot {
        return MemoryCacheSnapshot(
            modelCacheSize = modelCache.size(),
            invalidatedModelsSize = invalidatedModels.size
        )
    }

    data class MemoryCacheSnapshot(
        val modelCacheSize: Int,
        val invalidatedModelsSize: Int
    )

    private fun fetchUser(userId: Long) {
        if (userId == 0L) return
        if (cache.pendingUsers.add(userId)) {
            scope.launch(dispatchers.io) {
                try {
                    val user = chatRemoteSource.getUser(userId)
                    if (user != null) {
                        cache.putUser(user)
                        cache.userIdToChatId[user.id]?.let { privateChatId ->
                            triggerUpdate(privateChatId)
                        }
                    }
                } finally {
                    cache.pendingUsers.remove(userId)
                }
            }
        }
    }

    private suspend fun refreshChat(chatId: Long) {
        val chatObj = remoteDataSource.getChat(chatId) ?: return
        val existingPositions = cache.getChat(chatObj.id)?.positions ?: emptyArray()
        chatObj.positions = listManager.sanitizePositionsForActiveList(
            chatId = chatObj.id,
            currentPositions = existingPositions,
            incomingPositions = chatObj.positions,
            activeChatList = activeChatList,
            source = "RefreshChat"
        )
        cache.putChat(chatObj)
        listManager.updateActiveListPositions(chatObj.id, chatObj.positions, activeChatList)
        persistenceManager.scheduleChatSave(chatObj.id)
        triggerUpdate(chatObj.id)
    }

    private suspend fun hydrateCacheFromPersistence() {
        coRunCatching {
            val topEntities = chatLocalDataSource.getTopChats(INITIAL_CACHE_HYDRATION_LIMIT)
            if (topEntities.isNotEmpty()) {
                topEntities.forEach { entity ->
                    cache.putChatFromEntity(entity)
                    persistenceManager.rememberSavedEntity(entity)
                }
                updateActiveListPositionsFromCache()
                Log.i(
                    TAG,
                    "Cache top hydrated: entities=${topEntities.size} chats=${cache.allChats.size} activePositions=${cache.activeListPositions.size} activeFolder=$activeFolderId list=${activeChatList.debugName()}"
                )
                triggerUpdate()
            } else {
                Log.i(TAG, "Cache top hydrated: no persisted chats found")
            }
        }.onFailure { error ->
            Log.e(TAG, "Failed to hydrate top chat cache", error)
        }

        if (!cacheHydrated.isCompleted) {
            cacheHydrated.complete(Unit)
        }

        triggerUpdate()
        scheduleRecoveryIfNeeded("cache_top_hydrated")

        coRunCatching {
            val entities = chatLocalDataSource.getAllChats().first()
            if (entities.isNotEmpty()) {
                entities.forEach { entity ->
                    cache.putChatFromEntity(entity)
                    persistenceManager.rememberSavedEntity(entity)
                }
                updateActiveListPositionsFromCache()
                Log.i(
                    TAG,
                    "Cache hydrated: entities=${entities.size} chats=${cache.allChats.size} activePositions=${cache.activeListPositions.size} activeFolder=$activeFolderId list=${activeChatList.debugName()}"
                )
                triggerUpdate()
                scheduleRecoveryIfNeeded("cache_hydrated")
            } else {
                Log.i(TAG, "Cache hydrated: no persisted chats found")
            }
        }.onFailure { error ->
            Log.e(TAG, "Failed to hydrate chat cache", error)
        }
    }

    private suspend fun loadAndStabilizeChats(
        source: String,
        folderId: Int,
        requestId: Long,
        chatList: TdApi.ChatList,
        limit: Int
    ): Result<Unit> {
        var lastResult = chatRemoteSource.loadChats(chatList, limit)
        if (!lastResult.isSuccess && lastResult.exceptionOrNull()?.asTdErrorCode() != 404) {
            return lastResult
        }

        repeat(LOAD_CHATS_STABILIZATION_PASSES) { pass ->
            if (!isRequestActive(folderId, requestId)) {
                return lastResult
            }

            val chats = chatRemoteSource.getChats(chatList, limit)
            val loadedCount = chats?.chatIds?.size ?: 0
            val targetReached = loadedCount >= limit
            Log.d(
                TAG,
                "LoadChats snapshot: source=$source folder=$folderId requestId=$requestId list=${chatList.debugName()} pass=${pass + 1} requested=$limit loadedIds=$loadedCount activePositions=${cache.activeListPositions.size}"
            )

            if (loadedCount > 0) {
                backfillMissingChatsFromSnapshot(
                    source = source,
                    folderId = folderId,
                    requestId = requestId,
                    chatList = chatList,
                    chatIds = chats?.chatIds ?: longArrayOf()
                )
            }

            if (targetReached || lastResult.exceptionOrNull()?.asTdErrorCode() == 404) {
                return lastResult
            }

            lastResult = chatRemoteSource.loadChats(chatList, limit)
            if (!lastResult.isSuccess && lastResult.exceptionOrNull()?.asTdErrorCode() != 404) {
                return lastResult
            }
        }

        return lastResult
    }

    private suspend fun backfillMissingChatsFromSnapshot(
        source: String,
        folderId: Int,
        requestId: Long,
        chatList: TdApi.ChatList,
        chatIds: LongArray
    ) {
        if (chatIds.isEmpty()) return

        val missingIds = chatIds.filter { chatId ->
            val activePos = cache.activeListPositions[chatId]
            val cachedChat = cache.getChat(chatId)
            activePos == null || cachedChat == null || cachedChat.positions.none { pos ->
                pos.order != 0L && listManager.isSameChatList(pos.list, chatList)
            }
        }

        if (missingIds.isEmpty()) return

        Log.w(
            TAG,
            "Backfill missing chats: source=$source folder=$folderId requestId=$requestId list=${chatList.debugName()} missing=${missingIds.size} sample=${
                missingIds.take(
                    10
                )
            }"
        )

        var recovered = 0
        missingIds.forEach { chatId ->
            val cachedChat = cache.getChat(chatId)
            if (cachedChat != null && cachedChat.positions.any { pos ->
                    pos.order != 0L && listManager.isSameChatList(pos.list, chatList)
                }
            ) {
                if (isRequestActive(folderId, requestId)) {
                    listManager.updateActiveListPositions(chatId, cachedChat.positions, chatList)
                }
                recovered += 1
                return@forEach
            }

            val fetchedChat = remoteDataSource.getChat(chatId) ?: return@forEach
            val existingPositions = cache.getChat(fetchedChat.id)?.positions ?: emptyArray()
            fetchedChat.positions = listManager.sanitizePositionsForActiveList(
                chatId = fetchedChat.id,
                currentPositions = existingPositions,
                incomingPositions = fetchedChat.positions,
                activeChatList = chatList,
                source = "Backfill:$source"
            )
            cache.putChat(fetchedChat)
            persistenceManager.scheduleChatSave(fetchedChat.id)
            if (isRequestActive(folderId, requestId)) {
                listManager.updateActiveListPositions(
                    fetchedChat.id,
                    fetchedChat.positions,
                    chatList
                )
                recovered += 1
            }
        }

        if (recovered > 0 && isRequestActive(folderId, requestId)) {
            Log.i(
                TAG,
                "Backfill recovered chats: source=$source folder=$folderId requestId=$requestId list=${chatList.debugName()} recovered=$recovered activePositions=${cache.activeListPositions.size}"
            )
            triggerUpdate()
        }
    }

    private fun Throwable.asTdErrorCode(): Int? = (this as? TdLibException)?.error?.code

    private fun logLoadChatsResult(
        source: String,
        folderId: Int,
        requestId: Long,
        chatList: TdApi.ChatList,
        limit: Int,
        result: Result<Unit>
    ) {
        result.onSuccess {
            Log.i(
                TAG,
                "LoadChats success: source=$source folder=$folderId requestId=$requestId list=${chatList.debugName()} limit=$limit rendered=${_chatListFlow.value.size} activePositions=${cache.activeListPositions.size}"
            )
        }.onFailure { error ->
            val tdError = (error as? TdLibException)?.error
            if (tdError?.code == 404) {
                Log.i(
                    TAG,
                    "LoadChats reached end: source=$source folder=$folderId requestId=$requestId list=${chatList.debugName()} limit=$limit rendered=${_chatListFlow.value.size} activePositions=${cache.activeListPositions.size} message=${tdError.message}"
                )
            } else {
                Log.e(
                    TAG,
                    "LoadChats failed: source=$source folder=$folderId requestId=$requestId list=${chatList.debugName()} limit=$limit rendered=${_chatListFlow.value.size} activePositions=${cache.activeListPositions.size}",
                    error
                )
                scheduleRecoveryIfNeeded("load_failed:$source:$folderId", force = true)
            }
        }
    }

    private fun scheduleRecoveryIfNeeded(reason: String, force: Boolean = false) {
        scope.launch(dispatchers.io) {
            if (!cacheHydrated.isCompleted) {
                cacheHydrated.await()
            }
            val now = System.currentTimeMillis()
            if (!force && now - lastRecoveryAttemptAtMs < RECOVERY_COOLDOWN_MS) return@launch

            val rendered = _chatListFlow.value.size
            val activePositions = cache.activeListPositions.size
            val cachedChats = cache.allChats.size
            val hasRecoverableState = activeRequestId == 0L ||
                    (rendered == 0 && (cachedChats > 0 || activePositions > 0)) ||
                    (rendered < MIN_EXPECTED_RENDERED_CHATS &&
                            currentLimit <= initialChatListLimit &&
                            cachedChats > rendered + MIN_EXPECTED_RENDERED_CHATS)

            if (!force && !hasRecoverableState) return@launch

            lastRecoveryAttemptAtMs = now
            Log.w(
                TAG,
                "Recovery trigger: reason=$reason rendered=$rendered activePositions=$activePositions cachedChats=$cachedChats currentLimit=$currentLimit activeRequestId=$activeRequestId list=${activeChatList.debugName()}"
            )

            if (activeRequestId == 0L) {
                selectFolder(activeFolderId)
                return@launch
            }

            refresh()
            delay(RECOVERY_POST_REFRESH_DELAY_MS)

            val afterRendered = _chatListFlow.value.size
            val afterActivePositions = cache.activeListPositions.size
            val stillLooksIncomplete = afterRendered == 0 ||
                    (afterRendered < MIN_EXPECTED_RENDERED_CHATS &&
                            afterActivePositions <= afterRendered &&
                            currentLimit < maxChatListLimit)

            if (!_isLoadingFlow.value && stillLooksIncomplete) {
                Log.w(
                    TAG,
                    "Recovery loadNextChunk: reason=$reason rendered=$afterRendered activePositions=$afterActivePositions currentLimit=$currentLimit"
                )
                loadNextChunk(RECOVERY_LOAD_CHUNK)
            }
        }
    }

    private fun TdApi.ChatList.debugName(): String = when (this) {
        is TdApi.ChatListMain -> "main"
        is TdApi.ChatListArchive -> "archive"
        is TdApi.ChatListFolder -> "folder:$chatFolderId"
        else -> javaClass.simpleName
    }

    companion object {
        private const val TAG = "ChatsListRepository"
        private const val REBUILD_THROTTLE_MS = 250L
        private const val MODEL_CACHE_SIZE = 256
        private const val RECOVERY_COOLDOWN_MS = 10_000L
        private const val RECOVERY_POST_REFRESH_DELAY_MS = 1_500L
        private const val RECOVERY_LOAD_CHUNK = 200
        private const val MIN_EXPECTED_RENDERED_CHATS = 20
        private const val LOAD_CHATS_STABILIZATION_PASSES = 4
        private const val INITIAL_CACHE_HYDRATION_LIMIT = 3000
    }
}
