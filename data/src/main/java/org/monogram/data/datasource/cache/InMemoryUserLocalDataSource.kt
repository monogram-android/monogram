package org.monogram.data.datasource.cache

import org.drinkless.tdlib.TdApi
import org.monogram.data.db.model.UserFullInfoEntity
import java.util.concurrent.ConcurrentHashMap

class InMemoryUserLocalDataSource : UserLocalDataSource {
    private val users = ConcurrentHashMap<Long, TdApi.User>()
    private val fullInfos = ConcurrentHashMap<Long, TdApi.UserFullInfo>()
    private val fullInfoEntities = ConcurrentHashMap<Long, UserFullInfoEntity>()

    override suspend fun getUser(userId: Long): TdApi.User? = users[userId]

    override suspend fun putUser(user: TdApi.User) {
        users[user.id] = user
    }

    override suspend fun getUserFullInfo(userId: Long): TdApi.UserFullInfo? = fullInfos[userId]

    override suspend fun putUserFullInfo(userId: Long, info: TdApi.UserFullInfo) {
        fullInfos[userId] = info
    }

    override suspend fun getAllUsers(): Collection<TdApi.User> = users.values

    override suspend fun clearAll() {
        users.clear()
        fullInfos.clear()
        fullInfoEntities.clear()
    }

    override suspend fun getFullInfoEntity(userId: Long): UserFullInfoEntity? = fullInfoEntities[userId]

    override suspend fun saveFullInfoEntity(info: UserFullInfoEntity) {
        fullInfoEntities[info.userId] = info
    }

    override suspend fun deleteExpired(timestamp: Long) {
        fullInfoEntities.values.removeIf { it.createdAt < timestamp }
    }
}