package org.monogram.data.mtproto

import androidx.room.withTransaction
import org.monogram.data.db.MonogramDatabase
import org.monogram.data.db.dao.MtProtoCloudObjectDao
import org.monogram.data.db.dao.MtProtoMessageProjectionDao
import org.monogram.data.db.model.MtProtoMessageProjectionEntity
import org.monogram.mtproto.codec.CloudTlObjectCodec
import org.monogram.mtproto.tl.generated.cloud.layer223.TextWithEntities_d094604bd3
import org.monogram.mtproto.tl.generated.cloud.layer223.Poll_942021f3e0
import org.monogram.mtproto.tl.generated.cloud.layer223.WebPage_f814c33072
import org.monogram.mtproto.tl.generated.cloud.layer223.Game_616d2a0f4e
import org.monogram.mtproto.tl.generated.cloud.layer223.GeoPoint_126ad61cec
import org.monogram.mtproto.tl.generated.cloud.layer223.GeoPoint_9a65b6b51e
import org.monogram.mtproto.tl.generated.cloud.layer223.PollAnswer_ef4c0287fd
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
import org.monogram.mtproto.tl.generated.cloud.layer223.UpdateNotifySettings
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
    val documentId: Long? = null,
    val isScheduled: Boolean = false,
    val photoId: Long? = null,
    val mediaType: String? = null,
    val mediaKey: String? = null,
)

internal data class MtProtoMessageHistoryCursor(
    val date: Int,
    val messageId: Int,
)

internal interface MtProtoMessageProjectionStore {
    suspend fun stageLive(scope: MtProtoAuthKeyScope, envelope: Updates_faf6aaa3d5)
    suspend fun stageDifference(scope: MtProtoAuthKeyScope, batch: MtProtoUpdateDifferenceBatch)

    suspend fun stageMessages(scope: MtProtoAuthKeyScope, messages: List<Message_73e57f95e4>, isScheduled: Boolean = false)
    suspend fun stageQueryMessages(scope: MtProtoAuthKeyScope, messages: List<Message_73e57f95e4>, isScheduled: Boolean = false) =
        stageMessages(scope, messages, isScheduled)
    suspend fun get(scope: MtProtoAuthKeyScope, peerType: MtProtoMessagePeerType, peerId: Long, messageId: Int): MtProtoMessageReadModel?
    suspend fun getAll(scope: MtProtoAuthKeyScope, peerType: MtProtoMessagePeerType, peerId: Long): List<MtProtoMessageReadModel>
    suspend fun search(scope: MtProtoAuthKeyScope, query: String, limit: Int, offset: Int): List<MtProtoMessageReadModel>
    suspend fun getPage(scope: MtProtoAuthKeyScope, peerType: MtProtoMessagePeerType, peerId: Long, before: MtProtoMessageHistoryCursor?, limit: Int): List<MtProtoMessageReadModel>
    suspend fun getPageAfter(scope: MtProtoAuthKeyScope, peerType: MtProtoMessagePeerType, peerId: Long, after: MtProtoMessageHistoryCursor?, limit: Int): List<MtProtoMessageReadModel>
    suspend fun markDeleted(scope: MtProtoAuthKeyScope, peerType: MtProtoMessagePeerType, peerId: Long, messageIds: List<Int>) = Unit
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

