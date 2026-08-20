package org.monogram.data.mtproto

import org.monogram.domain.models.UserProfileSnapshotModel
import org.monogram.mtproto.tl.generated.cloud.layer223.InputUserSelf
import org.monogram.mtproto.tl.generated.cloud.layer223.InputUser_4020eae812
import org.monogram.mtproto.tl.generated.cloud.layer223.users.GetUsers
import org.monogram.domain.repository.UserProfileSnapshotRepository

internal interface MtProtoUserProfileReader {
    suspend fun getCurrentUser(accountId: String): UserProfileSnapshotModel?
    suspend fun getUser(accountId: String, userId: Long): UserProfileSnapshotModel?
}

internal class MtProtoUserProfileSnapshotRepository(
    private val configSource: TelegramMtProtoBootstrapConfigSource,
    private val userStore: MtProtoUserProjectionStore,
    private val sessionFactory: TelegramMtProtoSessionFactory? = null,
) : UserProfileSnapshotRepository, MtProtoUserProfileReader {
    override suspend fun getCurrentUser(accountId: String): UserProfileSnapshotModel? {
        val scope = scope(accountId)
        if (sessionFactory != null) {
            sessionFactory.open(accountId).use { transport ->
                userStore.upsert(scope, transport.execute(GetUsers(listOf(InputUserSelf))))
            }
        }
        return userStore.getSelf(scope)?.toDomain()
    }

    override suspend fun getUser(accountId: String, userId: Long): UserProfileSnapshotModel? {
        val scope = scope(accountId)
        val cached = userStore.get(scope, userId)
        if (sessionFactory != null && cached?.accessHash != null) {
            sessionFactory.open(accountId).use { transport ->
                userStore.upsert(
                    scope,
                    transport.execute(
                        GetUsers(listOf(InputUser_4020eae812(userId, cached.accessHash))),
                    ),
                )
            }
        }
        return userStore.get(scope, userId)?.toDomain()
    }

    private suspend fun scope(accountId: String): MtProtoAuthKeyScope {
        val config = configSource.createForAccount(accountId)
        return MtProtoAuthKeyScope(accountId, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId)
    }

    private fun MtProtoUserReadModel.toDomain() = UserProfileSnapshotModel(
        userId = userId,
        firstName = firstName,
        lastName = lastName,
        username = username,
        phoneNumber = phone,
        isCurrentUser = isSelf,
        isContact = isContact,
        isMutualContact = isMutualContact,
        isDeleted = isDeleted,
        isBot = isBot,
        isVerified = isVerified,
        isRestricted = isRestricted,
        isScam = isScam,
        isFake = isFake,
        isPremium = isPremium,
        isPartial = isMin,
    )
}
