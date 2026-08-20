package org.monogram.data.mtproto

import org.monogram.domain.models.DialogPeerType
import org.monogram.domain.models.TelegramPeerChatId
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeer
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerChannel
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerChat
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerUser
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.GetAllDrafts
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.SaveDraft

internal interface MtProtoDraftRepository {
    suspend fun getDraft(chatId: Long, threadId: Long?): String?
    suspend fun saveDraft(chatId: Long, text: String, replyToMsgId: Long?, threadId: Long?)
}

internal class MtProtoDraftRepositoryImpl(
    private val configSource: TelegramMtProtoBootstrapConfigSource,
    private val transportFactory: MtProtoSessionTransportFactory,
    private val users: MtProtoUserProjectionStore,
    private val chats: MtProtoChatProjectionStore,
    private val drafts: MtProtoDraftStore,
    private val accountSlot: String = DEFAULT_ACCOUNT_SLOT,
) : MtProtoDraftRepository {
    override suspend fun getDraft(chatId: Long, threadId: Long?): String? {
        require(threadId == null) { "MTProto topic drafts are not supported" }
        val config = configSource.createForAccount(accountSlot)
        val scope = MtProtoAuthKeyScope(accountSlot, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId)
        val transport = transportFactory.open(accountSlot)
        try {
            drafts.stageLive(scope, transport.execute(GetAllDrafts))
        } finally {
            transport.close()
        }
        return drafts.get(scope, chatId)?.takeIf(String::isNotEmpty)
    }

    override suspend fun saveDraft(chatId: Long, text: String, replyToMsgId: Long?, threadId: Long?) {
        require(threadId == null) { "MTProto topic drafts are not supported" }
        require(replyToMsgId == null) { "MTProto reply drafts are not supported" }
        val config = configSource.createForAccount(accountSlot)
        val scope = MtProtoAuthKeyScope(accountSlot, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId)
        val transport = transportFactory.open(accountSlot)
        try {
            transport.execute(
                SaveDraft(
                    noWebpage = false,
                    invertMedia = false,
                    replyTo = null,
                    peer = resolvePeer(scope, chatId),
                    message = text,
                    entities = null,
                    media = null,
                    effect = null,
                    suggestedPost = null,
                )
            )
            drafts.upsert(scope, chatId, text)
        } finally {
            transport.close()
        }
    }

    private suspend fun resolvePeer(scope: MtProtoAuthKeyScope, chatId: Long): InputPeer {
        val peer = TelegramPeerChatId.decode(chatId)
        return when (peer.type) {
            DialogPeerType.PRIVATE -> {
                val user = requireNotNull(users.get(scope, peer.id)) { "Missing MTProto user projection: ${peer.id}" }
                InputPeerUser(peer.id, requireNotNull(user.accessHash) { "Missing MTProto user access hash: ${peer.id}" })
            }
            DialogPeerType.BASIC_GROUP -> InputPeerChat(peer.id)
            DialogPeerType.SUPERGROUP, DialogPeerType.CHANNEL -> {
                val chat = requireNotNull(chats.get(scope, peer.id)) { "Missing MTProto chat projection: ${peer.id}" }
                InputPeerChannel(peer.id, requireNotNull(chat.accessHash) { "Missing MTProto channel access hash: ${peer.id}" })
            }
            DialogPeerType.UNKNOWN -> error("Cannot save a draft for an unknown peer")
        }
    }

    private companion object {
        const val DEFAULT_ACCOUNT_SLOT = "default"
    }
}
