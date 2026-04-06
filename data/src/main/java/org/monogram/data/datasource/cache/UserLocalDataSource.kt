package org.monogram.data.datasource.cache

import org.drinkless.tdlib.TdApi
import org.monogram.data.db.model.UserEntity
import org.monogram.data.db.model.UserFullInfoEntity

interface UserLocalDataSource {
    suspend fun getUser(userId: Long): TdApi.User?
    suspend fun putUser(user: TdApi.User)
    suspend fun getUserFullInfo(userId: Long): TdApi.UserFullInfo?
    suspend fun putUserFullInfo(userId: Long, info: TdApi.UserFullInfo)
    suspend fun getAllUsers(): Collection<TdApi.User>
    suspend fun clearAll()

    suspend fun getFullInfoEntity(userId: Long): UserFullInfoEntity?
    suspend fun saveFullInfoEntity(info: UserFullInfoEntity)
    suspend fun deleteExpired(timestamp: Long)

    suspend fun saveUser(user: UserEntity) {}
    suspend fun loadUser(userId: Long): UserEntity? = null
    suspend fun clearDatabase() {}
}
