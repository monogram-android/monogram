package org.monogram.data.datasource.cache

import org.drinkless.tdlib.TdApi
import org.monogram.data.db.dao.UserDao
import org.monogram.data.db.dao.UserFullInfoDao
import org.monogram.data.db.model.UserEntity
import org.monogram.data.db.model.UserFullInfoEntity
import org.monogram.data.mapper.user.toEntity
import org.monogram.data.mapper.user.toTdApi
import java.util.concurrent.ConcurrentHashMap

class RoomUserLocalDataSource(
    private val userDao: UserDao,
    private val userFullInfoDao: UserFullInfoDao
) : UserLocalDataSource {
    private val fullInfos = ConcurrentHashMap<Long, TdApi.UserFullInfo>()
    private val users = ConcurrentHashMap<Long, TdApi.User>()

    override suspend fun getUser(userId: Long): TdApi.User? {
        users[userId]?.let { return it }
        val dbUser = userDao.getUser(userId)?.toTdApi() ?: return null
        users[userId] = dbUser
        return dbUser
    }

    override suspend fun putUser(user: TdApi.User) {
        users[user.id] = user
        userDao.insertUser(user.toEntity())
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
    }

    override suspend fun getAllUsers(): Collection<TdApi.User> {
        val dbUsers = userDao.getAllUsers().map { it.toTdApi() }
        dbUsers.forEach { users[it.id] = it }
        return users.values
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

    suspend fun saveUser(user: UserEntity) = userDao.insertUser(user)
    suspend fun loadUser(userId: Long): UserEntity? {
        val entity = userDao.getUser(userId)
        entity?.let { users[it.id] = it.toTdApi() }
        return entity
    }
    suspend fun deleteUser(userId: Long) = userDao.deleteUser(userId)
    suspend fun clearDatabase() {
        userDao.clearAll()
        userFullInfoDao.clearAll()
    }

    private fun TdApi.User.toEntity(): UserEntity {
        return UserEntity(
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
