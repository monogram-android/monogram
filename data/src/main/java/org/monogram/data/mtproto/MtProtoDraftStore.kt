package org.monogram.data.mtproto

import org.monogram.data.db.dao.MtProtoDraftProjectionDao
import org.monogram.data.db.model.MtProtoDraftProjectionEntity
import org.monogram.domain.models.DialogPeerType
import org.monogram.domain.models.TelegramPeerChatId
import org.monogram.mtproto.tl.generated.cloud.layer223.DraftMessageEmpty
import org.monogram.mtproto.tl.generated.cloud.layer223.DraftMessage_3aaf32dfa6
import org.monogram.mtproto.tl.generated.cloud.layer223.Peer
import org.monogram.mtproto.tl.generated.cloud.layer223.PeerChannel
import org.monogram.mtproto.tl.generated.cloud.layer223.PeerChat
import org.monogram.mtproto.tl.generated.cloud.layer223.PeerUser
import org.monogram.mtproto.tl.generated.cloud.layer223.UpdateDraftMessage
import org.monogram.mtproto.tl.generated.cloud.layer223.UpdateShort
import org.monogram.mtproto.tl.generated.cloud.layer223.UpdatesCombined
import org.monogram.mtproto.tl.generated.cloud.layer223.Updates_02c952992b
import org.monogram.mtproto.tl.generated.cloud.layer223.Updates_faf6aaa3d5

internal interface MtProtoDraftStore {
    suspend fun get(scope: MtProtoAuthKeyScope, chatId: Long): String?
    suspend fun upsert(scope: MtProtoAuthKeyScope, chatId: Long, text: String)
    suspend fun stageLive(scope: MtProtoAuthKeyScope, envelope: Updates_faf6aaa3d5)
    suspend fun deleteAccount(accountSlot: String, environment: MtProtoEnvironment)
}

internal object NoOpMtProtoDraftStore : MtProtoDraftStore {
    override suspend fun get(scope: MtProtoAuthKeyScope, chatId: Long): String? = null
    override suspend fun upsert(scope: MtProtoAuthKeyScope, chatId: Long, text: String) = Unit
    override suspend fun stageLive(scope: MtProtoAuthKeyScope, envelope: Updates_faf6aaa3d5) = Unit
    override suspend fun deleteAccount(accountSlot: String, environment: MtProtoEnvironment) = Unit
}

internal class MtProtoRoomDraftStore(
    private val dao: MtProtoDraftProjectionDao,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : MtProtoDraftStore {
    override suspend fun get(scope: MtProtoAuthKeyScope, chatId: Long): String? {
        val peer = TelegramPeerChatId.decode(chatId)
        return dao.get(scope.accountSlot, scope.environment.storageName, scope.dcId, peer.type.name, peer.id)
    }

    override suspend fun upsert(scope: MtProtoAuthKeyScope, chatId: Long, text: String) {
        val peer = TelegramPeerChatId.decode(chatId)
        dao.upsert(scope.entity(peer.type, peer.id, text))
    }

    override suspend fun stageLive(scope: MtProtoAuthKeyScope, envelope: Updates_faf6aaa3d5) {
        envelope.draftUpdates().forEach { update ->
            if (update.topMsgId != null || update.savedPeerId != null) return@forEach
            val (peerType, peerId) = update.peer.projectionKey()
            when (val draft = update.draft) {
                is DraftMessage_3aaf32dfa6 -> dao.upsert(scope.entity(peerType, peerId, draft.message))
                is DraftMessageEmpty -> dao.upsert(scope.entity(peerType, peerId, ""))
            }
        }
    }

    override suspend fun deleteAccount(accountSlot: String, environment: MtProtoEnvironment) =
        dao.deleteAccount(accountSlot, environment.storageName)

    private fun MtProtoAuthKeyScope.entity(peerType: DialogPeerType, peerId: Long, text: String) =
        MtProtoDraftProjectionEntity(accountSlot, environment.storageName, dcId, peerType.name, peerId, text, nowMillis())

    private fun Updates_faf6aaa3d5.draftUpdates(): List<UpdateDraftMessage> = when (this) {
        is UpdatesCombined -> updates.filterIsInstance<UpdateDraftMessage>()
        is Updates_02c952992b -> updates.filterIsInstance<UpdateDraftMessage>()
        is UpdateShort -> listOfNotNull(update as? UpdateDraftMessage)
        else -> emptyList()
    }

    private fun Peer.projectionKey(): Pair<DialogPeerType, Long> = when (this) {
        is PeerUser -> DialogPeerType.PRIVATE to userId
        is PeerChat -> DialogPeerType.BASIC_GROUP to chatId
        is PeerChannel -> DialogPeerType.CHANNEL to channelId
    }
}
