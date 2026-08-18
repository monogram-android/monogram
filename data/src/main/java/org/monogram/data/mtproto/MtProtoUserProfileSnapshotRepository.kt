package org.monogram.data.mtproto

import org.monogram.domain.models.UserProfileSnapshotModel
import org.monogram.domain.repository.UserProfileSnapshotRepository

internal class MtProtoUserProfileSnapshotRepository(
    private val configSource: TelegramMtProtoBootstrapConfigSource,
    private val userStore: MtProtoUserProjectionStore,
) : UserProfileSnapshotRepository {
    override suspend fun getCurrentUser(accountId: String): UserProfileSnapshotModel? =
        userStore.getSelf(scope(accountId))?.toDomain()

    override suspend fun getUser(accountId: String, userId: Long): UserProfileSnapshotModel? =
        userStore.get(scope(accountId), userId)?.toDomain()

    private fun scope(accountId: String): MtProtoAuthKeyScope {
        val config = configSource.create()
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
