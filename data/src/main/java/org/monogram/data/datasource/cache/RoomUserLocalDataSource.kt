package org.monogram.data.datasource.cache

import org.drinkless.tdlib.TdApi
import org.monogram.data.db.dao.UserDao
import org.monogram.data.db.dao.UserFullInfoDao
import org.monogram.data.db.model.UserEntity
import org.monogram.data.db.model.UserFullInfoEntity
import org.monogram.data.infra.SynchronizedLruMap
import org.monogram.data.mapper.user.extractPersonalAvatarPath
import org.monogram.data.mapper.user.toEntity
import org.monogram.data.mapper.user.toTdApi

class RoomUserLocalDataSource(
    private val userDao: UserDao,
    private val userFullInfoDao: UserFullInfoDao
) : UserLocalDataSource {
    private val fullInfos = SynchronizedLruMap<Long, TdApi.UserFullInfo>(FULL_INFO_SNAPSHOT_LIMIT)
    private val users = SynchronizedLruMap<Long, TdApi.User>(USER_SNAPSHOT_LIMIT)

    override suspend fun getUser(userId: Long): TdApi.User? {
        users[userId]?.let { return it }
        val dbUser = userDao.getUser(userId)?.toTdApi() ?: return null
        users[userId] = dbUser
        return dbUser
    }

    override suspend fun putUser(user: TdApi.User) {
        users[user.id] = user
        val personalAvatarPath = fullInfos[user.id]?.extractPersonalAvatarPath()
            ?: userFullInfoDao.getUserFullInfo(user.id)?.personalPhotoPath?.ifBlank { null }
        userDao.insertUser(user.toEntity(personalAvatarPath))
    }

    override suspend fun getUserFullInfo(userId: Long): TdApi.UserFullInfo? {
        fullInfos[userId]?.let { return it }
        val dbInfo = userFullInfoDao.getUserFullInfo(userId)?.toTdApi() ?: return null
        fullInfos[userId] = dbInfo
        return dbInfo
    }

    override suspend fun putUserFullInfo(userId: Long, info: TdApi.UserFullInfo) {
        fullInfos[userId] = info
        userFullInfoDao.insertUserFullInfo(info.toEntity(userId))
        val personalAvatarPath = info.extractPersonalAvatarPath()
        if (!personalAvatarPath.isNullOrBlank()) {
            userDao.getUser(userId)?.let { existing ->
                if (existing.personalAvatarPath != personalAvatarPath) {
                    userDao.insertUser(existing.copy(personalAvatarPath = personalAvatarPath))
                }
            }
        }
    }

    override suspend fun getAllUsers(): Collection<TdApi.User> {
        val dbUsers = userDao.getAllUsers().map { it.toTdApi() }
        dbUsers.forEach { users[it.id] = it }
        return dbUsers
    }

    override suspend fun clearAll() {
        fullInfos.clear()
        users.clear()
    }

    override suspend fun getFullInfoEntity(userId: Long): UserFullInfoEntity? = userFullInfoDao.getUserFullInfo(userId)

    override suspend fun saveFullInfoEntity(info: UserFullInfoEntity) = userFullInfoDao.insertUserFullInfo(info)

    override suspend fun deleteExpired(timestamp: Long) {
        userDao.deleteExpired(timestamp)
        userFullInfoDao.deleteExpired(timestamp)
    }

    override suspend fun saveUser(user: UserEntity) = userDao.insertUser(user)

    override suspend fun loadUser(userId: Long): UserEntity? {
        val entity = userDao.getUser(userId)
        entity?.let { users[it.id] = it.toTdApi() }
        return entity
    }

    suspend fun deleteUser(userId: Long) = userDao.deleteUser(userId)

    override suspend fun clearDatabase() {
        userDao.clearAll()
        userFullInfoDao.clearAll()
    }

    override suspend fun updateUserStatus(userId: Long, status: TdApi.UserStatus): TdApi.User? {
        val user = users[userId] ?: getUser(userId) ?: return null
        user.status = status
        val statusType = when (status) {
            is TdApi.UserStatusOnline -> "ONLINE"
            is TdApi.UserStatusRecently -> "RECENTLY"
            is TdApi.UserStatusLastWeek -> "LAST_WEEK"
            is TdApi.UserStatusLastMonth -> "LAST_MONTH"
            else -> "OFFLINE"
        }
        val lastSeen = (status as? TdApi.UserStatusOffline)?.wasOnline?.toLong() ?: 0L
        userDao.updateStatus(userId, statusType, lastSeen)
        return user
    }

    override suspend fun clearCachedAvatarPaths() {
        userDao.clearAvatarPaths()
        // The bounded snapshot is derived from Room and will be refreshed lazily.
        users.clear()
    }

    private companion object {
        const val USER_SNAPSHOT_LIMIT = 1_024
        const val FULL_INFO_SNAPSHOT_LIMIT = 256
    }
}