    override suspend fun stageMessages(scope: MtProtoAuthKeyScope, messages: List<Message_73e57f95e4>, isScheduled: Boolean) = Unit
    override suspend fun stageQueryMessages(scope: MtProtoAuthKeyScope, messages: List<Message_73e57f95e4>, isScheduled: Boolean) = Unit
    override suspend fun get(scope: MtProtoAuthKeyScope, peerType: MtProtoMessagePeerType, peerId: Long, messageId: Int): MtProtoMessageReadModel? = null
    override suspend fun getAll(scope: MtProtoAuthKeyScope, peerType: MtProtoMessagePeerType, peerId: Long): List<MtProtoMessageReadModel> = emptyList()
    override suspend fun search(scope: MtProtoAuthKeyScope, query: String, limit: Int, offset: Int): List<MtProtoMessageReadModel> = emptyList()
    override suspend fun getPage(scope: MtProtoAuthKeyScope, peerType: MtProtoMessagePeerType, peerId: Long, before: MtProtoMessageHistoryCursor?, limit: Int): List<MtProtoMessageReadModel> = emptyList()
    override suspend fun getPageAfter(scope: MtProtoAuthKeyScope, peerType: MtProtoMessagePeerType, peerId: Long, after: MtProtoMessageHistoryCursor?, limit: Int): List<MtProtoMessageReadModel> = emptyList()
    override suspend fun markDeleted(scope: MtProtoAuthKeyScope, peerType: MtProtoMessagePeerType, peerId: Long, messageIds: List<Int>) = Unit
    override suspend fun backfill(scope: MtProtoAuthKeyScope) = MtProtoMessageProjectionBackfillResult(0, 0)
    override suspend fun deleteAccount(accountSlot: String, environment: MtProtoEnvironment) = Unit
}

