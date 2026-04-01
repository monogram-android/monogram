package org.monogram.data.repository

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import org.drinkless.tdlib.TdApi
import org.monogram.core.ScopeProvider
import org.monogram.data.chats.ChatCache
import org.monogram.data.core.coRunCatching
import org.monogram.data.datasource.cache.ChatLocalDataSource
import org.monogram.data.datasource.cache.RoomUserLocalDataSource
import org.monogram.data.datasource.cache.UserLocalDataSource
import org.monogram.data.datasource.remote.UserRemoteDataSource
import org.monogram.data.db.dao.KeyValueDao
import org.monogram.data.db.model.KeyValueEntity
import org.monogram.data.gateway.TelegramGateway
import org.monogram.data.gateway.UpdateDispatcher
import org.monogram.data.infra.FileDownloadQueue
import org.monogram.data.infra.SponsorSyncManager
import org.monogram.data.mapper.user.*
import org.monogram.domain.models.*
import org.monogram.domain.repository.ChatMemberStatus
import org.monogram.domain.repository.ChatMembersFilter
import org.monogram.domain.repository.UserRepository
import java.util.concurrent.ConcurrentHashMap

class UserRepositoryImpl(
    private val remote: UserRemoteDataSource,
    private val userLocal: UserLocalDataSource,
    private val chatLocal: ChatLocalDataSource,
    private val chatCache: ChatCache,
    private val gateway: TelegramGateway,
    private val updates: UpdateDispatcher,
    private val fileQueue: FileDownloadQueue,
    private val keyValueDao: KeyValueDao,
    private val sponsorSyncManager: SponsorSyncManager,
    scopeProvider: ScopeProvider
) : UserRepository {

    private val scope = scopeProvider.appScope
    private var currentUserId: Long = 0L
    private val userRequests = ConcurrentHashMap<Long, Deferred<TdApi.User?>>()
    private val fullInfoRequests = ConcurrentHashMap<Long, Deferred<TdApi.UserFullInfo?>>()
    private val missingUsersUntilMs = ConcurrentHashMap<Long, Long>()
    private val missingUserFullInfoUntilMs = ConcurrentHashMap<Long, Long>()

    private val emojiPathCache = ConcurrentHashMap<Long, String>()
    private val fileIdToUserIdMap = ConcurrentHashMap<Int, Long>()
    private val avatarDownloadPriority = 24
    private val avatarHdPrefetchPriority = 8

    private val _currentUserFlow = MutableStateFlow<UserModel?>(null)
    override val currentUserFlow = _currentUserFlow.asStateFlow()

    private val _userUpdateFlow = MutableSharedFlow<Long>(
        extraBufferCapacity = 10,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val anyUserUpdateFlow = _userUpdateFlow.asSharedFlow()

    init {
        scope.launch {
            restoreCurrentUserFromLocal()
        }

        scope.launch {
            updates.user.collect { update ->
                cacheUser(update.user)
                if (userLocal is RoomUserLocalDataSource) {
                    val personalAvatarPath = resolveStoredPersonalAvatarPath(update.user.id)
                    userLocal.saveUser(update.user.toEntity(personalAvatarPath))
                }
                if (update.user.id == currentUserId) refreshCurrentUser()
                _userUpdateFlow.emit(update.user.id)
            }
        }
        scope.launch {
            updates.userStatus.collect { update ->
                userLocal.getUser(update.userId)?.let { cached ->
                    cached.status = update.status
                    if (userLocal is RoomUserLocalDataSource) {
                        val personalAvatarPath = resolveStoredPersonalAvatarPath(cached.id)
                        userLocal.saveUser(cached.toEntity(personalAvatarPath))
                    }
                    if (update.userId == currentUserId) refreshCurrentUser()
                    _userUpdateFlow.emit(update.userId)
                }
            }
        }
        scope.launch {
            updates.file.collect { update ->
                val file = update.file
                if (file.local.isDownloadingCompleted) {
                    userLocal.getAllUsers().forEach { user ->
                        val small = user.profilePhoto?.small
                        val big = user.profilePhoto?.big
                        if (small?.id == file.id || big?.id == file.id) {
                            _userUpdateFlow.emit(user.id)
                            if (user.id == currentUserId) refreshCurrentUser()
                        }
                    }
                    if (file.local.path.isNotEmpty()) {
                        val userId = fileIdToUserIdMap.remove(file.id)
                        if (userId != null) {
                            userLocal.getUser(userId)?.let { user ->
                                val emojiId = user.extractEmojiStatusId()
                                if (emojiId != 0L) {
                                    emojiPathCache[emojiId] = file.local.path
                                }
                            }
                            _userUpdateFlow.emit(userId)
                            if (userId == currentUserId) refreshCurrentUser()
                        }
                    }
                }
            }
        }
    }

    private suspend fun restoreCurrentUserFromLocal() {
        val cachedUserId = keyValueDao.getValue(KEY_CURRENT_USER_ID)?.value?.toLongOrNull() ?: return
        if (cachedUserId <= 0L) return

        currentUserId = cachedUserId
        val user = userLocal.getUser(cachedUserId) ?: return
        val model = mapUserModel(user, userLocal.getUserFullInfo(cachedUserId))
        _currentUserFlow.value = model
    }

    private fun TdApi.User.extractEmojiStatusId(): Long {
        return when (val type = this.emojiStatus?.type) {
            is TdApi.EmojiStatusTypeCustomEmoji -> type.customEmojiId
            is TdApi.EmojiStatusTypeUpgradedGift -> type.modelCustomEmojiId
            else -> 0L
        }
    }

    private suspend fun resolveEmojiPath(user: TdApi.User): String? {
        val emojiId = user.extractEmojiStatusId()
        if (emojiId == 0L) return null

        emojiPathCache[emojiId]?.let { return it }

        return try {
            val result = gateway.execute(TdApi.GetCustomEmojiStickers(longArrayOf(emojiId)))
            if (result is TdApi.Stickers && result.stickers.isNotEmpty()) {
                val file = result.stickers.first().sticker
                if (file.local.isDownloadingCompleted && file.local.path.isNotEmpty()) {
                    emojiPathCache[emojiId] = file.local.path
                    file.local.path
                } else {
                    fileIdToUserIdMap[file.id] = user.id
                    fileQueue.enqueue(file.id, 1, FileDownloadQueue.DownloadType.DEFAULT, synchronous = false)
                    coRunCatching { fileQueue.waitForDownload(file.id).await() }

                    val refreshedPath = coRunCatching {
                        (gateway.execute(TdApi.GetFile(file.id)) as? TdApi.File)
                            ?.local
                            ?.path
                            ?.takeIf { it.isNotEmpty() }
                    }.getOrNull()
                    if (refreshedPath != null) {
                        emojiPathCache[emojiId] = refreshedPath
                    }
                    refreshedPath
                }
            } else null
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun refreshCurrentUser() {
        val user = userLocal.getUser(currentUserId) ?: return
        val model = mapUserModel(user, userLocal.getUserFullInfo(currentUserId))
        _currentUserFlow.value = model
    }

    override suspend fun getMe(): UserModel {
        val user = remote.getMe() ?: return UserModel(0, "Error")
        currentUserId = user.id
        coRunCatching { keyValueDao.insertValue(KeyValueEntity(KEY_CURRENT_USER_ID, user.id.toString())) }
        cacheUser(user)
        if (userLocal is RoomUserLocalDataSource) {
            val personalAvatarPath = resolveStoredPersonalAvatarPath(user.id)
            userLocal.saveUser(user.toEntity(personalAvatarPath))
        }
        val model = mapUserModel(user, userLocal.getUserFullInfo(user.id))
        _currentUserFlow.update { model }
        return model
    }

    override suspend fun getUser(userId: Long): UserModel? {
        if (userId <= 0) return null
        userLocal.getUser(userId)?.let {
            return mapUserModel(it, userLocal.getUserFullInfo(userId))
        }

        if (userLocal is RoomUserLocalDataSource) {
            userLocal.loadUser(userId)?.let { entity ->
                val user = entity.toTdApi()
                cacheUser(user)
                return mapUserModel(user, userLocal.getUserFullInfo(userId))
            }
        }

        if (isNegativeCached(missingUsersUntilMs, userId)) return null

        val deferred = userRequests.getOrPut(userId) {
            scope.async {
                fetchAndCacheUser(userId)?.also {
                    cacheUser(it)
                    if (userLocal is RoomUserLocalDataSource) {
                        val personalAvatarPath = resolveStoredPersonalAvatarPath(it.id)
                        userLocal.saveUser(it.toEntity(personalAvatarPath))
                    }
                }
            }
        }
        return try {
            deferred.await()?.let { user ->
                mapUserModel(user, userLocal.getUserFullInfo(userId))
            }
        } finally {
            userRequests.remove(userId)
        }
    }

    override suspend fun getUserFullInfo(userId: Long): UserModel? {
        if (userId <= 0) return null
        val user = userLocal.getUser(userId) ?: fetchAndCacheUser(userId)?.also {
            cacheUser(it)
            if (userLocal is RoomUserLocalDataSource) {
                val personalAvatarPath = resolveStoredPersonalAvatarPath(it.id)
                userLocal.saveUser(it.toEntity(personalAvatarPath))
            }
        } ?: return null

        val cachedFullInfo = userLocal.getUserFullInfo(userId)
        if (cachedFullInfo != null) return mapUserModel(user, cachedFullInfo)

        val dbFullInfo = userLocal.getFullInfoEntity(userId)
        if (dbFullInfo != null) {
            val fullInfo = dbFullInfo.toTdApi()
            cacheUserFullInfo(userId, fullInfo)
            return mapUserModel(user, fullInfo)
        }

        if (isNegativeCached(missingUserFullInfoUntilMs, userId)) {
            return mapUserModel(user, null)
        }

        val deferred = fullInfoRequests.getOrPut(userId) {
            scope.async {
                fetchAndCacheUserFullInfo(userId)?.also {
                    cacheUserFullInfo(userId, it)
                    userLocal.saveFullInfoEntity(it.toEntity(userId))
                    syncUserPersonalAvatarPath(userId, it)
                }
            }
        }
        return try {
            val fullInfo = deferred.await()
            mapUserModel(user, fullInfo)
        } finally {
            fullInfoRequests.remove(userId)
        }
    }

    private suspend fun mapUserModel(user: TdApi.User, fullInfo: TdApi.UserFullInfo?): UserModel {
        val emojiPath = resolveEmojiPath(user)
        val avatarPath = resolveAvatarPath(user)
        val model = user.toDomain(fullInfo, emojiPath)
        return if (avatarPath == null || avatarPath == model.avatarPath) model else model.copy(avatarPath = avatarPath)
    }

    private suspend fun resolveAvatarPath(user: TdApi.User): String? {
        val bigPhoto = user.profilePhoto?.big
        val smallPhoto = user.profilePhoto?.small
        val bigDirectPath = bigPhoto?.local?.path?.ifEmpty { null }
        if (bigDirectPath != null) return bigDirectPath

        val smallDirectPath = smallPhoto?.local?.path?.ifEmpty { null }
        if (smallDirectPath != null) {
            val bigId = bigPhoto?.id?.takeIf { it != 0 }
            if (bigId != null && bigId != smallPhoto.id) {
                fileQueue.enqueue(bigId, avatarHdPrefetchPriority, FileDownloadQueue.DownloadType.DEFAULT, synchronous = false)
            }
            return smallDirectPath
        }

        val resolvedSmallPath = resolveDownloadedFilePath(smallPhoto?.id)
        if (resolvedSmallPath != null) {
            val bigId = bigPhoto?.id?.takeIf { it != 0 }
            if (bigId != null && bigId != smallPhoto?.id) {
                fileQueue.enqueue(bigId, avatarHdPrefetchPriority, FileDownloadQueue.DownloadType.DEFAULT, synchronous = false)
            }
            return resolvedSmallPath
        }

        val resolvedBigPath = resolveDownloadedFilePath(bigPhoto?.id)
        if (resolvedBigPath != null) return resolvedBigPath

        val smallId = smallPhoto?.id?.takeIf { it != 0 }
        val bigId = bigPhoto?.id?.takeIf { it != 0 }
        if (smallId != null) {
            fileQueue.enqueue(smallId, avatarDownloadPriority, FileDownloadQueue.DownloadType.DEFAULT, synchronous = false)
            if (bigId != null && bigId != smallId) {
                fileQueue.enqueue(bigId, avatarHdPrefetchPriority, FileDownloadQueue.DownloadType.DEFAULT, synchronous = false)
            }
        } else if (bigId != null) {
            fileQueue.enqueue(bigId, avatarDownloadPriority, FileDownloadQueue.DownloadType.DEFAULT, synchronous = false)
        }

        return null
    }

    private suspend fun resolveDownloadedFilePath(fileId: Int?): String? {
        if (fileId == null || fileId == 0) return null
        val file = coRunCatching { gateway.execute(TdApi.GetFile(fileId)) }.getOrNull() ?: return null
        return if (file.local.isDownloadingCompleted) file.local.path.ifEmpty { null } else null
    }

    override fun getUserFlow(userId: Long): Flow<UserModel?> = flow {
        if (userId <= 0) {
            emit(null)
            return@flow
        }
        emit(getUser(userId))
        _userUpdateFlow
            .filter { it == userId }
            .collect { emit(getUser(userId)) }
    }

    override suspend fun getUserProfilePhotos(
        userId: Long,
        offset: Int,
        limit: Int,
        ensureFullRes: Boolean
    ): List<String> {
        if (userId <= 0) return emptyList()
        val result = remote.getUserProfilePhotos(userId, offset, limit) ?: return emptyList()
        return coroutineScope {
            result.photos
                .map { photo -> async { resolveUserProfilePhotoPath(photo, ensureFullRes) } }
                .awaitAll()
                .filterNotNull()
        }
    }

    override suspend fun getChatProfilePhotos(
        chatId: Long,
        offset: Int,
        limit: Int,
        ensureFullRes: Boolean
    ): List<String> {
        if (chatId == 0L) return emptyList()
        val paths = loadChatPhotoHistoryPaths(chatId, offset, limit, ensureFullRes)
        if (paths.isNotEmpty()) return paths

        val currentPath = resolveCurrentChatPhotoPath(chatId, ensureFullRes)
        return listOfNotNull(currentPath)
    }

    private suspend fun loadChatPhotoHistoryPaths(
        chatId: Long,
        offset: Int,
        limit: Int,
        ensureFullRes: Boolean
    ): List<String> {
        if (limit <= 0) return emptyList()

        val request = TdApi.SearchChatMessages().apply {
            this.chatId = chatId
            this.query = ""
            this.senderId = null
            this.fromMessageId = 0L
            this.offset = 0
            this.limit = (offset + limit).coerceAtMost(100)
            this.filter = TdApi.SearchMessagesFilterChatPhoto()
        }

        val result = coRunCatching {
            gateway.execute(request) as? TdApi.FoundChatMessages
        }.getOrNull() ?: return emptyList()

        val chatPhotos = result.messages
            .asSequence()
            .mapNotNull { (it.content as? TdApi.MessageChatChangePhoto)?.photo }
            .drop(offset)
            .take(limit)
            .toList()

        if (chatPhotos.isEmpty()) return emptyList()

        return coroutineScope {
            chatPhotos
                .map { photo -> async { resolveUserProfilePhotoPath(photo, ensureFullRes) } }
                .awaitAll()
                .filterNotNull()
                .distinct()
        }
    }

    private suspend fun resolveCurrentChatPhotoPath(chatId: Long, ensureFullRes: Boolean): String? {
        val chat = remote.getChat(chatId)?.also { chatLocal.insertChat(it.toEntity()) }
            ?: chatLocal.getChat(chatId)?.toTdApiChat()
            ?: return null
        return resolveChatPhotoInfoPath(chat.photo, ensureFullRes)
    }

    private suspend fun resolveChatPhotoInfoPath(
        photoInfo: TdApi.ChatPhotoInfo?,
        ensureFullRes: Boolean
    ): String? {
        val smallId = photoInfo?.small?.id?.takeIf { it != 0 }
        val bigId = photoInfo?.big?.id?.takeIf { it != 0 }
        val preferredFile = if (ensureFullRes) {
            photoInfo?.big ?: photoInfo?.small
        } else {
            photoInfo?.small ?: photoInfo?.big
        } ?: return null

        val directPath = preferredFile.local.path.ifEmpty { null }
        if (directPath != null) {
            if (!ensureFullRes && bigId != null && bigId != preferredFile.id) {
                fileQueue.enqueue(bigId, avatarHdPrefetchPriority, FileDownloadQueue.DownloadType.DEFAULT, synchronous = false)
            }
            return directPath
        }

        val downloadedPath = resolveDownloadedFilePath(preferredFile.id)
        if (downloadedPath != null) {
            if (!ensureFullRes && bigId != null && bigId != preferredFile.id) {
                fileQueue.enqueue(bigId, avatarHdPrefetchPriority, FileDownloadQueue.DownloadType.DEFAULT, synchronous = false)
            }
            return downloadedPath
        }

        if (!ensureFullRes) {
            if (smallId != null) {
                fileQueue.enqueue(smallId, avatarDownloadPriority, FileDownloadQueue.DownloadType.DEFAULT, synchronous = false)
                if (bigId != null && bigId != smallId) {
                    fileQueue.enqueue(bigId, avatarHdPrefetchPriority, FileDownloadQueue.DownloadType.DEFAULT, synchronous = false)
                }
            } else if (bigId != null) {
                fileQueue.enqueue(bigId, avatarDownloadPriority, FileDownloadQueue.DownloadType.DEFAULT, synchronous = false)
            }
            return null
        }

        val fileId = preferredFile.id.takeIf { it != 0 } ?: return null
        fileQueue.enqueue(
            fileId = fileId,
            priority = 32,
            type = FileDownloadQueue.DownloadType.DEFAULT,
            synchronous = false,
            ignoreSuppression = true
        )
        withTimeoutOrNull(15_000) {
            coRunCatching { fileQueue.waitForDownload(fileId).await() }
        }
        return resolveDownloadedFilePath(fileId)
    }

    private suspend fun resolveUserProfilePhotoPath(
        photo: TdApi.ChatPhoto,
        ensureFullRes: Boolean
    ): String? {
        val animationFile = photo.animation?.file
        val animationPath = animationFile?.local?.path?.ifEmpty { null }
        if (animationPath != null) return animationPath

        val bestPhotoFile = photo.sizes
            .maxByOrNull { it.width.toLong() * it.height.toLong() }
            ?.photo
            ?: photo.sizes.lastOrNull()?.photo
            ?: return null

        val directPath = bestPhotoFile.local.path.ifEmpty { null }
        if (directPath != null) return directPath

        if (!ensureFullRes) {
            val fallbackFile = photo.sizes.find { it.type == "m" }?.photo
                ?: photo.sizes.find { it.type == "s" }?.photo
                ?: photo.sizes.find { it.type == "c" }?.photo
                ?: photo.sizes.find { it.type == "b" }?.photo
                ?: photo.sizes.find { it.type == "a" }?.photo
                ?: photo.sizes.firstOrNull()?.photo

            val fallbackDirectPath = fallbackFile?.local?.path?.ifEmpty { null }
            if (fallbackDirectPath != null) return fallbackDirectPath

            val fallbackDownloadedPath = resolveDownloadedFilePath(fallbackFile?.id)
            if (fallbackDownloadedPath != null) return fallbackDownloadedPath

            return null
        }

        val fileId = bestPhotoFile.id.takeIf { it != 0 } ?: return null
        fileQueue.enqueue(
            fileId = fileId,
            priority = 32,
            type = FileDownloadQueue.DownloadType.DEFAULT,
            synchronous = false,
            ignoreSuppression = true
        )
        withTimeoutOrNull(15_000) {
            coRunCatching { fileQueue.waitForDownload(fileId).await() }
        }
        return resolveDownloadedFilePath(fileId)
    }

    override fun getUserProfilePhotosFlow(userId: Long): Flow<List<String>> = flow {
        if (userId <= 0) {
            emit(emptyList())
            return@flow
        }
        emit(getUserProfilePhotos(userId))
        updates.file.collect { emit(getUserProfilePhotos(userId)) }
    }

    override fun getChatProfilePhotosFlow(chatId: Long): Flow<List<String>> = flow {
        if (chatId == 0L) {
            emit(emptyList())
            return@flow
        }
        emit(getChatProfilePhotos(chatId))
        updates.file.collect { emit(getChatProfilePhotos(chatId)) }
    }

    override suspend fun getChatFullInfo(chatId: Long): ChatFullInfoModel? {
        if (chatId == 0L) return null

        val chat = remote.getChat(chatId)?.also { chatLocal.insertChat(it.toEntity()) }
            ?: chatLocal.getChat(chatId)?.let { it.toTdApiChat() }

        if (chat != null) {
            val dbFullInfo = chatLocal.getChatFullInfo(chatId)
            return when (val type = chat.type) {
                is TdApi.ChatTypePrivate -> {
                    val userId = type.userId
                    val fullInfo = userLocal.getUserFullInfo(userId) ?: userLocal.getFullInfoEntity(userId)?.let {
                        val info = it.toTdApi()
                        cacheUserFullInfo(userId, info)
                        info
                    } ?: fetchAndCacheUserFullInfo(userId)?.also {
                        cacheUserFullInfo(userId, it)
                        userLocal.saveFullInfoEntity(it.toEntity(userId))
                        syncUserPersonalAvatarPath(userId, it)
                    }
                    fullInfo?.mapUserFullInfoToChat() ?: dbFullInfo?.toDomain()
                }

                is TdApi.ChatTypeSupergroup -> {
                    val fullInfo = remote.getSupergroupFullInfo(type.supergroupId)
                    val supergroup = remote.getSupergroup(type.supergroupId)
                    fullInfo?.let {
                        chatLocal.insertChatFullInfo(it.toEntity(chatId))
                    }
                    fullInfo?.mapSupergroupFullInfoToChat(supergroup) ?: dbFullInfo?.toDomain()
                }

                is TdApi.ChatTypeBasicGroup -> {
                    val fullInfo = remote.getBasicGroupFullInfo(type.basicGroupId)
                    fullInfo?.let {
                        chatLocal.insertChatFullInfo(it.toEntity(chatId))
                    }
                    fullInfo?.mapBasicGroupFullInfoToChat() ?: dbFullInfo?.toDomain()
                }

                else -> dbFullInfo?.toDomain()
            }
        }

        val userId = chatId
        val fullInfo = userLocal.getUserFullInfo(userId) ?: userLocal.getFullInfoEntity(userId)?.let {
            val info = it.toTdApi()
            cacheUserFullInfo(userId, info)
            info
        } ?: fetchAndCacheUserFullInfo(userId)?.also {
            cacheUserFullInfo(userId, it)
            userLocal.saveFullInfoEntity(it.toEntity(userId))
            syncUserPersonalAvatarPath(userId, it)
        }
        return fullInfo?.mapUserFullInfoToChat()
    }

    private suspend fun resolveStoredPersonalAvatarPath(userId: Long): String? {
        val cachedFullInfo = userLocal.getUserFullInfo(userId)
        val cachedPath = cachedFullInfo?.extractPersonalAvatarPath()
        if (!cachedPath.isNullOrBlank()) return cachedPath
        return userLocal.getFullInfoEntity(userId)?.personalPhotoPath?.ifBlank { null }
    }

    private suspend fun syncUserPersonalAvatarPath(userId: Long, fullInfo: TdApi.UserFullInfo) {
        val roomUserLocal = userLocal as? RoomUserLocalDataSource ?: return
        val personalAvatarPath = fullInfo.extractPersonalAvatarPath() ?: return
        val existing = roomUserLocal.loadUser(userId) ?: return
        if (existing.personalAvatarPath == personalAvatarPath) return
        roomUserLocal.saveUser(existing.copy(personalAvatarPath = personalAvatarPath))
    }

    private fun TdApi.UserFullInfo.extractPersonalAvatarPath(): String? {
        val bestPhotoSize = personalPhoto?.sizes?.maxByOrNull { it.width.toLong() * it.height.toLong() }
            ?: personalPhoto?.sizes?.lastOrNull()
        return personalPhoto?.animation?.file?.local?.path?.ifEmpty { null }
            ?: bestPhotoSize?.photo?.local?.path?.ifEmpty { null }
    }

    private suspend fun fetchAndCacheUser(userId: Long): TdApi.User? {
        if (userId <= 0 || isNegativeCached(missingUsersUntilMs, userId)) return null
        val user = remote.getUser(userId)
        if (user != null) {
            missingUsersUntilMs.remove(userId)
        } else {
            rememberNegative(missingUsersUntilMs, userId)
        }
        return user
    }

    private suspend fun fetchAndCacheUserFullInfo(userId: Long): TdApi.UserFullInfo? {
        if (userId <= 0 || isNegativeCached(missingUserFullInfoUntilMs, userId)) return null
        val info = remote.getUserFullInfo(userId)
        if (info != null) {
            missingUserFullInfoUntilMs.remove(userId)
        } else {
            rememberNegative(missingUserFullInfoUntilMs, userId)
        }
        return info
    }

    private fun isNegativeCached(cache: ConcurrentHashMap<Long, Long>, id: Long): Boolean {
        val until = cache[id] ?: return false
        if (until > System.currentTimeMillis()) return true
        cache.remove(id, until)
        return false
    }

    private fun rememberNegative(cache: ConcurrentHashMap<Long, Long>, id: Long) {
        cache[id] = System.currentTimeMillis() + NEGATIVE_CACHE_TTL_MS
    }

    private suspend fun cacheUser(user: TdApi.User) {
        userLocal.putUser(user)
        chatCache.putUser(user)
    }

    private suspend fun cacheUserFullInfo(userId: Long, info: TdApi.UserFullInfo) {
        userLocal.putUserFullInfo(userId, info)
        chatCache.putUserFullInfo(userId, info)
    }

    override suspend fun getContacts(): List<UserModel> {
        val result = remote.getContacts() ?: return emptyList()
        return result.userIds.map { scope.async { getUser(it) } }.awaitAll().filterNotNull()
    }

    override suspend fun searchContacts(query: String): List<UserModel> {
        val result = remote.searchContacts(query) ?: return emptyList()
        return result.userIds.map { scope.async { getUser(it) } }.awaitAll().filterNotNull()
    }

    override suspend fun addContact(user: UserModel) {
        val contact = TdApi.ImportedContact(
            user.phoneNumber.orEmpty(),
            user.firstName,
            user.lastName.orEmpty(),
            null
        )
        remote.addContact(user.id, contact, true)

        remote.getUser(user.id)?.let { refreshedUser ->
            cacheUser(refreshedUser)
            if (userLocal is RoomUserLocalDataSource) {
                val personalAvatarPath = resolveStoredPersonalAvatarPath(refreshedUser.id)
                userLocal.saveUser(refreshedUser.toEntity(personalAvatarPath))
            }
        }

        _userUpdateFlow.emit(user.id)
    }

    override suspend fun removeContact(userId: Long) {
        remote.removeContacts(longArrayOf(userId))
        _userUpdateFlow.emit(userId)
    }

    override suspend fun searchPublicChat(username: String): ChatModel? {
        val chat = remote.searchPublicChat(username) ?: return null
        chatLocal.insertChat(chat.toEntity())
        return chat.toDomain()
    }

    override suspend fun getChatMembers(
        chatId: Long,
        offset: Int,
        limit: Int,
        filter: ChatMembersFilter
    ): List<GroupMemberModel> {
        val chat = remote.getChat(chatId) ?: return emptyList()
        val members: List<TdApi.ChatMember> = when (val type = chat.type) {
            is TdApi.ChatTypeSupergroup -> {
                val tdFilter = filter.toApi()
                remote.getSupergroupMembers(type.supergroupId, tdFilter, offset, limit)
                    ?.members?.toList() ?: emptyList()
            }
            is TdApi.ChatTypeBasicGroup -> {
                if (offset > 0) return emptyList()
                val fullInfo = remote.getBasicGroupMembers(type.basicGroupId) ?: return emptyList()
                fullInfo.members.filter { member ->
                    when (filter) {
                        is ChatMembersFilter.Administrators ->
                            member.status is TdApi.ChatMemberStatusAdministrator ||
                                    member.status is TdApi.ChatMemberStatusCreator
                        else -> true
                    }
                }
            }
            else -> emptyList()
        }

        return members.map { member ->
            scope.async {
                val sender = member.memberId as? TdApi.MessageSenderUser ?: return@async null
                val user = getUser(sender.userId) ?: return@async null
                member.toDomain(user)
            }
        }.awaitAll().filterNotNull()
    }

    override suspend fun getChatMember(chatId: Long, userId: Long): GroupMemberModel? {
        val member = remote.getChatMember(chatId, userId) ?: return null
        val user = getUser(userId) ?: return null
        return member.toDomain(user)
    }

    override suspend fun setChatMemberStatus(
        chatId: Long,
        userId: Long,
        status: ChatMemberStatus
    ) {
        remote.setChatMemberStatus(chatId, userId, status.toApi())
        _userUpdateFlow.emit(userId)
    }

    override suspend fun getPremiumState(): PremiumStateModel? {
        val state = remote.getPremiumState() ?: return null
        return state.toDomain()
    }

    override suspend fun getPremiumFeatures(source: PremiumSource): List<PremiumFeatureType> {
        val tdSource = source.toApi() ?: return emptyList()
        val result = remote.getPremiumFeatures(tdSource) ?: return emptyList()
        return result.features.map { it.toDomain() }
    }

    override suspend fun getPremiumLimit(limitType: PremiumLimitType): Int {
        val tdType = limitType.toApi() ?: return 0
        return remote.getPremiumLimit(tdType)?.premiumValue ?: 0
    }

    override suspend fun getBotCommands(botId: Long): List<BotCommandModel> {
        val fullInfo = remote.getBotFullInfo(botId) ?: return emptyList()
        return fullInfo.botInfo?.commands?.map {
            BotCommandModel(it.command, it.description)
        } ?: emptyList()
    }

    override suspend fun getBotInfo(botId: Long): BotInfoModel? {
        val fullInfo = remote.getBotFullInfo(botId) ?: return null
        val commands = fullInfo.botInfo?.commands?.map {
            BotCommandModel(it.command, it.description)
        } ?: emptyList()
        val menuButton = when (val btn = fullInfo.botInfo?.menuButton) {
            is TdApi.BotMenuButton -> BotMenuButtonModel.WebApp(btn.text, btn.url)
            else -> BotMenuButtonModel.Default
        }
        return BotInfoModel(commands, menuButton)
    }

    override suspend fun getChatStatistics(chatId: Long, isDark: Boolean): ChatStatisticsModel? {
        val stats = remote.getChatStatistics(chatId, isDark) ?: return null
        return stats.toDomain()
    }

    override suspend fun getChatRevenueStatistics(
        chatId: Long,
        isDark: Boolean
    ): ChatRevenueStatisticsModel? {
        val stats = remote.getChatRevenueStatistics(chatId, isDark) ?: return null
        return stats.toDomain()
    }

    override suspend fun loadStatisticsGraph(
        chatId: Long,
        token: String,
        x: Long
    ): StatisticsGraphModel? {
        val graph = remote.getStatisticsGraph(chatId, token, x) ?: return null
        return graph.toDomain()
    }

    override fun logOut() {
        scope.launch { coRunCatching { remote.logout() } }
        scope.launch { userLocal.clearAll() }
        if (userLocal is RoomUserLocalDataSource) {
            scope.launch { userLocal.clearDatabase() }
        }
        scope.launch {
            coRunCatching { keyValueDao.deleteValue(KEY_CURRENT_USER_ID) }
            currentUserId = 0L
            _currentUserFlow.value = null
        }
        scope.launch { chatLocal.clearAll() }
    }

    override suspend fun setName(firstName: String, lastName: String) =
        remote.setName(firstName, lastName)

    override suspend fun setBio(bio: String) =
        remote.setBio(bio)

    override suspend fun setUsername(username: String) =
        remote.setUsername(username)

    override suspend fun setEmojiStatus(customEmojiId: Long?) =
        remote.setEmojiStatus(customEmojiId)

    override suspend fun setProfilePhoto(path: String) =
        remote.setProfilePhoto(path)

    override suspend fun setBirthdate(birthdate: BirthdateModel?) =
        remote.setBirthdate(birthdate?.let { TdApi.Birthdate(it.day, it.month, it.year ?: 0) })

    override suspend fun setPersonalChat(chatId: Long) =
        remote.setPersonalChat(chatId)

    override suspend fun setBusinessBio(bio: String) =
        remote.setBusinessBio(bio)

    override suspend fun setBusinessLocation(address: String, latitude: Double, longitude: Double) =
        remote.setBusinessLocation(
            if (address.isNotEmpty()) TdApi.BusinessLocation(
                TdApi.Location(latitude, longitude, 0.0), address
            ) else null
        )

    override suspend fun setBusinessOpeningHours(openingHours: BusinessOpeningHoursModel?) =
        remote.setBusinessOpeningHours(
            openingHours?.let {
                TdApi.BusinessOpeningHours(
                    it.timeZoneId,
                    it.intervals.map { interval ->
                        TdApi.BusinessOpeningHoursInterval(interval.startMinute, interval.endMinute)
                    }.toTypedArray()
                )
            }
        )

    override suspend fun toggleUsernameIsActive(username: String, isActive: Boolean) =
        remote.toggleUsernameIsActive(username, isActive)

    override suspend fun reorderActiveUsernames(usernames: List<String>) =
        remote.reorderActiveUsernames(usernames.toTypedArray())

    override fun forceSponsorSync() {
        sponsorSyncManager.forceSync()
    }

    private fun TdApi.Chat.toEntity(): org.monogram.data.db.model.ChatEntity {
        val isChannel = (type as? TdApi.ChatTypeSupergroup)?.isChannel ?: false
        val isArchived = positions.any { it.list is TdApi.ChatListArchive }
        val permissions = permissions ?: TdApi.ChatPermissions()
        val cachedCounts = parseCachedCounts(clientData)
        val senderId = when (val sender = messageSenderId) {
            is TdApi.MessageSenderUser -> sender.userId
            is TdApi.MessageSenderChat -> sender.chatId
            else -> null
        }
        val privateUserId = (type as? TdApi.ChatTypePrivate)?.userId ?: 0L
        val basicGroupId = (type as? TdApi.ChatTypeBasicGroup)?.basicGroupId ?: 0L
        val supergroupId = (type as? TdApi.ChatTypeSupergroup)?.supergroupId ?: 0L
        val secretChatId = (type as? TdApi.ChatTypeSecret)?.secretChatId ?: 0
        return org.monogram.data.db.model.ChatEntity(
            id = id,
            title = title,
            unreadCount = unreadCount,
            avatarPath = photo?.small?.local?.path,
            lastMessageText = (lastMessage?.content as? TdApi.MessageText)?.text?.text ?: "",
            lastMessageTime = (lastMessage?.date?.toLong() ?: 0L).toString(),
            lastMessageDate = lastMessage?.date?.toLong() ?: 0L,
            order = positions.firstOrNull()?.order ?: 0L,
            isPinned = positions.firstOrNull()?.isPinned ?: false,
            isMuted = notificationSettings.muteFor > 0,
            isChannel = isChannel,
            isGroup = type is TdApi.ChatTypeBasicGroup || (type is TdApi.ChatTypeSupergroup && !isChannel),
            type = when (type) {
                is TdApi.ChatTypePrivate -> "PRIVATE"
                is TdApi.ChatTypeBasicGroup -> "BASIC_GROUP"
                is TdApi.ChatTypeSupergroup -> "SUPERGROUP"
                is TdApi.ChatTypeSecret -> "SECRET"
                else -> "PRIVATE"
            },
            privateUserId = privateUserId,
            basicGroupId = basicGroupId,
            supergroupId = supergroupId,
            secretChatId = secretChatId,
            positionsCache = encodePositions(positions),
            isArchived = isArchived,
            memberCount = cachedCounts.first,
            onlineCount = cachedCounts.second,
            unreadMentionCount = unreadMentionCount,
            unreadReactionCount = unreadReactionCount,
            isMarkedAsUnread = isMarkedAsUnread,
            hasProtectedContent = hasProtectedContent,
            isTranslatable = isTranslatable,
            hasAutomaticTranslation = false,
            messageAutoDeleteTime = messageAutoDeleteTime,
            canBeDeletedOnlyForSelf = canBeDeletedOnlyForSelf,
            canBeDeletedForAllUsers = canBeDeletedForAllUsers,
            canBeReported = canBeReported,
            lastReadInboxMessageId = lastReadInboxMessageId,
            lastReadOutboxMessageId = lastReadOutboxMessageId,
            lastMessageId = lastMessage?.id ?: 0L,
            isLastMessageOutgoing = lastMessage?.isOutgoing ?: false,
            replyMarkupMessageId = replyMarkupMessageId,
            messageSenderId = senderId,
            blockList = blockList != null,
            emojiStatusId = (emojiStatus?.type as? TdApi.EmojiStatusTypeCustomEmoji)?.customEmojiId,
            accentColorId = accentColorId,
            profileAccentColorId = profileAccentColorId,
            backgroundCustomEmojiId = backgroundCustomEmojiId,
            photoId = photo?.small?.id ?: 0,
            isSupergroup = type is TdApi.ChatTypeSupergroup,
            isAdmin = false,
            isOnline = false,
            typingAction = null,
            draftMessage = (draftMessage?.inputMessageText as? TdApi.InputMessageText)?.text?.text,
            isVerified = false,
            viewAsTopics = viewAsTopics,
            isForum = false,
            isBot = false,
            isMember = true,
            username = null,
            description = null,
            inviteLink = null,
            permissionCanSendBasicMessages = permissions.canSendBasicMessages,
            permissionCanSendAudios = permissions.canSendAudios,
            permissionCanSendDocuments = permissions.canSendDocuments,
            permissionCanSendPhotos = permissions.canSendPhotos,
            permissionCanSendVideos = permissions.canSendVideos,
            permissionCanSendVideoNotes = permissions.canSendVideoNotes,
            permissionCanSendVoiceNotes = permissions.canSendVoiceNotes,
            permissionCanSendPolls = permissions.canSendPolls,
            permissionCanSendOtherMessages = permissions.canSendOtherMessages,
            permissionCanAddLinkPreviews = permissions.canAddLinkPreviews,
            permissionCanEditTag = permissions.canEditTag,
            permissionCanChangeInfo = permissions.canChangeInfo,
            permissionCanInviteUsers = permissions.canInviteUsers,
            permissionCanPinMessages = permissions.canPinMessages,
            permissionCanCreateTopics = permissions.canCreateTopics,
            createdAt = System.currentTimeMillis()
        )
    }

    private fun encodePositions(positions: Array<TdApi.ChatPosition>): String? {
        if (positions.isEmpty()) return null
        val encoded = positions.mapNotNull { pos ->
            if (pos.order == 0L) return@mapNotNull null
            val pinned = if (pos.isPinned) 1 else 0
            when (val list = pos.list) {
                is TdApi.ChatListMain -> "m:${pos.order}:$pinned"
                is TdApi.ChatListArchive -> "a:${pos.order}:$pinned"
                is TdApi.ChatListFolder -> "f:${list.chatFolderId}:${pos.order}:$pinned"
                else -> null
            }
        }
        return if (encoded.isEmpty()) null else encoded.joinToString("|")
    }

    private fun TdApi.User.toEntity(personalAvatarPath: String?): org.monogram.data.db.model.UserEntity {
        val usernamesData = buildString {
            append(usernames?.activeUsernames?.joinToString("|").orEmpty())
            append('\n')
            append(usernames?.disabledUsernames?.joinToString("|").orEmpty())
            append('\n')
            append(usernames?.editableUsername.orEmpty())
            append('\n')
            append(usernames?.collectibleUsernames?.joinToString("|").orEmpty())
        }

        val statusType = when (status) {
            is TdApi.UserStatusOnline -> "ONLINE"
            is TdApi.UserStatusRecently -> "RECENTLY"
            is TdApi.UserStatusLastWeek -> "LAST_WEEK"
            is TdApi.UserStatusLastMonth -> "LAST_MONTH"
            else -> "OFFLINE"
        }

        val statusEmojiId = when (val type = emojiStatus?.type) {
            is TdApi.EmojiStatusTypeCustomEmoji -> type.customEmojiId
            is TdApi.EmojiStatusTypeUpgradedGift -> type.modelCustomEmojiId
            else -> 0L
        }

        return org.monogram.data.db.model.UserEntity(
            id = id,
            firstName = firstName,
            lastName = lastName.ifEmpty { null },
            phoneNumber = phoneNumber.ifEmpty { null },
            avatarPath = profilePhoto?.small?.local?.path?.ifEmpty { null },
            personalAvatarPath = personalAvatarPath,
            isPremium = isPremium,
            isVerified = verificationStatus?.isVerified ?: false,
            isSupport = isSupport,
            isContact = isContact,
            isMutualContact = isMutualContact,
            isCloseFriend = isCloseFriend,
            haveAccess = haveAccess,
            username = usernames?.activeUsernames?.firstOrNull(),
            usernamesData = usernamesData,
            statusType = statusType,
            accentColorId = accentColorId,
            profileAccentColorId = profileAccentColorId,
            statusEmojiId = statusEmojiId,
            languageCode = languageCode.ifEmpty { null },
            lastSeen = (status as? TdApi.UserStatusOffline)?.wasOnline?.toLong() ?: 0L,
            createdAt = System.currentTimeMillis()
        )
    }

    private fun parseCachedCounts(clientData: String?): Pair<Int, Int> {
        if (clientData.isNullOrBlank()) return 0 to 0
        val memberCount = Regex("""mc:(\d+)""").find(clientData)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        val onlineCount = Regex("""oc:(\d+)""").find(clientData)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        return memberCount to onlineCount
    }

    companion object {
        private const val NEGATIVE_CACHE_TTL_MS = 5 * 60 * 1000L
        private const val KEY_CURRENT_USER_ID = "current_user_id"
    }
}
