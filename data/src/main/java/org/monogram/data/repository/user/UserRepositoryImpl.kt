package org.monogram.data.repository.user

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import org.monogram.core.ScopeProvider
import org.monogram.data.chats.ChatCache
import org.monogram.data.core.coRunCatching
import org.monogram.data.datasource.cache.ChatLocalDataSource
import org.monogram.data.datasource.cache.UserLocalDataSource
import org.monogram.data.datasource.remote.UserRemoteDataSource
import org.monogram.data.db.dao.KeyValueDao
import org.monogram.data.db.model.KeyValueEntity
import org.monogram.data.gateway.TelegramGateway
import org.monogram.data.gateway.UpdateDispatcher
import org.monogram.data.infra.FileDownloadQueue
import org.monogram.data.mapper.user.*
import org.monogram.domain.models.ChatFullInfoModel
import org.monogram.domain.models.UserModel
import org.monogram.domain.repository.CacheProvider
import org.monogram.domain.repository.UserRepository
import java.util.concurrent.ConcurrentHashMap

class UserRepositoryImpl(
    private val remote: UserRemoteDataSource,
    private val userLocal: UserLocalDataSource,
    private val chatLocal: ChatLocalDataSource,
    private val chatCache: ChatCache,
    gateway: TelegramGateway,
    private val updates: UpdateDispatcher,
    fileQueue: FileDownloadQueue,
    private val keyValueDao: KeyValueDao,
    private val cacheProvider: CacheProvider,
    scopeProvider: ScopeProvider
) : UserRepository {

    private val scope = scopeProvider.appScope
    private val mediaResolver = UserMediaResolver(gateway = gateway, fileQueue = fileQueue)
    private var currentUserId: Long = 0L
    private val userRequests = ConcurrentHashMap<Long, Deferred<TdApi.User?>>()
    private val fullInfoRequests = ConcurrentHashMap<Long, Deferred<TdApi.UserFullInfo?>>()
    private val missingUsersUntilMs = ConcurrentHashMap<Long, Long>()
    private val missingUserFullInfoUntilMs = ConcurrentHashMap<Long, Long>()

    private val _currentUserFlow = MutableStateFlow<UserModel?>(null)
    override val currentUserFlow = _currentUserFlow.asStateFlow()

    private val _userUpdateFlow = MutableSharedFlow<Long>(
        extraBufferCapacity = USER_UPDATE_BUFFER_SIZE,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val anyUserUpdateFlow = _userUpdateFlow.asSharedFlow()

    init {
        scope.launch {
            restoreCurrentUserFromLocal()
        }

        UserUpdateSynchronizer(
            scope = scope,
            updates = updates,
            userLocal = userLocal,
            keyValueDao = keyValueDao,
            emojiPathCache = mediaResolver.emojiPathCache,
            fileIdToUserIdMap = mediaResolver.fileIdToUserIdMap,
            onUserUpdated = { user -> handleUserUpdated(user) },
            onUserIdChanged = { userId -> handleUserIdUpdated(userId) },
            onCachedSimCountryIsoChanged = { iso -> cacheProvider.setCachedSimCountryIso(iso) }
        ).start()
    }

    private suspend fun handleUserUpdated(user: TdApi.User) {
        cacheUser(user)
        handleUserIdUpdated(user.id)
    }

    private suspend fun handleUserIdUpdated(userId: Long) {
        if (userId == currentUserId) refreshCurrentUser()
        _userUpdateFlow.emit(userId)
    }

    private suspend fun restoreCurrentUserFromLocal() {
        val cachedUserId = keyValueDao.getValue(KEY_CURRENT_USER_ID)?.value?.toLongOrNull() ?: return
        if (cachedUserId <= 0L) return

        currentUserId = cachedUserId
        val user = userLocal.getUser(cachedUserId) ?: return
        val model = mapUserModel(user, userLocal.getUserFullInfo(cachedUserId))
        _currentUserFlow.value = model
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
        val model = mapUserModel(user, userLocal.getUserFullInfo(user.id))
        _currentUserFlow.update { model }
        return model
    }

    override suspend fun getUser(userId: Long): UserModel? {
        if (userId <= 0) return null
        userLocal.getUser(userId)?.let {
            return mapUserModel(it, userLocal.getUserFullInfo(userId))
        }

        userLocal.loadUser(userId)?.let { entity ->
            val user = entity.toTdApi()
            cacheUser(user)
            return mapUserModel(user, userLocal.getUserFullInfo(userId))
        }

        if (isNegativeCached(missingUsersUntilMs, userId)) return null

        val deferred = userRequests.getOrPut(userId) {
            scope.async {
                fetchAndCacheUser(userId)?.also {
                    cacheUser(it)
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

    override suspend fun resolveUserChatFullInfo(userId: Long): ChatFullInfoModel? {
        if (userId <= 0L) return null
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

    private suspend fun mapUserModel(user: TdApi.User, fullInfo: TdApi.UserFullInfo?): UserModel {
        val emojiPath = mediaResolver.resolveEmojiPath(user)
        val avatarPath = mediaResolver.resolveAvatarPath(user)
        val model = user.toDomain(fullInfo, emojiPath)
        return if (avatarPath == null || avatarPath == model.avatarPath) model else model.copy(avatarPath = avatarPath)
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

    private suspend fun syncUserPersonalAvatarPath(userId: Long, fullInfo: TdApi.UserFullInfo) {
        val personalAvatarPath = fullInfo.extractPersonalAvatarPath() ?: return
        val existing = userLocal.loadUser(userId) ?: return
        if (existing.personalAvatarPath == personalAvatarPath) return
        userLocal.saveUser(existing.copy(personalAvatarPath = personalAvatarPath))
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
        }

        _userUpdateFlow.emit(user.id)
    }

    override suspend fun removeContact(userId: Long) {
        remote.removeContacts(longArrayOf(userId))
        _userUpdateFlow.emit(userId)
    }

    override suspend fun setCachedSimCountryIso(iso: String?) {
        if (iso != null) {
            keyValueDao.insertValue(KeyValueEntity(KEY_CACHED_SIM_COUNTRY_ISO, iso))
        } else {
            keyValueDao.deleteValue(KEY_CACHED_SIM_COUNTRY_ISO)
        }
    }

    override fun logOut() {
        scope.launch { coRunCatching { remote.logout() } }
        scope.launch { userLocal.clearAll() }
        scope.launch { userLocal.clearDatabase() }
        scope.launch {
            coRunCatching { keyValueDao.deleteValue(KEY_CURRENT_USER_ID) }
            currentUserId = 0L
            _currentUserFlow.value = null
        }
        scope.launch { chatLocal.clearAll() }
    }

    companion object {
        private const val NEGATIVE_CACHE_TTL_MS = 5 * 60 * 1000L
        private const val KEY_CURRENT_USER_ID = "current_user_id"
        private const val KEY_CACHED_SIM_COUNTRY_ISO = "cached_sim_country_iso"
        private const val USER_UPDATE_BUFFER_SIZE = 10
    }
}