package org.monogram.data.mtproto

import org.monogram.data.db.dao.MtProtoCloudObjectDao
import org.monogram.data.db.dao.MtProtoMessageProjectionDao
import org.monogram.data.db.model.MtProtoMessageProjectionEntity
import org.monogram.mtproto.codec.CloudTlObjectCodec
import org.monogram.mtproto.tl.generated.cloud.layer223.MessageEmpty
import org.monogram.mtproto.tl.generated.cloud.layer223.MessageService
import org.monogram.mtproto.tl.generated.cloud.layer223.Message_73e57f95e4
import org.monogram.mtproto.tl.generated.cloud.layer223.Message_7b7ecf54a3
import org.monogram.mtproto.tl.generated.cloud.layer223.Peer
import org.monogram.mtproto.tl.generated.cloud.layer223.PeerChannel
import org.monogram.mtproto.tl.generated.cloud.layer223.PeerChat
import org.monogram.mtproto.tl.generated.cloud.layer223.PeerUser
import org.monogram.mtproto.tl.generated.cloud.layer223.Update
import org.monogram.mtproto.tl.generated.cloud.layer223.UpdateDeleteChannelMessages
import org.monogram.mtproto.tl.generated.cloud.layer223.UpdateDeleteMessages
import org.monogram.mtproto.tl.generated.cloud.layer223.UpdateDialogPinned
import org.monogram.mtproto.tl.generated.cloud.layer223.UpdateDialogUnreadMark
import org.monogram.mtproto.tl.generated.cloud.layer223.UpdateEditChannelMessage
import org.monogram.mtproto.tl.generated.cloud.layer223.UpdateEditMessage
import org.monogram.mtproto.tl.generated.cloud.layer223.UpdateNewChannelMessage
import org.monogram.mtproto.tl.generated.cloud.layer223.UpdateNewMessage
import org.monogram.mtproto.tl.generated.cloud.layer223.UpdateShortChatMessage
import org.monogram.mtproto.tl.generated.cloud.layer223.UpdateShort
import org.monogram.mtproto.tl.generated.cloud.layer223.UpdateShortMessage
import org.monogram.mtproto.tl.generated.cloud.layer223.UpdateShortSentMessage
import org.monogram.mtproto.tl.generated.cloud.layer223.Updates_02c952992b
import org.monogram.mtproto.tl.generated.cloud.layer223.Updates_faf6aaa3d5
import org.monogram.mtproto.tl.runtime.TlCodecException
import org.monogram.mtproto.updates.MtProtoUpdateDifferenceBatch

internal enum class MtProtoMessagePeerType {
    USER,
    GROUP,
    CHANNEL,
}

internal data class MtProtoMessageReadModel(
    val peerType: MtProtoMessagePeerType,
    val peerId: Long,
    val messageId: Int,
    val senderType: MtProtoMessagePeerType?,
    val senderId: Long?,
    val date: Int,
    val text: String?,
    val isService: Boolean,
    val isDeleted: Boolean,
    val isOutgoing: Boolean,
    val isMentioned: Boolean,
    val isMediaUnread: Boolean,
    val isSilent: Boolean,
    val isPinned: Boolean,
    val editDate: Int?,
    val groupedId: Long?,
    val hasMedia: Boolean,
)

internal data class MtProtoMessageHistoryCursor(
    val date: Int,
    val messageId: Int,
)

internal interface MtProtoMessageProjectionStore {
    suspend fun stageLive(scope: MtProtoAuthKeyScope, envelope: Updates_faf6aaa3d5)
    suspend fun stageDifference(scope: MtProtoAuthKeyScope, batch: MtProtoUpdateDifferenceBatch)

