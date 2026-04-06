package org.monogram.data.repository

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import org.monogram.core.DispatcherProvider
import org.monogram.core.ScopeProvider
import org.monogram.data.chats.*
import org.monogram.data.core.coRunCatching
import org.monogram.data.datasource.cache.ChatLocalDataSource
import org.monogram.data.datasource.remote.ChatRemoteSource
import org.monogram.data.datasource.remote.ChatsRemoteDataSource
import org.monogram.data.db.dao.ChatFolderDao
import org.monogram.data.db.dao.SearchHistoryDao
import org.monogram.data.db.dao.UserFullInfoDao
import org.monogram.data.gateway.TelegramGateway
import org.monogram.data.gateway.UpdateDispatcher
import org.monogram.data.infra.ConnectionManager
import org.monogram.data.infra.FileDownloadQueue
import org.monogram.data.mapper.ChatMapper
import org.monogram.data.mapper.MessageMapper
import org.monogram.domain.models.ChatModel
import org.monogram.domain.models.ChatPermissionsModel
import org.monogram.domain.models.FolderModel
import org.monogram.domain.models.TopicModel
import org.monogram.domain.repository.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

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
    scopeProvider: ScopeProvider,
    private val chatLocalDataSource: ChatLocalDataSource,
    private val connectionManager: ConnectionManager,
    private val databaseFile: File,
    private val searchHistoryDao: SearchHistoryDao,
    private val chatFolderDao: ChatFolderDao,
    private val userFullInfoDao: UserFullInfoDao,
    private val fileQueue: FileDownloadQueue,
    private val stringProvider: StringProvider
) : ChatListRepository,
    ChatFolderRepository,
    ChatOperationsRepository,
    ChatSearchRepository,
    ForumTopicsRepository,
    ChatSettingsRepository,
    ChatCreationRepository {

    private val scope = scopeProvider.appScope

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
        scopeProvider = scopeProvider,
        fileQueue = fileQueue,
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
        scopeProvider = scopeProvider,
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
        scopeProvider = scopeProvider,
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
    private val initialChatListLimit = 50
    private var currentLimit = initialChatListLimit

    private val modelCache = ConcurrentHashMap<Long, ChatModel>()
    private val invalidatedModels = ConcurrentHashMap.newKeySet<Long>()
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
            coRunCatching {
                val entities = chatLocalDataSource.getAllChats().first()
                if (entities.isNotEmpty()) {
                    entities.forEach { entity ->
                        cache.putChatFromEntity(entity)
                        persistenceManager.rememberSavedEntity(entity)
                    }
                    updateActiveListPositionsFromCache()
                    triggerUpdate()
                }
            }.onFailure { error ->
                Log.e(TAG, "Failed to hydrate chat cache", error)
            }

            if (!cacheHydrated.isCompleted) {
                cacheHydrated.complete(Unit)
            }
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
            updates.chatFolders.collect { update ->
                folderManager.handleChatFoldersUpdate(update)
                triggerUpdate()
            }
        }
    }

    private suspend fun rebuildAndEmit() {
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

            emitListUpdate(folderId, newList)
            persistenceManager.persistChatModels(newList, activeChatList)
        }.onFailure { error ->
            Log.e(TAG, "Error rebuilding chat list", error)
        }
    }

    private fun rebuildChatModels(limit: Int): List<ChatModel> {
        return listManager.rebuildChatList(limit, emptyList()) { chat, order, isPinned ->
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
        lastList = null
        lastListFolderId = -1
        _chatListFlow.value = emptyList()
        persistenceManager.clear()
    }

    private fun triggerUpdate(chatId: Long? = null) {
        if (chatId == null) {
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
        triggerUpdate()

        scope.launch(dispatchers.io) {
            chatRemoteSource.loadChats(newList, initialLoadLimit)
            setLoadingState(folderId, requestId, false)
            if (isRequestActive(folderId, requestId)) {
                triggerUpdate()
            }
        }
    }

    private fun updateActiveListPositionsFromCache() {
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
            chatRemoteSource.loadChats(chatList, limit)
            if (isRequestActive(folderId, requestId)) {
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

            chatRemoteSource.loadChats(chatList, limit)
            setLoadingState(folderId, requestId, false)
            if (isRequestActive(folderId, requestId)) {
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
                chatRemoteSource.toggleChatIsMarkedAsUnread(chatId, markAsUnread)
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
        cache.putChat(chatObj)
        listManager.updateActiveListPositions(chatObj.id, chatObj.positions, activeChatList)
        persistenceManager.scheduleChatSave(chatObj.id)
        triggerUpdate(chatObj.id)
    }

    companion object {
        private const val TAG = "ChatsListRepository"
        private const val REBUILD_THROTTLE_MS = 250L
    }
}
