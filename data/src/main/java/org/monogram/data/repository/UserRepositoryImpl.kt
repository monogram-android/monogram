package org.monogram.data.repository

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import org.monogram.core.ScopeProvider
import org.monogram.data.datasource.cache.ChatLocalDataSource
import org.monogram.data.datasource.cache.RoomUserLocalDataSource
import org.monogram.data.datasource.cache.UserLocalDataSource
import org.monogram.data.datasource.remote.UserRemoteDataSource
import org.monogram.data.gateway.UpdateDispatcher
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
    private val updates: UpdateDispatcher,
    scopeProvider: ScopeProvider
) : UserRepository {

    private val scope = scopeProvider.appScope
    private var currentUserId: Long = 0L
    private val userRequests = ConcurrentHashMap<Long, Deferred<TdApi.User?>>()
    private val fullInfoRequests = ConcurrentHashMap<Long, Deferred<TdApi.UserFullInfo?>>()

    private val _currentUserFlow = MutableStateFlow<UserModel?>(null)
    override val currentUserFlow = _currentUserFlow.asStateFlow()

    private val _userUpdateFlow = MutableSharedFlow<Long>(
        extraBufferCapacity = 10,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    init {
        scope.launch {
            updates.user.collect { update ->
                userLocal.putUser(update.user)
                if (userLocal is RoomUserLocalDataSource) {
                    userLocal.saveUser(update.user.toEntity())
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
                        userLocal.saveUser(cached.toEntity())
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
                    // Check if this file is a user profile photo
                    userLocal.getAllUsers().forEach { user ->
                        val small = user.profilePhoto?.small
                        val big = user.profilePhoto?.big
                        if (small?.id == file.id || big?.id == file.id) {
                            _userUpdateFlow.emit(user.id)
                            if (user.id == currentUserId) refreshCurrentUser()
                        }
                    }
                }
            }
        }
    }

    private suspend fun refreshCurrentUser() {
        val user = userLocal.getUser(currentUserId) ?: return
        _currentUserFlow.update { user.toDomain(userLocal.getUserFullInfo(currentUserId)) }
    }

    override suspend fun getMe(): UserModel {
        val user = remote.getMe() ?: return UserModel(0, "Error")
        currentUserId = user.id
        userLocal.putUser(user)
        if (userLocal is RoomUserLocalDataSource) {
            userLocal.saveUser(user.toEntity())
        }
        val model = user.toDomain(userLocal.getUserFullInfo(user.id))
        _currentUserFlow.update { model }
        return model
    }

    override suspend fun getUser(userId: Long): UserModel? {
        if (userId <= 0) return null
        userLocal.getUser(userId)?.let {
            return it.toDomain(userLocal.getUserFullInfo(userId))
        }

        if (userLocal is RoomUserLocalDataSource) {
            userLocal.loadUser(userId)?.let { entity ->
                val user = entity.toTdApi()
                userLocal.putUser(user)
                return user.toDomain(userLocal.getUserFullInfo(userId))
            }
        }

        val deferred = userRequests.getOrPut(userId) {
            scope.async {
                remote.getUser(userId)?.also {
                    userLocal.putUser(it)
                    if (userLocal is RoomUserLocalDataSource) {
                        userLocal.saveUser(it.toEntity())
                    }
                }
            }
        }
        return try {
            deferred.await()?.toDomain(userLocal.getUserFullInfo(userId))
        } finally {
            userRequests.remove(userId)
        }
    }

    override suspend fun getUserFullInfo(userId: Long): UserModel? {
        if (userId <= 0) return null
        val user = userLocal.getUser(userId) ?: remote.getUser(userId)?.also {
            userLocal.putUser(it)
            if (userLocal is RoomUserLocalDataSource) {
                userLocal.saveUser(it.toEntity())
            }
        } ?: return null

        val cachedFullInfo = userLocal.getUserFullInfo(userId)
        if (cachedFullInfo != null) return user.toDomain(cachedFullInfo)

        val dbFullInfo = userLocal.getFullInfoEntity(userId)
        if (dbFullInfo != null) {
            val fullInfo = dbFullInfo.toTdApi()
            userLocal.putUserFullInfo(userId, fullInfo)
            return user.toDomain(fullInfo)
        }

        val deferred = fullInfoRequests.getOrPut(userId) {
            scope.async {
                remote.getUserFullInfo(userId)?.also {
                    userLocal.putUserFullInfo(userId, it)
                    userLocal.saveFullInfoEntity(it.toEntity(userId))
                }
            }
        }
        return try {
            val fullInfo = deferred.await()
            user.toDomain(fullInfo)
        } finally {
            fullInfoRequests.remove(userId)
        }
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

    override suspend fun getUserProfilePhotos(userId: Long, offset: Int, limit: Int): List<String> {
        if (userId <= 0) return emptyList()
        val result = remote.getUserProfilePhotos(userId, offset, limit) ?: return emptyList()
        return result.photos.mapNotNull { photo ->
            val big = photo.sizes.lastOrNull()
            val animation = photo.animation
            when {
                animation != null && animation.file.local.path.isNotEmpty() -> animation.file.local.path
                big != null && big.photo.local.path.isNotEmpty() -> big.photo.local.path
                else -> null
            }
        }
    }

    override fun getUserProfilePhotosFlow(userId: Long): Flow<List<String>> = flow {
        if (userId <= 0) {
            emit(emptyList())
            return@flow
        }
        emit(getUserProfilePhotos(userId))
        updates.file.collect { emit(getUserProfilePhotos(userId)) }
    }

    override suspend fun getChatFullInfo(chatId: Long): ChatFullInfoModel? {
        if (chatId > 0) {
            val fullInfo = userLocal.getUserFullInfo(chatId) ?: userLocal.getFullInfoEntity(chatId)?.let {
                val info = it.toTdApi()
                userLocal.putUserFullInfo(chatId, info)
                info
            } ?: remote.getUserFullInfo(chatId)?.also {
                userLocal.putUserFullInfo(chatId, it)
                userLocal.saveFullInfoEntity(it.toEntity(chatId))
            }
            return fullInfo?.mapUserFullInfoToChat()
        }

        // It's a group or channel (chatId < 0)
        val chat = remote.getChat(chatId)?.also { chatLocal.insertChat(it.toEntity()) }
            ?: chatLocal.getChat(chatId)?.let { it.toTdApiChat() }
            ?: return null

        val dbFullInfo = chatLocal.getChatFullInfo(chatId)
        if (dbFullInfo != null) {
            return dbFullInfo.toDomain()
        }

        return when (val type = chat.type) {
            is TdApi.ChatTypeSupergroup -> {
                val fullInfo = remote.getSupergroupFullInfo(type.supergroupId)
                val supergroup = remote.getSupergroup(type.supergroupId)
                fullInfo?.let {
                    chatLocal.insertChatFullInfo(it.toEntity(chatId))
                }
                fullInfo?.mapSupergroupFullInfoToChat(supergroup)
            }
            is TdApi.ChatTypeBasicGroup -> {
                val fullInfo = remote.getBasicGroupFullInfo(type.basicGroupId)
                fullInfo?.let {
                    chatLocal.insertChatFullInfo(it.toEntity(chatId))
                }
                fullInfo?.mapBasicGroupFullInfoToChat()
            }
            else -> null
        }
    }

    override suspend fun getContacts(): List<UserModel> {
        val result = remote.getContacts() ?: return emptyList()
        return result.userIds.map { scope.async { getUser(it) } }.awaitAll().filterNotNull()
    }

    override suspend fun searchContacts(query: String): List<UserModel> {
        val result = remote.searchContacts(query) ?: return emptyList()
        return result.userIds.map { scope.async { getUser(it) } }.awaitAll().filterNotNull()
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
        scope.launch { runCatching { remote.logout() } }
        scope.launch { userLocal.clearAll() }
        if (userLocal is RoomUserLocalDataSource) {
            scope.launch { userLocal.clearDatabase() }
        }
        scope.launch { chatLocal.clearAllChats() }
    }

    override suspend fun setName(firstName: String, lastName: String) =
        remote.setName(firstName, lastName)

    override suspend fun setBio(bio: String) =
        remote.setBio(bio)

    override suspend fun setUsername(username: String) =
        remote.setUsername(username)

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

    private fun TdApi.Chat.toEntity(): org.monogram.data.db.model.ChatEntity {
        val isChannel = (type as? TdApi.ChatTypeSupergroup)?.isChannel ?: false
        val isArchived = positions.any { it.list is TdApi.ChatListArchive }
        return org.monogram.data.db.model.ChatEntity(
            id = id,
            title = title,
            unreadCount = unreadCount,
            avatarPath = photo?.small?.local?.path,
            lastMessageText = (lastMessage?.content as? TdApi.MessageText)?.text?.text ?: "",
            lastMessageTime = (lastMessage?.date?.toLong() ?: 0L).toString(),
            order = 0L,
            isPinned = false,
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
            isArchived = isArchived,
            memberCount = 0,
            onlineCount = 0,
            createdAt = System.currentTimeMillis()
        )
    }

    private fun TdApi.User.toEntity(): org.monogram.data.db.model.UserEntity {
        return org.monogram.data.db.model.UserEntity(
            id = id,
            firstName = firstName,
            lastName = lastName.ifEmpty { null },
            phoneNumber = phoneNumber.ifEmpty { null },
            avatarPath = profilePhoto?.small?.local?.path?.ifEmpty { null },
            isPremium = isPremium,
            isVerified = verificationStatus?.isVerified ?: false,
            username = usernames?.activeUsernames?.firstOrNull(),
            lastSeen = (status as? TdApi.UserStatusOffline)?.wasOnline?.toLong() ?: 0L,
            createdAt = System.currentTimeMillis()
        )
    }
}