    suspend fun stageMessages(scope: MtProtoAuthKeyScope, messages: List<Message_73e57f95e4>)
    suspend fun get(scope: MtProtoAuthKeyScope, peerType: MtProtoMessagePeerType, peerId: Long, messageId: Int): MtProtoMessageReadModel?
    suspend fun getAll(scope: MtProtoAuthKeyScope, peerType: MtProtoMessagePeerType, peerId: Long): List<MtProtoMessageReadModel>
    suspend fun getPage(scope: MtProtoAuthKeyScope, peerType: MtProtoMessagePeerType, peerId: Long, before: MtProtoMessageHistoryCursor?, limit: Int): List<MtProtoMessageReadModel>
    suspend fun backfill(scope: MtProtoAuthKeyScope): MtProtoMessageProjectionBackfillResult
    suspend fun deleteAccount(accountSlot: String, environment: MtProtoEnvironment)
}

internal data class MtProtoMessageProjectionBackfillResult(
    val projectedCount: Int,
    val rejectedCount: Int,
)

internal object NoOpMtProtoMessageProjectionStore : MtProtoMessageProjectionStore {
    override suspend fun stageLive(scope: MtProtoAuthKeyScope, envelope: Updates_faf6aaa3d5) = Unit
    override suspend fun stageDifference(scope: MtProtoAuthKeyScope, batch: MtProtoUpdateDifferenceBatch) = Unit

    override suspend fun stageMessages(scope: MtProtoAuthKeyScope, messages: List<Message_73e57f95e4>) = Unit
    override suspend fun get(scope: MtProtoAuthKeyScope, peerType: MtProtoMessagePeerType, peerId: Long, messageId: Int): MtProtoMessageReadModel? = null
    override suspend fun getAll(scope: MtProtoAuthKeyScope, peerType: MtProtoMessagePeerType, peerId: Long): List<MtProtoMessageReadModel> = emptyList()
    override suspend fun getPage(scope: MtProtoAuthKeyScope, peerType: MtProtoMessagePeerType, peerId: Long, before: MtProtoMessageHistoryCursor?, limit: Int): List<MtProtoMessageReadModel> = emptyList()
    override suspend fun backfill(scope: MtProtoAuthKeyScope) = MtProtoMessageProjectionBackfillResult(0, 0)
    override suspend fun deleteAccount(accountSlot: String, environment: MtProtoEnvironment) = Unit
}