internal class MtProtoRoomMessageProjectionStore(
    private val dao: MtProtoMessageProjectionDao,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val cloudObjectDao: MtProtoCloudObjectDao? = null,
    private val dialogStore: MtProtoDialogStore? = null,
    private val documentLocations: MtProtoDocumentLocationStore = NoOpMtProtoDocumentLocationStore,
    private val photoLocations: MtProtoPhotoLocationStore = NoOpMtProtoPhotoLocationStore,
    private val pollPayloads: MtProtoPollPayloadStore? = null,
    private val database: MonogramDatabase? = null,
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

    override suspend fun stageMessages(scope: MtProtoAuthKeyScope, messages: List<Message_73e57f95e4>, isScheduled: Boolean) {
        messages.forEach { upsert(scope, it, isScheduled, updateDialog = true) }
    }

    override suspend fun stageQueryMessages(scope: MtProtoAuthKeyScope, messages: List<Message_73e57f95e4>, isScheduled: Boolean) {
        messages.forEach { upsert(scope, it, isScheduled, updateDialog = false) }
    }

    override suspend fun get(scope: MtProtoAuthKeyScope, peerType: MtProtoMessagePeerType, peerId: Long, messageId: Int): MtProtoMessageReadModel? =
        dao.get(scope.accountSlot, scope.environment.storageName, scope.dcId, peerType.name, peerId, messageId)?.toReadModel()

    override suspend fun getAll(scope: MtProtoAuthKeyScope, peerType: MtProtoMessagePeerType, peerId: Long): List<MtProtoMessageReadModel> =
        dao.getAll(scope.accountSlot, scope.environment.storageName, scope.dcId, peerType.name, peerId).map { it.toReadModel() }

    override suspend fun search(scope: MtProtoAuthKeyScope, query: String, limit: Int, offset: Int): List<MtProtoMessageReadModel> =
        dao.search(scope.accountSlot, scope.environment.storageName, scope.dcId, query, limit, offset).map { it.toReadModel() }

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

    override suspend fun getPageAfter(
        scope: MtProtoAuthKeyScope,
        peerType: MtProtoMessagePeerType,
        peerId: Long,
        after: MtProtoMessageHistoryCursor?,
        limit: Int,
    ): List<MtProtoMessageReadModel> {
        require(limit in 1..MAX_HISTORY_PAGE_SIZE)
        return dao.getPageAfter(
            scope.accountSlot,
            scope.environment.storageName,
            scope.dcId,
            peerType.name,
            peerId,
            after?.date,
            after?.messageId,
            limit,
        ).map { it.toReadModel() }
    }

    override suspend fun markDeleted(
        scope: MtProtoAuthKeyScope,
        peerType: MtProtoMessagePeerType,
        peerId: Long,
        messageIds: List<Int>,
    ) {
        if (messageIds.isEmpty()) return
        if (peerType == MtProtoMessagePeerType.CHANNEL) {
            dao.markDeletedChannel(scope.accountSlot, scope.environment.storageName, scope.dcId, peerId, messageIds, nowMillis())
        } else {
            dao.markDeletedNonChannel(scope.accountSlot, scope.environment.storageName, scope.dcId, messageIds, nowMillis())
        }
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

    override suspend fun deleteAccount(accountSlot: String, environment: MtProtoEnvironment) {
        dao.deleteAccount(accountSlot, environment.storageName)
        documentLocations.deleteAccount(accountSlot, environment)
    }

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
        is UpdateNotifySettings -> {
            dialogStore?.updateNotifySettings(scope, update)
            dialogStore != null
        }
        else -> false
    }

    private suspend fun upsert(scope: MtProtoAuthKeyScope, message: Message_73e57f95e4, isScheduled: Boolean = false, updateDialog: Boolean = true): Boolean {
        val entity = message.toEntity(scope, nowMillis(), isScheduled) ?: return false
        persist(
            scope,
            entity,
            (message as? org.monogram.mtproto.tl.generated.cloud.layer223.Message_7b7ecf54a3)?.document(),
            (message as? org.monogram.mtproto.tl.generated.cloud.layer223.Message_7b7ecf54a3)?.photo(),
            (message as? org.monogram.mtproto.tl.generated.cloud.layer223.Message_7b7ecf54a3)?.poll(),
            (message as? org.monogram.mtproto.tl.generated.cloud.layer223.Message_7b7ecf54a3)?.let { concrete ->
                (concrete.media as? org.monogram.mtproto.tl.generated.cloud.layer223.MessageMediaPoll)?.results
                    as? org.monogram.mtproto.tl.generated.cloud.layer223.PollResults_267c8c3226
            },
            updateDialog,
        )
        return true
    }

    private suspend fun persist(
        scope: MtProtoAuthKeyScope,
        entity: MtProtoMessageProjectionEntity,
        document: org.monogram.mtproto.tl.generated.cloud.layer223.Document_be725c3b31? = null,
        photo: org.monogram.mtproto.tl.generated.cloud.layer223.Photo_97e0ed8316? = null,
        poll: Poll_942021f3e0? = null,
        pollResults: org.monogram.mtproto.tl.generated.cloud.layer223.PollResults_267c8c3226? = null,
        updateDialog: Boolean = true,
    ) {
        suspend fun persistProjection() {
            document?.let { documentLocations.upsert(scope, it) }
            photo?.let { photoLocations.upsert(scope, it) }
            poll?.let { poll ->
                val votersByOption = pollResults?.results.orEmpty()
                    .mapNotNull { voterEntry ->
                        val concrete = voterEntry as? org.monogram.mtproto.tl.generated.cloud.layer223.PollAnswerVoters_8e83b26601
                            ?: return@mapNotNull null
                        concrete.option.toByteArray().toList() to MtProtoPollVoterInfo(concrete.voters, concrete.chosen)
                    }.toMap()
                pollPayloads?.upsert(
                    pollId = poll.id,
                    question = (poll.question as? org.monogram.mtproto.tl.generated.cloud.layer223.TextWithEntities_d094604bd3)?.text ?: "",
                    optionLabels = poll.answers.mapNotNull { answer ->
                        val answerText = (answer as? PollAnswer_ef4c0287fd)?.text
                        (answerText as? org.monogram.mtproto.tl.generated.cloud.layer223.TextWithEntities_d094604bd3)?.text
                    },
                    totalVoters = pollResults?.totalVoters ?: 0,
                    isClosed = poll.closed,
                    isAnonymous = !poll.publicVoters,
                    voterCountsByOption = votersByOption,
                )
            }
            dao.upsert(entity)
            if (updateDialog) dialogStore?.updateTopMessage(
                scope = scope,
                peerType = MtProtoMessagePeerType.valueOf(entity.peerType),
                peerId = entity.peerId,
                messageId = entity.messageId,
            )
        }
        database?.withTransaction { persistProjection() } ?: persistProjection()
    }

    private fun Message_73e57f95e4.toEntity(scope: MtProtoAuthKeyScope, updatedAt: Long, scheduled: Boolean = false): MtProtoMessageProjectionEntity? = when (this) {
        is MessageEmpty -> peerId?.toPeerKey()?.let { (type, id) -> entity(scope, type, id, this.id, updatedAt, isDeleted = true) }
        is MessageService -> peerId.toPeerKey().let { (type, id) -> entity(scope, type, id, this.id, updatedAt, sender = fromId, date = date, isService = true, isOutgoing = out_, isMentioned = mentioned, isMediaUnread = mediaUnread, isSilent = silent) }
        is Message_7b7ecf54a3 -> peerId.toPeerKey().let { (type, id) -> entity(scope, type, id, this.id, updatedAt, sender = fromId, date = date, text = message, isOutgoing = out_, isMentioned = mentioned, isMediaUnread = mediaUnread, isSilent = silent, isPinned = pinned, editDate = editDate, groupedId = groupedId, hasMedia = media != null, documentId = document()?.id, photoId = photo()?.id, isScheduled = scheduled || fromScheduled,
            mediaType = classifyMedia(media).first, mediaKey = classifyMedia(media).second) }
    }

    private fun classifyMedia(media: org.monogram.mtproto.tl.generated.cloud.layer223.MessageMedia?): Pair<String?, String?> =
        classifyMessageMedia(media)

    private fun org.monogram.mtproto.tl.generated.cloud.layer223.Message_7b7ecf54a3.document() =
        (media as? org.monogram.mtproto.tl.generated.cloud.layer223.MessageMediaDocument)
            ?.document as? org.monogram.mtproto.tl.generated.cloud.layer223.Document_be725c3b31

    private fun org.monogram.mtproto.tl.generated.cloud.layer223.Message_7b7ecf54a3.photo() =
        (media as? org.monogram.mtproto.tl.generated.cloud.layer223.MessageMediaPhoto)
            ?.photo as? org.monogram.mtproto.tl.generated.cloud.layer223.Photo_97e0ed8316

    private fun org.monogram.mtproto.tl.generated.cloud.layer223.Message_7b7ecf54a3.poll() =
        (media as? org.monogram.mtproto.tl.generated.cloud.layer223.MessageMediaPoll)
            ?.poll as? Poll_942021f3e0

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

    private fun entity(scope: MtProtoAuthKeyScope, peerType: MtProtoMessagePeerType, peerId: Long, messageId: Int, updatedAt: Long, sender: Peer? = null, date: Int = 0, text: String? = null, isService: Boolean = false, isDeleted: Boolean = false, isOutgoing: Boolean = false, isMentioned: Boolean = false, isMediaUnread: Boolean = false, isSilent: Boolean = false, isPinned: Boolean = false, editDate: Int? = null, groupedId: Long? = null, hasMedia: Boolean = false, documentId: Long? = null, photoId: Long? = null, isScheduled: Boolean = false, mediaType: String? = null, mediaKey: String? = null): MtProtoMessageProjectionEntity {
        val senderKey = sender?.toPeerKey()
        return MtProtoMessageProjectionEntity(scope.accountSlot, scope.environment.storageName, scope.dcId, peerType.name, peerId, messageId, senderKey?.first?.name, senderKey?.second, date, text, isService, isDeleted, isOutgoing, isMentioned, isMediaUnread, isSilent, isPinned, editDate, groupedId, hasMedia, documentId, photoId, mediaType, mediaKey, isScheduled, updatedAt)
    }

    private fun MtProtoMessageProjectionEntity.toReadModel() = MtProtoMessageReadModel(MtProtoMessagePeerType.valueOf(peerType), peerId, messageId, senderType?.let(MtProtoMessagePeerType::valueOf), senderId, date, text, isService, isDeleted, isOutgoing, isMentioned, isMediaUnread, isSilent, isPinned, editDate, groupedId, hasMedia, documentId, isScheduled, photoId, mediaType, mediaKey)

    private companion object {
        const val MESSAGE_OBJECT_TYPE = "message"
        const val UPDATE_OBJECT_TYPE = "update"
        const val LIVE_UPDATES_OBJECT_TYPE = "live_updates"
        const val MAX_HISTORY_PAGE_SIZE = 100
    }
}
