package org.monogram.data.mtproto

import org.monogram.data.db.dao.MtProtoChatProjectionDao
import org.monogram.data.db.dao.MtProtoCloudObjectDao
import org.monogram.data.db.model.MtProtoChatProjectionEntity
import org.monogram.mtproto.codec.CloudTlObjectCodec
import org.monogram.mtproto.tl.generated.cloud.layer223.Channel
import org.monogram.mtproto.tl.generated.cloud.layer223.ChannelForbidden
import org.monogram.mtproto.tl.generated.cloud.layer223.ChatEmpty
import org.monogram.mtproto.tl.generated.cloud.layer223.ChatForbidden
import org.monogram.mtproto.tl.generated.cloud.layer223.Chat_65eab3b078
import org.monogram.mtproto.tl.generated.cloud.layer223.Chat_7fdd7beb6e
import org.monogram.mtproto.tl.runtime.TlCodecException

internal enum class MtProtoChatType {
    BASIC_GROUP,
    SUPERGROUP,
    CHANNEL,
}

internal data class MtProtoChatReadModel(
    val chatId: Long,
    val type: MtProtoChatType,
    val accessHash: Long?,
    val title: String?,
    val username: String?,
    val participantsCount: Int?,
    val isDeleted: Boolean,
    val isForbidden: Boolean,
    val isLeft: Boolean,
    val isDeactivated: Boolean,
    val isVerified: Boolean,
    val isRestricted: Boolean,
    val isScam: Boolean,
    val isFake: Boolean,
    val isForum: Boolean,
    val signaturesEnabled: Boolean,
    val signatureProfilesEnabled: Boolean,
    val forumTabs: Boolean,
    val isMin: Boolean,
)

internal interface MtProtoChatProjectionStore {
    suspend fun upsert(scope: MtProtoAuthKeyScope, chats: List<Chat_7fdd7beb6e>)
    suspend fun get(scope: MtProtoAuthKeyScope, chatId: Long): MtProtoChatReadModel?
    suspend fun getAll(scope: MtProtoAuthKeyScope): List<MtProtoChatReadModel>
    suspend fun backfill(scope: MtProtoAuthKeyScope): MtProtoChatProjectionBackfillResult
    suspend fun deleteAccount(accountSlot: String, environment: MtProtoEnvironment)
}

internal data class MtProtoChatProjectionBackfillResult(
    val projectedCount: Int,
    val rejectedCount: Int,
)

internal object NoOpMtProtoChatProjectionStore : MtProtoChatProjectionStore {
    override suspend fun upsert(scope: MtProtoAuthKeyScope, chats: List<Chat_7fdd7beb6e>) = Unit
    override suspend fun get(scope: MtProtoAuthKeyScope, chatId: Long): MtProtoChatReadModel? = null
    override suspend fun getAll(scope: MtProtoAuthKeyScope): List<MtProtoChatReadModel> = emptyList()
    override suspend fun backfill(scope: MtProtoAuthKeyScope) = MtProtoChatProjectionBackfillResult(0, 0)
    override suspend fun deleteAccount(accountSlot: String, environment: MtProtoEnvironment) = Unit
}