internal class MtProtoRoomMessageProjectionStore(
    private val dao: MtProtoMessageProjectionDao,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val cloudObjectDao: MtProtoCloudObjectDao? = null,
    private val dialogStore: MtProtoDialogStore? = null,
) : MtProtoMessageProjectionStore {
    override suspend fun stageLive(scope: MtProtoAuthKeyScope, envelope: Updates_faf6aaa3d5) {
        applyLive(scope, envelope)
    }

    private suspend fun applyLive(scope: MtProtoAuthKeyScope, envelope: Updates_faf6aaa3d5): Boolean =
        when (envelope) {
            is org.monogram.mtproto.tl.generated.cloud.layer223.UpdatesCombined -> applyUpdates(scope, envelope.updates)
            is Updates_02c952992b -> applyUpdates(scope, envelope.updates)
            is UpdateShort -> applyUpdate(scope, envelope.update)
            is UpdateShortMessage -> {
                persist(scope, envelope.toEntity(scope))
                true
            }
            is UpdateShortChatMessage -> {
                persist(scope, envelope.toEntity(scope))
                true
            }
            is UpdateShortSentMessage -> false
            org.monogram.mtproto.tl.generated.cloud.layer223.UpdatesTooLong -> false
        }

    override suspend fun stageDifference(scope: MtProtoAuthKeyScope, batch: MtProtoUpdateDifferenceBatch) {
        batch.newMessages.forEach { upsert(scope, it) }
        applyUpdates(scope, batch.otherUpdates)
    }

    override suspend fun stageMessages(scope: MtProtoAuthKeyScope, messages: List<Message_73e57f95e4>) {
        messages.forEach { upsert(scope, it) }
    }

    override suspend fun get(scope: MtProtoAuthKeyScope, peerType: MtProtoMessagePeerType, peerId: Long, messageId: Int): MtProtoMessageReadModel? =
        dao.get(scope.accountSlot, scope.environment.storageName, scope.dcId, peerType.name, peerId, messageId)?.toReadModel()

    override suspend fun getAll(scope: MtProtoAuthKeyScope, peerType: MtProtoMessagePeerType, peerId: Long): List<MtProtoMessageReadModel> =
        dao.getAll(scope.accountSlot, scope.environment.storageName, scope.dcId, peerType.name, peerId).map { it.toReadModel() }

    override suspend fun getPage(
        scope: MtProtoAuthKeyScope,
        peerType: MtProtoMessagePeerType,
        peerId: Long,
        before: MtProtoMessageHistoryCursor?,
        limit: Int,
    ): List<MtProtoMessageReadModel> {
        require(limit in 1..MAX_HISTORY_PAGE_SIZE)
        return dao.getPage(
            scope.accountSlot,
            scope.environment.storageName,
            scope.dcId,
            peerType.name,
            peerId,
            before?.date,
            before?.messageId,
            limit,
        ).map { it.toReadModel() }
    }

    override suspend fun backfill(scope: MtProtoAuthKeyScope): MtProtoMessageProjectionBackfillResult {
        val source = cloudObjectDao ?: return MtProtoMessageProjectionBackfillResult(0, 0)
        var projectedCount = 0
        var rejectedCount = 0
        source.getAll(scope.accountSlot, scope.environment.storageName, scope.dcId).forEach { entity ->
            when (entity.objectType) {
                MESSAGE_OBJECT_TYPE -> {
                    val message = decode(entity.payload) as? Message_73e57f95e4
                    if (message == null || !upsert(scope, message)) rejectedCount++ else projectedCount++
                }
                UPDATE_OBJECT_TYPE -> {
                    val update = decode(entity.payload) as? Update
                    if (update == null) rejectedCount++ else if (applyUpdate(scope, update)) projectedCount++
                }
                LIVE_UPDATES_OBJECT_TYPE -> {
                    val envelope = decode(entity.payload) as? Updates_faf6aaa3d5
                    if (envelope == null) rejectedCount++ else if (applyLive(scope, envelope)) projectedCount++
                }
            }
        }
        return MtProtoMessageProjectionBackfillResult(projectedCount, rejectedCount)
    }

    private fun decode(payload: ByteArray) = try {
        CloudTlObjectCodec.decode(payload)
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: TlCodecException) {
        null
    }

    override suspend fun deleteAccount(accountSlot: String, environment: MtProtoEnvironment) =
        dao.deleteAccount(accountSlot, environment.storageName)

    private suspend fun applyUpdates(scope: MtProtoAuthKeyScope, updates: List<Update>): Boolean =
        updates.fold(false) { applied, update -> applyUpdate(scope, update) || applied }

    private suspend fun applyUpdate(scope: MtProtoAuthKeyScope, update: Update): Boolean = when (update) {
        is UpdateNewMessage -> upsert(scope, update.message)
        is UpdateEditMessage -> upsert(scope, update.message)
        is UpdateNewChannelMessage -> upsert(scope, update.message)
        is UpdateEditChannelMessage -> upsert(scope, update.message)
        is UpdateDeleteMessages -> {
            if (update.messages.isNotEmpty()) dao.markDeletedNonChannel(scope.accountSlot, scope.environment.storageName, scope.dcId, update.messages, nowMillis())
            true
        }
        is UpdateDeleteChannelMessages -> {
            if (update.messages.isNotEmpty()) dao.markDeletedChannel(scope.accountSlot, scope.environment.storageName, scope.dcId, update.channelId, update.messages, nowMillis())
            true
        }
        is UpdateDialogPinned -> {
            dialogStore?.updatePinned(scope, update)
            dialogStore != null
        }
        is UpdateDialogUnreadMark -> {
            dialogStore?.updateUnreadMark(scope, update)
            dialogStore != null
        }
        else -> false
    }

    private suspend fun upsert(scope: MtProtoAuthKeyScope, message: Message_73e57f95e4): Boolean {
        val entity = message.toEntity(scope, nowMillis()) ?: return false
        persist(scope, entity)
        return true
    }

    private suspend fun persist(scope: MtProtoAuthKeyScope, entity: MtProtoMessageProjectionEntity) {
        dao.upsert(entity)
        dialogStore?.updateTopMessage(
            scope = scope,
            peerType = MtProtoMessagePeerType.valueOf(entity.peerType),
            peerId = entity.peerId,
            messageId = entity.messageId,
        )
    }

    private fun Message_73e57f95e4.toEntity(scope: MtProtoAuthKeyScope, updatedAt: Long): MtProtoMessageProjectionEntity? = when (this) {
        is MessageEmpty -> peerId?.toPeerKey()?.let { (type, id) -> entity(scope, type, id, this.id, updatedAt, isDeleted = true) }
        is MessageService -> peerId.toPeerKey().let { (type, id) -> entity(scope, type, id, this.id, updatedAt, sender = fromId, date = date, isService = true, isOutgoing = out_, isMentioned = mentioned, isMediaUnread = mediaUnread, isSilent = silent) }
        is Message_7b7ecf54a3 -> peerId.toPeerKey().let { (type, id) -> entity(scope, type, id, this.id, updatedAt, sender = fromId, date = date, text = message, isOutgoing = out_, isMentioned = mentioned, isMediaUnread = mediaUnread, isSilent = silent, isPinned = pinned, editDate = editDate, groupedId = groupedId, hasMedia = media != null) }
    }

    private fun UpdateShortMessage.toEntity(scope: MtProtoAuthKeyScope) = entity(
        scope,
        MtProtoMessagePeerType.USER,
        userId,
        id,
        nowMillis(),
        date = date,
        text = message,
        isOutgoing = out_,
        isMentioned = mentioned,
        isMediaUnread = mediaUnread,
        isSilent = silent,
    )

    private fun UpdateShortChatMessage.toEntity(scope: MtProtoAuthKeyScope) = entity(
        scope,
        MtProtoMessagePeerType.GROUP,
        chatId,
        id,
        nowMillis(),
        sender = PeerUser(fromId),
        date = date,
        text = message,
        isOutgoing = out_,
        isMentioned = mentioned,
        isMediaUnread = mediaUnread,
        isSilent = silent,
    )

    private fun Peer.toPeerKey() = when (this) {
        is PeerUser -> MtProtoMessagePeerType.USER to userId
        is PeerChat -> MtProtoMessagePeerType.GROUP to chatId
        is PeerChannel -> MtProtoMessagePeerType.CHANNEL to channelId
    }

    private fun entity(scope: MtProtoAuthKeyScope, peerType: MtProtoMessagePeerType, peerId: Long, messageId: Int, updatedAt: Long, sender: Peer? = null, date: Int = 0, text: String? = null, isService: Boolean = false, isDeleted: Boolean = false, isOutgoing: Boolean = false, isMentioned: Boolean = false, isMediaUnread: Boolean = false, isSilent: Boolean = false, isPinned: Boolean = false, editDate: Int? = null, groupedId: Long? = null, hasMedia: Boolean = false): MtProtoMessageProjectionEntity {
        val senderKey = sender?.toPeerKey()
        return MtProtoMessageProjectionEntity(scope.accountSlot, scope.environment.storageName, scope.dcId, peerType.name, peerId, messageId, senderKey?.first?.name, senderKey?.second, date, text, isService, isDeleted, isOutgoing, isMentioned, isMediaUnread, isSilent, isPinned, editDate, groupedId, hasMedia, updatedAt)
    }

    private fun MtProtoMessageProjectionEntity.toReadModel() = MtProtoMessageReadModel(MtProtoMessagePeerType.valueOf(peerType), peerId, messageId, senderType?.let(MtProtoMessagePeerType::valueOf), senderId, date, text, isService, isDeleted, isOutgoing, isMentioned, isMediaUnread, isSilent, isPinned, editDate, groupedId, hasMedia)

    private companion object {
        const val MESSAGE_OBJECT_TYPE = "message"
        const val UPDATE_OBJECT_TYPE = "update"
        const val LIVE_UPDATES_OBJECT_TYPE = "live_updates"
        const val MAX_HISTORY_PAGE_SIZE = 100
    }
}
