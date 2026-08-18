package org.monogram.data.mtproto

import org.monogram.data.db.dao.MtProtoCloudObjectDao
import org.monogram.data.db.dao.MtProtoUserProjectionDao
import org.monogram.data.db.model.MtProtoUserProjectionEntity
import org.monogram.mtproto.codec.CloudTlObjectCodec
import org.monogram.mtproto.tl.generated.cloud.layer223.UserEmpty
import org.monogram.mtproto.tl.generated.cloud.layer223.User_1990f29d1e
import org.monogram.mtproto.tl.generated.cloud.layer223.User_655b5dfc57
import org.monogram.mtproto.tl.runtime.TlCodecException

internal data class MtProtoUserReadModel(
    val userId: Long,
    val accessHash: Long?,
    val firstName: String?,
    val lastName: String?,
    val username: String?,
    val phone: String?,
    val isSelf: Boolean,
    val isContact: Boolean,
    val isMutualContact: Boolean,
    val isDeleted: Boolean,
    val isBot: Boolean,
    val isVerified: Boolean,
    val isRestricted: Boolean,
    val isScam: Boolean,
    val isFake: Boolean,
    val isPremium: Boolean,
    val isMin: Boolean,
)

internal interface MtProtoUserProjectionStore {
    suspend fun upsert(scope: MtProtoAuthKeyScope, users: List<User_655b5dfc57>)

    suspend fun get(scope: MtProtoAuthKeyScope, userId: Long): MtProtoUserReadModel?

    suspend fun getSelf(scope: MtProtoAuthKeyScope): MtProtoUserReadModel?

    suspend fun getAll(scope: MtProtoAuthKeyScope): List<MtProtoUserReadModel>

    suspend fun backfill(scope: MtProtoAuthKeyScope): MtProtoUserProjectionBackfillResult

    suspend fun deleteAccount(accountSlot: String, environment: MtProtoEnvironment)
}

internal data class MtProtoUserProjectionBackfillResult(
    val projectedCount: Int,
    val rejectedCount: Int,
)

internal object NoOpMtProtoUserProjectionStore : MtProtoUserProjectionStore {
    override suspend fun upsert(scope: MtProtoAuthKeyScope, users: List<User_655b5dfc57>) = Unit

    override suspend fun get(scope: MtProtoAuthKeyScope, userId: Long): MtProtoUserReadModel? = null

    override suspend fun getSelf(scope: MtProtoAuthKeyScope): MtProtoUserReadModel? = null

    override suspend fun getAll(scope: MtProtoAuthKeyScope): List<MtProtoUserReadModel> = emptyList()

    override suspend fun backfill(scope: MtProtoAuthKeyScope) = MtProtoUserProjectionBackfillResult(0, 0)

    override suspend fun deleteAccount(accountSlot: String, environment: MtProtoEnvironment) = Unit
}

internal class MtProtoRoomUserProjectionStore(
    private val dao: MtProtoUserProjectionDao,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val cloudObjectDao: MtProtoCloudObjectDao? = null,
) : MtProtoUserProjectionStore {
    override suspend fun upsert(scope: MtProtoAuthKeyScope, users: List<User_655b5dfc57>) {
        users.forEach { user ->
            val incoming = user.toEntity(scope, nowMillis())
            val existing = dao.get(scope.accountSlot, scope.environment.storageName, scope.dcId, incoming.userId)
            dao.upsert(if (incoming.isMin) incoming.mergeMin(existing) else incoming)
        }
    }

    override suspend fun get(scope: MtProtoAuthKeyScope, userId: Long): MtProtoUserReadModel? =
        dao.get(scope.accountSlot, scope.environment.storageName, scope.dcId, userId)?.toReadModel()

    override suspend fun getSelf(scope: MtProtoAuthKeyScope): MtProtoUserReadModel? =
        dao.getSelf(scope.accountSlot, scope.environment.storageName, scope.dcId)?.toReadModel()

    override suspend fun getAll(scope: MtProtoAuthKeyScope): List<MtProtoUserReadModel> =
        dao.getAll(scope.accountSlot, scope.environment.storageName, scope.dcId).map { it.toReadModel() }

    override suspend fun backfill(scope: MtProtoAuthKeyScope): MtProtoUserProjectionBackfillResult {
        val source = cloudObjectDao ?: return MtProtoUserProjectionBackfillResult(0, 0)
        var projectedCount = 0
        var rejectedCount = 0
        source.getByType(scope.accountSlot, scope.environment.storageName, scope.dcId, USER_OBJECT_TYPE)
            .forEach { entity ->
                val user = try {
                    CloudTlObjectCodec.decode(entity.payload) as? User_655b5dfc57
                } catch (_: IllegalArgumentException) {
                    null
                } catch (_: TlCodecException) {
                    null
                }
                if (user == null) {
                    rejectedCount++
                } else {
                    upsert(scope, listOf(user))
                    projectedCount++
                }
            }
        return MtProtoUserProjectionBackfillResult(projectedCount, rejectedCount)
    }

    override suspend fun deleteAccount(accountSlot: String, environment: MtProtoEnvironment) =
        dao.deleteAccount(accountSlot, environment.storageName)

    private fun User_655b5dfc57.toEntity(scope: MtProtoAuthKeyScope, updatedAt: Long) = when (this) {
        is UserEmpty -> MtProtoUserProjectionEntity(
            accountSlot = scope.accountSlot,
            environment = scope.environment.storageName,
            dcId = scope.dcId,
            userId = id,
            accessHash = null,
            firstName = null,
            lastName = null,
            username = null,
            phone = null,
            isSelf = false,
            isContact = false,
            isMutualContact = false,
            isDeleted = true,
            isBot = false,
            isVerified = false,
            isRestricted = false,
            isScam = false,
            isFake = false,
            isPremium = false,
            isMin = false,
            updatedAt = updatedAt,
        )

        is User_1990f29d1e -> MtProtoUserProjectionEntity(
            accountSlot = scope.accountSlot,
            environment = scope.environment.storageName,
            dcId = scope.dcId,
            userId = id,
            accessHash = accessHash,
            firstName = firstName,
            lastName = lastName,
            username = username,
            phone = phone,
            isSelf = self,
            isContact = contact,
            isMutualContact = mutualContact,
            isDeleted = deleted,
            isBot = bot,
            isVerified = verified,
            isRestricted = restricted,
            isScam = scam,
            isFake = fake,
            isPremium = premium,
            isMin = min,
            updatedAt = updatedAt,
        )
    }

    private fun MtProtoUserProjectionEntity.mergeMin(existing: MtProtoUserProjectionEntity?) = existing?.copy(
        accessHash = accessHash ?: existing.accessHash,
        firstName = firstName ?: existing.firstName,
        lastName = lastName ?: existing.lastName,
        username = username ?: existing.username,
        phone = phone ?: existing.phone,
        isMin = true,
        updatedAt = updatedAt,
    ) ?: this

    private fun MtProtoUserProjectionEntity.toReadModel() = MtProtoUserReadModel(
        userId = userId,
        accessHash = accessHash,
        firstName = firstName,
        lastName = lastName,
        username = username,
        phone = phone,
        isSelf = isSelf,
        isContact = isContact,
        isMutualContact = isMutualContact,
        isDeleted = isDeleted,
        isBot = isBot,
        isVerified = isVerified,
        isRestricted = isRestricted,
        isScam = isScam,
        isFake = isFake,
        isPremium = isPremium,
        isMin = isMin,
    )

    private companion object {
        const val USER_OBJECT_TYPE = "user"
    }
}
