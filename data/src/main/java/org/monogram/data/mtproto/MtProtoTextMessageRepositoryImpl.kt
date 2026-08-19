package org.monogram.data.mtproto

import java.security.SecureRandom
import org.monogram.domain.models.DialogPeerType
import org.monogram.domain.models.TelegramPeerChatId
import org.monogram.domain.repository.MtProtoTextMessageRepository
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeer
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerChannel
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerChat
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerUser
import org.monogram.mtproto.tl.generated.cloud.layer223.ReactionEmoji
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.DeleteHistory
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.EditMessage
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.ForwardMessages
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.ReadMentions
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.ReadReactions
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.SendMessage
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.SendScheduledMessages
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.SendReaction
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.UpdatePinnedMessage

/** Basic plain-text sending backed by an authenticated owned MTProto transport. */
internal class MtProtoTextMessageRepositoryImpl(
    private val configSource: TelegramMtProtoBootstrapConfigSource,
    private val transportFactory: MtProtoSessionTransportFactory,
    private val users: MtProtoUserProjectionStore,
    private val chats: MtProtoChatProjectionStore,
    private val messages: MtProtoMessageProjectionStore,
    private val randomId: () -> Long = { SecureRandom().nextLong() },
    private val accountSlot: String = DEFAULT_ACCOUNT_SLOT,
) : MtProtoTextMessageRepository {
    override suspend fun sendText(chatId: Long, peerType: DialogPeerType, text: String) {
        require(text.isNotBlank()) { "Message text must not be blank" }
        val config = configSource.createForAccount(accountSlot)
        val scope = MtProtoAuthKeyScope(accountSlot, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId)
        val peer = resolvePeer(scope, chatId, peerType)
        val transport = transportFactory.open(accountSlot)
        try {
            val updates = transport.execute(
                SendMessage(
                    noWebpage = false,
                    silent = false,
                    background = false,
                    clearDraft = true,
                    noforwards = false,
                    updateStickersetsOrder = false,
                    invertMedia = false,
                    allowPaidFloodskip = false,
                    peer = peer,
                    replyTo = null,
                    message = text,
                    randomId = randomId(),
                    replyMarkup = null,
                    entities = null,
                    scheduleDate = null,
                    scheduleRepeatPeriod = null,
                    sendAs = null,
                    quickReplyShortcut = null,
                    effect = null,
                    allowPaidStars = null,
                    suggestedPost = null,
                )
            )
            messages.stageLive(scope, updates)
        } finally {
            transport.close()
        }
    }

    override suspend fun editText(chatId: Long, peerType: DialogPeerType, messageId: Long, text: String) {
        require(text.isNotBlank()) { "Message text must not be blank" }
        require(messageId in 1..Int.MAX_VALUE) { "MTProto message id must fit a positive int" }
        val config = configSource.createForAccount(accountSlot)
        val scope = MtProtoAuthKeyScope(accountSlot, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId)
        val peer = resolvePeer(scope, chatId, peerType)
        val transport = transportFactory.open(accountSlot)
        try {
            val updates = transport.execute(
                EditMessage(
                    noWebpage = false,
                    invertMedia = false,
                    peer = peer,
                    id = messageId.toInt(),
                    message = text,
                    media = null,
                    replyMarkup = null,
                    entities = null,
                    scheduleDate = null,
                    scheduleRepeatPeriod = null,
                    quickReplyShortcutId = null,
                )
            )
            messages.stageLive(scope, updates)
        } finally {
            transport.close()
        }
    }

    override suspend fun setEmojiReaction(
        chatId: Long,
        peerType: DialogPeerType,
        messageId: Long,
        emoji: String?,
    ) {
        require(messageId in 1..Int.MAX_VALUE) { "MTProto message id must fit a positive int" }
        val config = configSource.createForAccount(accountSlot)
        val scope = MtProtoAuthKeyScope(accountSlot, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId)
        val peer = resolvePeer(scope, chatId, peerType)
        val transport = transportFactory.open(accountSlot)
        try {
            val updates = transport.execute(
                SendReaction(
                    big = false,
                    addToRecent = emoji != null,
                    peer = peer,
                    msgId = messageId.toInt(),
                    reaction = emoji?.let { listOf(ReactionEmoji(it)) },
                )
            )
            messages.stageLive(scope, updates)
        } finally {
            transport.close()
        }
    }

    override suspend fun setPinned(
        chatId: Long,
        peerType: DialogPeerType,
        messageId: Long,
        pinned: Boolean,
    ) {
        require(messageId in 1..Int.MAX_VALUE) { "MTProto message id must fit a positive int" }
        val config = configSource.createForAccount(accountSlot)
        val scope = MtProtoAuthKeyScope(accountSlot, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId)
        val peer = resolvePeer(scope, chatId, peerType)
        val transport = transportFactory.open(accountSlot)
        try {
            val updates = transport.execute(UpdatePinnedMessage(false, !pinned, false, peer, messageId.toInt()))
            messages.stageLive(scope, updates)
        } finally {
            transport.close()
        }
    }

    override suspend fun forwardToSelf(
        chatId: Long,
        peerType: DialogPeerType,
        messageId: Long,
    ) {
        require(messageId in 1..Int.MAX_VALUE) { "MTProto message id must fit a positive int" }
        val config = configSource.createForAccount(accountSlot)
        val scope = MtProtoAuthKeyScope(accountSlot, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId)
        val peer = resolvePeer(scope, chatId, peerType)
        val transport = transportFactory.open(accountSlot)
        try {
            val updates = transport.execute(
                ForwardMessages(
                    silent = false,
                    background = false,
                    withMyScore = false,
                    dropAuthor = true,
                    dropMediaCaptions = false,
                    noforwards = false,
                    allowPaidFloodskip = false,
                    fromPeer = peer,
                    id = listOf(messageId.toInt()),
                    randomId = listOf(randomId()),
                    toPeer = peer,
                    topMsgId = null,
                    replyTo = null,
                    scheduleDate = null,
                    scheduleRepeatPeriod = null,
                    sendAs = null,
                    quickReplyShortcut = null,
                    effect = null,
                    videoTimestamp = null,
                    allowPaidStars = null,
                    suggestedPost = null,
                )
            )
            messages.stageLive(scope, updates)
        } finally {
            transport.close()
        }
    }

    override suspend fun sendScheduledNow(
        chatId: Long,
        peerType: DialogPeerType,
        messageId: Long,
    ) {
        require(messageId in 1..Int.MAX_VALUE) { "MTProto message id must fit a positive int" }
        val config = configSource.createForAccount(accountSlot)
        val scope = MtProtoAuthKeyScope(accountSlot, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId)
        val peer = resolvePeer(scope, chatId, peerType)
        val transport = transportFactory.open(accountSlot)
        try {
            val updates = transport.execute(SendScheduledMessages(peer, listOf(messageId.toInt())))
            messages.stageLive(scope, updates)
        } finally {
            transport.close()
        }
    }

    override suspend fun clearHistory(
        chatId: Long,
        peerType: DialogPeerType,
        revoke: Boolean,
    ) {
        val config = configSource.createForAccount(accountSlot)
        val scope = MtProtoAuthKeyScope(accountSlot, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId)
        val peer = resolvePeer(scope, chatId, peerType)
        val transport = transportFactory.open(accountSlot)
        try {
            transport.execute(DeleteHistory(false, revoke, peer, 0, null, null))
        } finally {
            transport.close()
        }
    }

    override suspend fun markMentionsRead(chatId: Long, peerType: DialogPeerType) {
        executeReceipt(chatId, peerType) { peer -> ReadMentions(peer, null) }
    }

    override suspend fun markReactionsRead(chatId: Long, peerType: DialogPeerType) {
        executeReceipt(chatId, peerType) { peer -> ReadReactions(peer, null, null) }
    }

    private suspend fun executeReceipt(
        chatId: Long,
        peerType: DialogPeerType,
        request: (InputPeer) -> org.monogram.mtproto.tl.runtime.TlMethod<*>,
    ) {
        val config = configSource.createForAccount(accountSlot)
        val scope = MtProtoAuthKeyScope(accountSlot, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId)
        val peer = resolvePeer(scope, chatId, peerType)
        val transport = transportFactory.open(accountSlot)
        try {
            @Suppress("UNCHECKED_CAST")
            transport.execute(request(peer) as org.monogram.mtproto.tl.runtime.TlMethod<Any>)
        } finally {
            transport.close()
        }
    }

    private suspend fun resolvePeer(
        scope: MtProtoAuthKeyScope,
        chatId: Long,
        peerType: DialogPeerType,
    ): InputPeer {
        val peer = TelegramPeerChatId.decode(chatId, peerType == DialogPeerType.CHANNEL)
        return when (peer.type) {
            DialogPeerType.PRIVATE -> {
                val user = requireNotNull(users.get(scope, peer.id)) { "Missing MTProto user projection: ${peer.id}" }
                InputPeerUser(peer.id, requireNotNull(user.accessHash) { "Missing MTProto user access hash: ${peer.id}" })
            }

            DialogPeerType.BASIC_GROUP -> InputPeerChat(peer.id)
            DialogPeerType.SUPERGROUP,
            DialogPeerType.CHANNEL -> {
                val chat = requireNotNull(chats.get(scope, peer.id)) { "Missing MTProto chat projection: ${peer.id}" }
                InputPeerChannel(peer.id, requireNotNull(chat.accessHash) { "Missing MTProto channel access hash: ${peer.id}" })
            }

            DialogPeerType.UNKNOWN -> error("Cannot send to an unknown peer")
        }
    }

    private companion object {
        const val DEFAULT_ACCOUNT_SLOT = "default"
    }
}