internal class MtProtoRoomChatProjectionStore(
    private val dao: MtProtoChatProjectionDao,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val cloudObjectDao: MtProtoCloudObjectDao? = null,
) : MtProtoChatProjectionStore {
    override suspend fun upsert(scope: MtProtoAuthKeyScope, chats: List<Chat_7fdd7beb6e>) {
        chats.forEach { chat ->
            val incoming = chat.toEntity(scope, nowMillis())
            val existing = dao.get(scope.accountSlot, scope.environment.storageName, scope.dcId, incoming.chatId)
            dao.upsert(if (incoming.isMin) incoming.mergeMin(existing) else incoming)
        }
    }

    override suspend fun get(scope: MtProtoAuthKeyScope, chatId: Long): MtProtoChatReadModel? =
        dao.get(scope.accountSlot, scope.environment.storageName, scope.dcId, chatId)?.toReadModel()

    override suspend fun getAll(scope: MtProtoAuthKeyScope): List<MtProtoChatReadModel> =
        dao.getAll(scope.accountSlot, scope.environment.storageName, scope.dcId).map { it.toReadModel() }

    override suspend fun backfill(scope: MtProtoAuthKeyScope): MtProtoChatProjectionBackfillResult {
        val source = cloudObjectDao ?: return MtProtoChatProjectionBackfillResult(0, 0)
        var projectedCount = 0
        var rejectedCount = 0
        source.getByType(scope.accountSlot, scope.environment.storageName, scope.dcId, CHAT_OBJECT_TYPE)
            .forEach { entity ->
                val chat = try {
                    CloudTlObjectCodec.decode(entity.payload) as? Chat_7fdd7beb6e
                } catch (_: IllegalArgumentException) {
                    null
                } catch (_: TlCodecException) {
                    null
                }
                if (chat == null) {
                    rejectedCount++
                } else {
                    upsert(scope, listOf(chat))
                    projectedCount++
                }
            }
        return MtProtoChatProjectionBackfillResult(projectedCount, rejectedCount)
    }

    override suspend fun deleteAccount(accountSlot: String, environment: MtProtoEnvironment) =
        dao.deleteAccount(accountSlot, environment.storageName)

    private fun Chat_7fdd7beb6e.toEntity(scope: MtProtoAuthKeyScope, updatedAt: Long) = when (this) {
        is ChatEmpty -> entity(scope, id, MtProtoChatType.BASIC_GROUP, updatedAt, isDeleted = true)
        is ChatForbidden -> entity(
            scope,
            id,
            MtProtoChatType.BASIC_GROUP,
            updatedAt,
            title = title,
            isForbidden = true,
        )
        is Chat_65eab3b078 -> entity(
            scope,
            id,
            MtProtoChatType.BASIC_GROUP,
            updatedAt,
            title = title,
            participantsCount = participantsCount,
            isLeft = left,
            isDeactivated = deactivated,
        )
        is ChannelForbidden -> entity(
            scope,
            id,
            if (megagroup) MtProtoChatType.SUPERGROUP else MtProtoChatType.CHANNEL,
            updatedAt,
            accessHash = accessHash,
            title = title,
            isForbidden = true,
            isBroadcast = broadcast,
            isMegagroup = megagroup,
        )
        is Channel -> entity(
            scope,
            id,
            if (megagroup) MtProtoChatType.SUPERGROUP else MtProtoChatType.CHANNEL,
            updatedAt,
            accessHash = accessHash,
            title = title,
            username = username,
            participantsCount = participantsCount,
            isLeft = left,
            isBroadcast = broadcast,
            isMegagroup = megagroup,
            isVerified = verified,
            isRestricted = restricted,
            isScam = scam,
            isFake = fake,
            isForum = forum,
            signaturesEnabled = signatures,
            signatureProfilesEnabled = signatureProfiles,
            forumTabs = forumTabs,
            isMin = min,
        )
    }

    private fun entity(
        scope: MtProtoAuthKeyScope,
        chatId: Long,
        type: MtProtoChatType,
        updatedAt: Long,
        accessHash: Long? = null,
        title: String? = null,
        username: String? = null,
        participantsCount: Int? = null,
        isDeleted: Boolean = false,
        isForbidden: Boolean = false,
        isLeft: Boolean = false,
        isDeactivated: Boolean = false,
        isBroadcast: Boolean = false,
        isMegagroup: Boolean = false,
        isVerified: Boolean = false,
        isRestricted: Boolean = false,
        isScam: Boolean = false,
        isFake: Boolean = false,
        isForum: Boolean = false,
        signaturesEnabled: Boolean = false,
        signatureProfilesEnabled: Boolean = false,
        forumTabs: Boolean = false,
        isMin: Boolean = false,
    ) = MtProtoChatProjectionEntity(
        scope.accountSlot,
        scope.environment.storageName,
        scope.dcId,
        chatId,
        type.name,
        accessHash,
        title,
        username,
        participantsCount,
        isDeleted,
        isForbidden,
        isLeft,
        isDeactivated,
        isBroadcast,
        isMegagroup,
        isVerified,
        isRestricted,
        isScam,
        isFake,
        isForum,
        signaturesEnabled,
        signatureProfilesEnabled,
        forumTabs,
        isMin,
        updatedAt,
    )

    private fun MtProtoChatProjectionEntity.mergeMin(existing: MtProtoChatProjectionEntity?) = existing?.copy(
        type = type,
        accessHash = accessHash ?: existing.accessHash,
        title = title ?: existing.title,
        username = username ?: existing.username,
        participantsCount = participantsCount ?: existing.participantsCount,
        isMin = true,
        updatedAt = updatedAt,
    ) ?: this

    private fun MtProtoChatProjectionEntity.toReadModel() = MtProtoChatReadModel(
        chatId,
        MtProtoChatType.valueOf(type),
        accessHash,
        title,
        username,
        participantsCount,
        isDeleted,
        isForbidden,
        isLeft,
        isDeactivated,
        isVerified,
        isRestricted,
        isScam,
        isFake,
        isForum,
        signaturesEnabled,
        signatureProfilesEnabled,
        forumTabs,
        isMin,
    )

    private companion object {
        const val CHAT_OBJECT_TYPE = "chat"
    }
}
