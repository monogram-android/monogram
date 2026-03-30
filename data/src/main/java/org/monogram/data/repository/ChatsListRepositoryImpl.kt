package org.monogram.data.repository

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.drinkless.tdlib.TdApi
import org.monogram.core.DispatcherProvider
import org.monogram.core.ScopeProvider
import org.monogram.data.chats.*
import org.monogram.data.core.coRunCatching
import org.monogram.data.datasource.cache.ChatLocalDataSource
import org.monogram.data.datasource.cache.ChatsCacheDataSource
import org.monogram.data.datasource.remote.ChatRemoteSource
import org.monogram.data.datasource.remote.ChatsRemoteDataSource
import org.monogram.data.datasource.remote.ProxyRemoteDataSource
import org.monogram.data.db.dao.ChatFolderDao
import org.monogram.data.db.dao.SearchHistoryDao
import org.monogram.data.db.model.ChatEntity
import org.monogram.data.db.model.SearchHistoryEntity
import org.monogram.data.db.model.TopicEntity
import org.monogram.data.gateway.TelegramGateway
import org.monogram.data.gateway.UpdateDispatcher
import org.monogram.data.infra.ConnectionManager
import org.monogram.data.infra.FileDownloadQueue
import org.monogram.data.mapper.ChatMapper
import org.monogram.data.mapper.MessageMapper
import org.monogram.data.mapper.user.toEntity
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
    private val cacheDataSource: ChatsCacheDataSource,
    private val chatRemoteSource: ChatRemoteSource,
    private val proxyRemoteSource: ProxyRemoteDataSource,
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
    private val fileQueue: FileDownloadQueue,
    private val stringProvider: StringProvider
) : ChatsListRepository {

    private val TAG = "ChatsListRepo"
    private val scope = scopeProvider.appScope

    private val fileManager = ChatFileManager(
        gateway = gateway,
        dispatchers = dispatchers,
        scopeProvider = scopeProvider,
        fileQueue = fileQueue,
        onUpdate = { triggerUpdate(); refreshActiveForumTopics() }
    )
    private val typingManager = ChatTypingManager(
        scope = scope,
        usersCache = cache.usersCache,
        allChats = cache.allChats,
        stringProvider = stringProvider,
        onUpdate = { triggerUpdate() },
        onUserNeeded = { userId -> fetchUser(userId) }
    )
    private val listManager = ChatListManager(cache) { chatId ->
        if (cache.pendingChats.add(chatId)) {
            scope.launch { refreshChat(chatId); cache.pendingChats.remove(chatId) }
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
        triggerUpdate = { chatId -> triggerUpdate(chatId) },
        fetchUser = { userId -> fetchUser(userId) }
    )

    private val _chatListFlow = MutableStateFlow<List<ChatModel>>(emptyList())
    override val chatListFlow = _chatListFlow.asStateFlow()

    private val _folderChatsFlow = MutableSharedFlow<FolderChatsUpdate>(
        replay = 1,
        extraBufferCapacity = 10,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val folderChatsFlow: Flow<FolderChatsUpdate> = _folderChatsFlow.asSharedFlow()

    private val _foldersFlow = MutableStateFlow(listOf(FolderModel(-1, "")))
    override val foldersFlow = _foldersFlow.asStateFlow()

    private val _isLoadingFlow = MutableStateFlow(false)
    override val isLoadingFlow = _isLoadingFlow.asStateFlow()

    private val _folderLoadingFlow = MutableSharedFlow<FolderLoadingUpdate>(
        replay = 1,
        extraBufferCapacity = 10,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val folderLoadingFlow: Flow<FolderLoadingUpdate> = _folderLoadingFlow.asSharedFlow()

    override val connectionStateFlow = connectionManager.connectionStateFlow

    private val _forumTopicsFlow = MutableSharedFlow<Pair<Long, List<TopicModel>>>(
        replay = 1, extraBufferCapacity = 10, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val forumTopicsFlow = _forumTopicsFlow.asSharedFlow()

    private val folderManager = ChatFolderManager(
        gateway = gateway,
        dispatchers = dispatchers,
        scopeProvider = scopeProvider,
        foldersFlow = _foldersFlow,
        cacheProvider = cacheProvider,
        chatFolderDao = chatFolderDao
    )
    override val isArchivePinned = appPreferences.isArchivePinned
    override val isArchiveAlwaysVisible = appPreferences.isArchiveAlwaysVisible
    override val searchHistory: Flow<List<ChatModel>> = cacheProvider.searchHistory.map { ids ->
        coroutineScope {
            ids.map { id -> async { getChatById(id) } }.awaitAll().filterNotNull()
        }
    }

    private var activeForumChatId: Long? = null
    private var myUserId: Long = 0L

    @Volatile
    private var activeFolderId: Int = -1
    @Volatile private var activeChatList: TdApi.ChatList = TdApi.ChatListMain()
    @Volatile
    private var activeRequestId: Long = 0L
    private val requestIdGenerator = AtomicLong(0L)
    private val cacheHydrated = CompletableDeferred<Unit>()
    private val updateChannel = Channel<Unit>(Channel.CONFLATED)
    private var pendingSelectFolderJob: Job? = null
    private val maxChatListLimit = 10_000
    private var currentLimit = 50

    private val lastSavedEntities = ConcurrentHashMap<Long, ChatEntity>()
    private val pendingSaveJobs = ConcurrentHashMap<Long, Job>()
    private val modelCache = ConcurrentHashMap<Long, ChatModel>()
    private val invalidatedModels = ConcurrentHashMap.newKeySet<Long>()
    private var lastList: List<ChatModel>? = null
    private var lastListFolderId: Int = -1

    private val mainChatList = TdApi.ChatListMain()

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
                        lastSavedEntities[entity.id] = entity
                    }
                    updateActiveListPositionsFromCache()
                    triggerUpdate()
                }
            }.onFailure { e ->
                Log.e(TAG, "Failed to hydrate chat cache", e)
            }

            if (!cacheHydrated.isCompleted) {
                cacheHydrated.complete(Unit)
            }
        }

        scope.launch(dispatchers.io) {
            for (u in updateChannel) {
                rebuildAndEmit()
                delay(250)
            }
        }

        scope.launch {
            updates.chatsListUpdates.collect { update -> handleUpdate(update) }
        }

        scope.launch {
            updates.chatFolders.collect { update ->
                Log.d(TAG, "UpdateChatFolders received via dedicated flow")
                folderManager.handleChatFoldersUpdate(update)
                triggerUpdate()
            }
        }

        scope.launch {
            searchHistoryDao.getSearchHistory().collect { entities ->
                cacheProvider.setSearchHistory(entities.map { it.chatId })
            }
        }
    }

    private suspend fun rebuildAndEmit() {
        coRunCatching {
            activeRequestId
            val folderIdAtStart = activeFolderId
            val limitAtStart = maxOf(currentLimit, cache.activeListPositions.size)
                .coerceAtMost(maxChatListLimit)

            val newList = listManager.rebuildChatList(limitAtStart, emptyList()) { chat, order, isPinned ->
                val cached = modelCache[chat.id]
                if (cached != null && cached.order == order && cached.isPinned == isPinned && !invalidatedModels.contains(
                        chat.id
                    )
                ) {
                    cached
                } else {
                    modelFactory.mapChatToModel(chat, order, isPinned).also {
                        modelCache[chat.id] = it
                        invalidatedModels.remove(chat.id)
                    }
                }
            }

            if (folderIdAtStart != activeFolderId) {
                return@coRunCatching
            }

            val folderChanged = folderIdAtStart != lastListFolderId
            if (folderChanged || newList != lastList) {
                val pinnedInPositions = cache.activeListPositions.entries
                    .asSequence()
                    .filter { it.value.isPinned }
                    .map { it.key }
                    .toSet()
                val pinnedInList = newList.asSequence().filter { it.isPinned }.map { it.id }.toSet()
                if (pinnedInPositions.size != pinnedInList.size) {
                    Log.w(
                        "PinnedDiag",
                        "emit mismatch folder=$folderIdAtStart pinnedPositions=${pinnedInPositions.size} pinnedList=${pinnedInList.size} missingInList=${
                            (pinnedInPositions - pinnedInList).take(
                                10
                            )
                        }"
                    )
                }
                _chatListFlow.value = newList
                _folderChatsFlow.tryEmit(FolderChatsUpdate(folderIdAtStart, newList))
                lastList = newList
                lastListFolderId = folderIdAtStart

                val toSave = newList.map { model ->
                    val chat = cache.getChat(model.id)
                    if (chat != null) {
                        val persistPosition = resolvePersistPosition(chat)
                        val mapped = chatMapper.mapToEntity(chat, model)
                        if (persistPosition != null &&
                            (persistPosition.order != mapped.order || persistPosition.isPinned != mapped.isPinned)
                        ) {
                            mapped.copy(order = persistPosition.order, isPinned = persistPosition.isPinned)
                        } else {
                            mapped
                        }
                    }
                    else chatMapper.mapToEntity(model)
                }
                    .filter { entity ->
                        val last = lastSavedEntities[entity.id]
                        if (last == null || isEntityChanged(last, entity)) {
                            lastSavedEntities[entity.id] = entity
                            true
                        } else {
                            false
                        }
                    }

                if (toSave.isNotEmpty()) {
                    chatLocalDataSource.insertChats(toSave)
                }
            }
        }.onFailure { e ->
            Log.e(TAG, "Error rebuilding chat list", e)
        }
    }

    private fun handleUpdate(update: TdApi.Update) {
        when (update) {
            is TdApi.UpdateNewChat -> {
                cache.putChat(update.chat)
                listManager.updateActiveListPositions(update.chat.id, update.chat.positions, activeChatList)
                saveChatToDb(update.chat.id)
                triggerUpdate(update.chat.id)
            }
            is TdApi.UpdateChatTitle -> {
                cache.updateChat(update.chatId) { it.title = update.title }
                saveChatToDb(update.chatId)
                triggerUpdate(update.chatId)
            }
            is TdApi.UpdateChatPhoto -> {
                cache.updateChat(update.chatId) { it.photo = update.photo }
                saveChatToDb(update.chatId)
                triggerUpdate(update.chatId)
            }
            is TdApi.UpdateChatEmojiStatus -> {
                cache.updateChat(update.chatId) { it.emojiStatus = update.emojiStatus }
                saveChatToDb(update.chatId)
                triggerUpdate(update.chatId)
            }
            is TdApi.UpdateChatDraftMessage -> {
                cache.updateChat(update.chatId) { chat ->
                    chat.draftMessage = update.draftMessage
                    if (!update.positions.isNullOrEmpty()) {
                        chat.positions = update.positions
                        listManager.updateActiveListPositions(update.chatId, update.positions, activeChatList)
                    }
                }
                saveChatToDb(update.chatId)
                triggerUpdate(update.chatId)
            }
            is TdApi.UpdateChatPosition -> {
                if (listManager.updateChatPositionInCache(update.chatId, update.position, activeChatList)) {
                    saveChatToDb(update.chatId)
                    triggerUpdate(update.chatId)
                }
            }
            is TdApi.UpdateChatLastMessage -> {
                cache.updateChat(update.chatId) { chat ->
                    chat.lastMessage = update.lastMessage
                    if (!update.positions.isNullOrEmpty()) {
                        chat.positions = update.positions
                        listManager.updateActiveListPositions(update.chatId, update.positions, activeChatList)
                    }
                    typingManager.clearTypingStatus(update.chatId)
                }
                saveChatToDb(update.chatId)
                triggerUpdate(update.chatId)
            }
            is TdApi.UpdateChatReadInbox -> {
                cache.updateChat(update.chatId) { it.unreadCount = update.unreadCount }
                saveChatToDb(update.chatId)
                triggerUpdate(update.chatId)
            }
            is TdApi.UpdateChatReadOutbox -> {
                cache.updateChat(update.chatId) { it.lastReadOutboxMessageId = update.lastReadOutboxMessageId }
                saveChatToDb(update.chatId)
                triggerUpdate(update.chatId)
            }
            is TdApi.UpdateChatUnreadMentionCount -> {
                cache.updateChat(update.chatId) { it.unreadMentionCount = update.unreadMentionCount }
                folderManager.handleUpdateChatUnreadCount(update.chatId, update.unreadMentionCount)
                saveChatToDb(update.chatId)
                triggerUpdate(update.chatId)
            }
            is TdApi.UpdateChatUnreadReactionCount -> {
                cache.updateChat(update.chatId) { it.unreadReactionCount = update.unreadReactionCount }
                saveChatToDb(update.chatId)
                triggerUpdate(update.chatId)
            }
            is TdApi.UpdateMessageMentionRead -> {
                cache.updateChat(update.chatId) { it.unreadMentionCount = update.unreadMentionCount }
                folderManager.handleUpdateChatUnreadCount(update.chatId, update.unreadMentionCount)
                saveChatToDb(update.chatId)
                triggerUpdate(update.chatId)
            }
            is TdApi.UpdateMessageReactions -> {
                saveChatToDb(update.chatId)
                triggerUpdate(update.chatId)
            }
            is TdApi.UpdateFile -> {
                if (fileManager.handleFileUpdate(update.file)) {
                    val chatId = fileManager.getChatIdByPhotoId(update.file.id)
                    triggerUpdate(chatId)
                    refreshActiveForumTopics()
                }
            }
            is TdApi.UpdateDeleteMessages -> {
                if (update.isPermanent || update.fromCache) {
                    scope.launch { refreshChat(update.chatId) }
                }
            }
            is TdApi.UpdateChatFolders -> {
                Log.d(TAG, "UpdateChatFolders received in handleUpdate")
                folderManager.handleChatFoldersUpdate(update)
                triggerUpdate()
            }
            is TdApi.UpdateUserStatus -> {
                cache.updateUser(update.userId) { it.status = update.status }
                triggerUpdate()
            }
            is TdApi.UpdateUser -> {
                cache.putUser(update.user)
                if (update.user.id == myUserId) myUserId = update.user.id
                triggerUpdate()
                refreshActiveForumTopics()
            }
            is TdApi.UpdateSupergroup -> {
                cache.putSupergroup(update.supergroup)
                saveChatsBySupergroupId(update.supergroup.id)
                triggerUpdate()
            }
            is TdApi.UpdateBasicGroup -> {
                cache.putBasicGroup(update.basicGroup)
                saveChatsByBasicGroupId(update.basicGroup.id)
                triggerUpdate()
            }
            is TdApi.UpdateSupergroupFullInfo -> {
                cache.putSupergroupFullInfo(update.supergroupId, update.supergroupFullInfo)
                scope.launch(dispatchers.io) {
                    val chatId =
                        cache.allChats.values.find { (it.type as? TdApi.ChatTypeSupergroup)?.supergroupId == update.supergroupId }?.id
                    if (chatId != null) {
                        chatLocalDataSource.insertChatFullInfo(update.supergroupFullInfo.toEntity(chatId))
                    }
                }
                triggerUpdate()
            }
            is TdApi.UpdateBasicGroupFullInfo -> {
                cache.putBasicGroupFullInfo(update.basicGroupId, update.basicGroupFullInfo)
                scope.launch(dispatchers.io) {
                    val chatId =
                        cache.allChats.values.find { (it.type as? TdApi.ChatTypeBasicGroup)?.basicGroupId == update.basicGroupId }?.id
                    if (chatId != null) {
                        chatLocalDataSource.insertChatFullInfo(update.basicGroupFullInfo.toEntity(chatId))
                    }
                }
                triggerUpdate()
            }
            is TdApi.UpdateSecretChat -> {
                cache.putSecretChat(update.secretChat); triggerUpdate()
            }
            is TdApi.UpdateChatAction -> typingManager.handleChatAction(update)
            is TdApi.UpdateChatNotificationSettings -> {
                cache.updateChat(update.chatId) { it.notificationSettings = update.notificationSettings }
                saveChatToDb(update.chatId)
                triggerUpdate(update.chatId)
            }
            is TdApi.UpdateChatViewAsTopics -> {
                cache.updateChat(update.chatId) { it.viewAsTopics = update.viewAsTopics }
                saveChatToDb(update.chatId)
                triggerUpdate(update.chatId)
            }
            is TdApi.UpdateChatIsTranslatable -> {
                cache.updateChat(update.chatId) { it.isTranslatable = update.isTranslatable }
                saveChatToDb(update.chatId)
                triggerUpdate(update.chatId)
            }
            is TdApi.UpdateChatPermissions -> {
                cache.putChatPermissions(update.chatId, update.permissions)
                cache.updateChat(update.chatId) { it.permissions = update.permissions }
                saveChatToDb(update.chatId)
                triggerUpdate(update.chatId)
            }
            is TdApi.UpdateChatMember -> {
                val memberId = update.newChatMember.memberId
                if (memberId is TdApi.MessageSenderUser && memberId.userId == myUserId) {
                    cache.putMyChatMember(update.chatId, update.newChatMember)
                    triggerUpdate(update.chatId)
                }
            }
            is TdApi.UpdateChatOnlineMemberCount -> {
                cache.putOnlineMemberCount(update.chatId, update.onlineMemberCount)
                saveChatToDb(update.chatId)
                triggerUpdate(update.chatId)
            }
            is TdApi.UpdateAuthorizationState -> {
                Log.d(TAG, "UpdateAuthorizationState: ${update.authorizationState}")
                if (update.authorizationState is TdApi.AuthorizationStateLoggingOut ||
                    update.authorizationState is TdApi.AuthorizationStateClosed
                ) {
                    cache.clearAll()
                    modelCache.clear()
                    invalidatedModels.clear()
                    lastSavedEntities.clear()
                    scope.launch { chatLocalDataSource.clearAll() }
                }
            }
            else -> {}
        }
    }

    private fun saveChatToDb(chatId: Long) {
        val chat = cache.getChat(chatId) ?: return

        pendingSaveJobs[chatId]?.cancel()
        pendingSaveJobs[chatId] = scope.launch(dispatchers.io) {
            delay(2000)
            val position = resolvePersistPosition(chat)

            val model = modelFactory.mapChatToModel(chat, position?.order ?: 0L, position?.isPinned ?: false)
            val entity = chatMapper.mapToEntity(chat, model)

            val last = lastSavedEntities[chatId]
            if (last == null || isEntityChanged(last, entity)) {
                chatLocalDataSource.insertChat(entity)
                lastSavedEntities[chatId] = entity
            }
            pendingSaveJobs.remove(chatId)
        }
    }

    private fun isEntityChanged(old: ChatEntity, new: ChatEntity): Boolean {
        return old.title != new.title ||
                old.unreadCount != new.unreadCount ||
                old.unreadMentionCount != new.unreadMentionCount ||
                old.unreadReactionCount != new.unreadReactionCount ||
                old.avatarPath != new.avatarPath ||
                old.lastMessageText != new.lastMessageText ||
                old.lastMessageTime != new.lastMessageTime ||
                old.lastMessageDate != new.lastMessageDate ||
                old.order != new.order ||
                old.isPinned != new.isPinned ||
                old.isMuted != new.isMuted ||
                old.isChannel != new.isChannel ||
                old.isGroup != new.isGroup ||
                old.type != new.type ||
                old.privateUserId != new.privateUserId ||
                old.basicGroupId != new.basicGroupId ||
                old.supergroupId != new.supergroupId ||
                old.secretChatId != new.secretChatId ||
                old.positionsCache != new.positionsCache ||
                old.isArchived != new.isArchived ||
                old.memberCount != new.memberCount ||
                old.onlineCount != new.onlineCount ||
                old.isMarkedAsUnread != new.isMarkedAsUnread ||
                old.hasProtectedContent != new.hasProtectedContent ||
                old.isTranslatable != new.isTranslatable ||
                old.hasAutomaticTranslation != new.hasAutomaticTranslation ||
                old.messageAutoDeleteTime != new.messageAutoDeleteTime ||
                old.canBeDeletedOnlyForSelf != new.canBeDeletedOnlyForSelf ||
                old.canBeDeletedForAllUsers != new.canBeDeletedForAllUsers ||
                old.canBeReported != new.canBeReported ||
                old.lastReadInboxMessageId != new.lastReadInboxMessageId ||
                old.lastReadOutboxMessageId != new.lastReadOutboxMessageId ||
                old.lastMessageId != new.lastMessageId ||
                old.isLastMessageOutgoing != new.isLastMessageOutgoing ||
                old.replyMarkupMessageId != new.replyMarkupMessageId ||
                old.messageSenderId != new.messageSenderId ||
                old.blockList != new.blockList ||
                old.emojiStatusId != new.emojiStatusId ||
                old.accentColorId != new.accentColorId ||
                old.profileAccentColorId != new.profileAccentColorId ||
                old.backgroundCustomEmojiId != new.backgroundCustomEmojiId ||
                old.photoId != new.photoId ||
                old.isSupergroup != new.isSupergroup ||
                old.isAdmin != new.isAdmin ||
                old.isOnline != new.isOnline ||
                old.typingAction != new.typingAction ||
                old.draftMessage != new.draftMessage ||
                old.isVerified != new.isVerified ||
                old.isSponsor != new.isSponsor ||
                old.viewAsTopics != new.viewAsTopics ||
                old.isForum != new.isForum ||
                old.isBot != new.isBot ||
                old.isMember != new.isMember ||
                old.username != new.username ||
                old.description != new.description ||
                old.inviteLink != new.inviteLink ||
                old.permissionCanSendBasicMessages != new.permissionCanSendBasicMessages ||
                old.permissionCanSendAudios != new.permissionCanSendAudios ||
                old.permissionCanSendDocuments != new.permissionCanSendDocuments ||
                old.permissionCanSendPhotos != new.permissionCanSendPhotos ||
                old.permissionCanSendVideos != new.permissionCanSendVideos ||
                old.permissionCanSendVideoNotes != new.permissionCanSendVideoNotes ||
                old.permissionCanSendVoiceNotes != new.permissionCanSendVoiceNotes ||
                old.permissionCanSendPolls != new.permissionCanSendPolls ||
                old.permissionCanSendOtherMessages != new.permissionCanSendOtherMessages ||
                old.permissionCanAddLinkPreviews != new.permissionCanAddLinkPreviews ||
                old.permissionCanEditTag != new.permissionCanEditTag ||
                old.permissionCanChangeInfo != new.permissionCanChangeInfo ||
                old.permissionCanInviteUsers != new.permissionCanInviteUsers ||
                old.permissionCanPinMessages != new.permissionCanPinMessages ||
                old.permissionCanCreateTopics != new.permissionCanCreateTopics ||
                old.lastMessageContentType != new.lastMessageContentType ||
                old.lastMessageSenderName != new.lastMessageSenderName
    }

    private fun resolvePersistPosition(chat: TdApi.Chat): TdApi.ChatPosition? {
        return chat.positions.find { pos ->
            pos.order != 0L && listManager.isSameChatList(pos.list, mainChatList)
        }
            ?: chat.positions.find { pos ->
                pos.order != 0L && listManager.isSameChatList(pos.list, activeChatList)
            }
            ?: chat.positions.firstOrNull { it.order != 0L }
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
        val chatId = activeForumChatId ?: return
        scope.launch { getForumTopics(chatId) }
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
        Log.d(TAG, "selectFolder: folderId=$folderId")
        val newList: TdApi.ChatList = when (folderId) {
            -1 -> TdApi.ChatListMain()
            -2 -> TdApi.ChatListArchive()
            else -> TdApi.ChatListFolder(folderId)
        }
        if (folderId == activeFolderId &&
            listManager.isSameChatList(newList, activeChatList) &&
            activeRequestId != 0L
        ) {
            Log.d(TAG, "selectFolder: already initialized for this folder, skipping")
            return
        }

        activeFolderId = folderId
        activeChatList = newList
        updateActiveListPositionsFromCache()
        val cachedChatsCount = cache.activeListPositions.size
        val initialLoadLimit = cachedChatsCount.coerceAtLeast(50).coerceAtMost(maxChatListLimit)
        currentLimit = initialLoadLimit

        val requestId = requestIdGenerator.incrementAndGet()
        activeRequestId = requestId
        Log.d(
            TAG,
            "selectFolder: cache positions=$cachedChatsCount, initialLoadLimit=$initialLoadLimit"
        )
        setLoadingState(folderId, requestId, true)
        triggerUpdate()

        scope.launch(dispatchers.io) {
            Log.d(TAG, "selectFolder: calling loadChats for $newList with limit=$initialLoadLimit")
            chatRemoteSource.loadChats(newList, initialLoadLimit)
            val discoveredPositions = cache.activeListPositions.size
            val expandedLimit = maxOf(currentLimit, discoveredPositions).coerceAtMost(maxChatListLimit)
            if (expandedLimit != currentLimit) {
                currentLimit = expandedLimit
            }
            setLoadingState(folderId, requestId, false)
            Log.d(TAG, "selectFolder: loadChats completed")
            if (isRequestActive(folderId, requestId)) {
                triggerUpdate()
            }
        }
    }

    private fun updateActiveListPositionsFromCache() {
        cache.activeListPositions.clear()
        cache.authoritativeActiveListChatIds.clear()
        cache.protectedPinnedChatIds.clear()
        cache.allChats.values.forEach { chat ->
            chat.positions.find { listManager.isSameChatList(it.list, activeChatList) }?.let {
                if (it.order != 0L) {
                    cache.activeListPositions[chat.id] = it
                    if (it.isPinned) {
                        cache.protectedPinnedChatIds.add(chat.id)
                    }
                }
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
            val limit = currentLimit.coerceAtLeast(50).coerceAtMost(maxChatListLimit)
            chatRemoteSource.loadChats(chatList, limit)
            if (isRequestActive(folderId, requestId)) {
                triggerUpdate()
            }
        }
    }

    override fun loadNextChunk(limit: Int) {
        if (!cacheHydrated.isCompleted) return
        if (_isLoadingFlow.value || currentLimit >= maxChatListLimit) return
        Log.d(TAG, "loadNextChunk: limit=$limit")

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
                lastSavedEntities[entity.id] = entity
                cache.getChat(chatId)
            }
            ?: remoteDataSource.getChat(chatId)?.also {
                cache.putChat(it)
                listManager.updateActiveListPositions(it.id, it.positions, activeChatList)
                saveChatToDb(it.id)
            } ?: return null

        val position = chatObj.positions.find { listManager.isSameChatList(it.list, activeChatList) }
        return coRunCatching {
            modelFactory.mapChatToModel(chatObj, position?.order ?: 0L, position?.isPinned ?: false)
        }.getOrNull()
    }

    override suspend fun searchChats(query: String): List<ChatModel> {
        if (query.isBlank()) return emptyList()
        val result = chatRemoteSource.searchChats(query, 50) ?: return emptyList()
        return coroutineScope {
            result.chatIds.map { id -> async { getChatById(id) } }.awaitAll().filterNotNull()
        }
    }

    override suspend fun searchPublicChats(query: String): List<ChatModel> {
        if (query.isBlank()) return emptyList()
        val result = chatRemoteSource.searchPublicChats(query) ?: return emptyList()
        return coroutineScope {
            result.chatIds.map { id -> async { getChatById(id) } }.awaitAll().filterNotNull()
        }
    }

    override suspend fun searchMessages(query: String, offset: String, limit: Int): SearchMessagesResult {
        val result = chatRemoteSource.searchMessages(query, offset, limit) ?: return SearchMessagesResult(emptyList(), "")
        val models = coroutineScope {
            result.messages.map { msg -> async { messageMapper.mapMessageToModel(msg, isChatOpen = false) } }.awaitAll()
        }
        return SearchMessagesResult(models, result.nextOffset)
    }

    override fun toggleMuteChats(chatIds: Set<Long>, mute: Boolean) {
        val muteFor = if (mute) Int.MAX_VALUE else 0
        chatIds.forEach { chatId -> scope.launch(dispatchers.io) { chatRemoteSource.muteChat(chatId, muteFor) } }
    }

    override fun toggleArchiveChats(chatIds: Set<Long>, archive: Boolean) {
        chatIds.forEach { chatId -> scope.launch(dispatchers.io) { chatRemoteSource.archiveChat(chatId, archive) } }
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
        chatIds.forEach { chatId -> scope.launch(dispatchers.io) { chatRemoteSource.deleteChat(chatId) } }
    }

    override fun leaveChat(chatId: Long) {
        scope.launch(dispatchers.io) { chatRemoteSource.leaveChat(chatId) }
    }

    override fun setArchivePinned(pinned: Boolean) { appPreferences.setArchivePinned(pinned) }

    override suspend fun createFolder(title: String, iconName: String?, includedChatIds: List<Long>) =
        folderManager.createFolder(title, iconName, includedChatIds)

    override suspend fun deleteFolder(folderId: Int) = chatRemoteSource.deleteFolder(folderId)

    override suspend fun updateFolder(folderId: Int, title: String, iconName: String?, includedChatIds: List<Long>) =
        folderManager.updateFolder(folderId, title, iconName, includedChatIds)

    override suspend fun reorderFolders(folderIds: List<Int>) = folderManager.reorderFolders(folderIds)

    override suspend fun getForumTopics(
        chatId: Long, query: String, offsetDate: Int,
        offsetMessageId: Long, offsetForumTopicId: Int, limit: Int
    ): List<TopicModel> {
        activeForumChatId = chatId
        val result = chatRemoteSource.getForumTopics(chatId, query, offsetDate, offsetMessageId, offsetForumTopicId, limit)
            ?: return emptyList()

        val models = result.topics.map { topic ->
            val (txt, entities, time) = chatMapper.formatMessageInfo(topic.lastMessage, null) { userId ->
                cache.usersCache[userId]?.firstName ?: run { fetchUser(userId); null }
            }
            val emojiId = topic.info.icon.customEmojiId
            var emojiPath: String? = null
            if (emojiId != 0L) {
                emojiPath = fileManager.getEmojiPath(emojiId)
                if (emojiPath == null) fileManager.loadEmoji(emojiId)
            }

            var senderName: String? = null
            var senderAvatar: String? = null
            when (val senderId = topic.lastMessage?.senderId) {
                is TdApi.MessageSenderUser -> {
                    cache.usersCache[senderId.userId]?.let { user ->
                        senderName = user.firstName
                        user.profilePhoto?.small?.let { small ->
                            fileManager.registerTrackedFile(small.id)
                            senderAvatar = small.local.path.ifEmpty { fileManager.getFilePath(small.id) }
                            if (senderAvatar.isNullOrEmpty()) fileManager.downloadFile(small.id, 24, synchronous = false)
                        }
                    } ?: fetchUser(senderId.userId)
                }
                is TdApi.MessageSenderChat -> cache.getChat(senderId.chatId)?.let { chat ->
                    senderName = chat.title
                    chat.photo?.small?.let { small ->
                        fileManager.registerTrackedFile(small.id)
                        senderAvatar = small.local.path.ifEmpty { fileManager.getFilePath(small.id) }
                        if (senderAvatar.isNullOrEmpty()) fileManager.downloadFile(small.id, 24, synchronous = false)
                    }
                }
                else -> {}
            }

            TopicModel(
                topic.info.forumTopicId, topic.info.name, emojiId, emojiPath,
                topic.info.icon.color, topic.info.isClosed, topic.isPinned,
                topic.unreadCount, txt, entities, time, topic.order, senderName, senderAvatar
            )
        }

        scope.launch(dispatchers.io) {
            chatLocalDataSource.insertTopics(result.topics.map { topic ->
                val (txt, _, time) = chatMapper.formatMessageInfo(topic.lastMessage, null) { null }
                TopicEntity(
                    chatId = chatId,
                    id = topic.info.forumTopicId,
                    name = topic.info.name,
                    iconCustomEmojiId = topic.info.icon.customEmojiId,
                    iconColor = topic.info.icon.color,
                    isClosed = topic.info.isClosed,
                    isPinned = topic.isPinned,
                    unreadCount = topic.unreadCount,
                    lastMessageText = txt,
                    lastMessageTime = time,
                    order = topic.order,
                    lastMessageSenderName = null
                )
            })
        }

        _forumTopicsFlow.tryEmit(chatId to models)
        return models
    }

    override fun clearChatHistory(chatId: Long, revoke: Boolean) {
        scope.launch(dispatchers.io) { chatRemoteSource.clearChatHistory(chatId, revoke) }
    }

    override suspend fun getChatLink(chatId: Long) = chatRemoteSource.getChatLink(chatId)

    override fun reportChat(chatId: Long, reason: String, messageIds: List<Long>) {
        scope.launch(dispatchers.io) { chatRemoteSource.reportChat(chatId, reason, messageIds) }
    }

    override fun addSearchChatId(chatId: Long) {
        cacheProvider.addSearchChatId(chatId)
        scope.launch(dispatchers.io) {
            searchHistoryDao.insertSearchChatId(SearchHistoryEntity(chatId))
        }
    }

    override fun removeSearchChatId(chatId: Long) {
        cacheProvider.removeSearchChatId(chatId)
        scope.launch(dispatchers.io) {
            searchHistoryDao.deleteSearchChatId(chatId)
        }
    }

    override fun clearSearchHistory() {
        cacheProvider.clearSearchHistory()
        scope.launch(dispatchers.io) {
            searchHistoryDao.clearAll()
        }
    }

    override suspend fun createGroup(title: String, userIds: List<Long>, messageAutoDeleteTime: Int) =
        chatRemoteSource.createGroup(title, userIds, messageAutoDeleteTime)

    override suspend fun createChannel(title: String, description: String, isMegagroup: Boolean, messageAutoDeleteTime: Int) =
        chatRemoteSource.createChannel(title, description, isMegagroup, messageAutoDeleteTime)

    override suspend fun setChatPhoto(chatId: Long, photoPath: String) = chatRemoteSource.setChatPhoto(chatId, photoPath)
    override suspend fun setChatTitle(chatId: Long, title: String) = chatRemoteSource.setChatTitle(chatId, title)
    override suspend fun setChatDescription(chatId: Long, description: String) = chatRemoteSource.setChatDescription(chatId, description)
    override suspend fun setChatUsername(chatId: Long, username: String) = chatRemoteSource.setChatUsername(chatId, username)
    override suspend fun setChatPermissions(chatId: Long, permissions: ChatPermissionsModel) = chatRemoteSource.setChatPermissions(chatId, permissions)
    override suspend fun setChatSlowModeDelay(chatId: Long, slowModeDelay: Int) = chatRemoteSource.setChatSlowModeDelay(chatId, slowModeDelay)
    override suspend fun toggleChatIsForum(chatId: Long, isForum: Boolean) = chatRemoteSource.toggleChatIsForum(chatId, isForum)
    override suspend fun toggleChatIsTranslatable(chatId: Long, isTranslatable: Boolean) = chatRemoteSource.toggleChatIsTranslatable(chatId, isTranslatable)

    override fun getDatabaseSize(): Long {
        return if (databaseFile.exists()) databaseFile.length() else 0L
    }

    override fun clearDatabase() {
        scope.launch(dispatchers.io) {
            chatLocalDataSource.clearAll()
            lastSavedEntities.clear()
            modelCache.clear()
            invalidatedModels.clear()
            triggerUpdate()
        }
    }

    private fun fetchUser(userId: Long) {
        if (userId == 0L) return
        if (cache.pendingUsers.add(userId)) {
            scope.launch(dispatchers.io) {
                coRunCatching {
                    val user = gateway.execute(TdApi.GetUser(userId))
                    cache.putUser(user)
                    triggerUpdate()
                }
                cache.pendingUsers.remove(userId)
            }
        }
    }

    private suspend fun refreshChat(chatId: Long) {
        val chatObj = remoteDataSource.getChat(chatId) ?: return
        cache.putChat(chatObj)
        listManager.updateActiveListPositions(chatObj.id, chatObj.positions, activeChatList)
        saveChatToDb(chatObj.id)
        triggerUpdate(chatObj.id)
    }

    private fun saveChatsBySupergroupId(supergroupId: Long) {
        cache.allChats.values
            .asSequence()
            .filter { (it.type as? TdApi.ChatTypeSupergroup)?.supergroupId == supergroupId }
            .map { it.id }
            .forEach { saveChatToDb(it) }
    }

    private fun saveChatsByBasicGroupId(basicGroupId: Long) {
        cache.allChats.values
            .asSequence()
            .filter { (it.type as? TdApi.ChatTypeBasicGroup)?.basicGroupId == basicGroupId }
            .map { it.id }
            .forEach { saveChatToDb(it) }
    }
}
