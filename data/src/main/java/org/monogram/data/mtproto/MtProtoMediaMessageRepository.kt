package org.monogram.data.mtproto

import kotlin.random.Random
import org.monogram.domain.models.DialogPeerType
import org.monogram.domain.models.MessageSendOptions
import org.monogram.domain.models.TelegramPeerChatId
import org.monogram.mtproto.tl.generated.cloud.layer223.DocumentAttributeFilename
import org.monogram.mtproto.tl.generated.cloud.layer223.InputMediaUploadedDocument
import org.monogram.mtproto.tl.generated.cloud.layer223.InputMediaUploadedPhoto
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeer
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerChannel
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerChat
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerUser
import org.monogram.mtproto.tl.generated.cloud.layer223.InputReplyToMessage
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.SendMedia

internal interface MtProtoMediaMessageRepository {
    suspend fun sendPhoto(chatId: Long, path: String, caption: String, entities: List<org.monogram.domain.models.MessageEntity>, replyTo: Long?, threadId: Long?, options: MessageSendOptions)
    suspend fun sendDocument(chatId: Long, path: String, caption: String, entities: List<org.monogram.domain.models.MessageEntity>, replyTo: Long?, threadId: Long?, options: MessageSendOptions)
}

internal class TelegramMtProtoMediaMessageRepository(
    private val configSource: TelegramMtProtoBootstrapConfigSource,
    private val transportFactory: MtProtoSessionTransportFactory,
    private val uploader: MtProtoFileUploader,
    private val users: MtProtoUserProjectionStore,
    private val chats: MtProtoChatProjectionStore,
    private val messages: MtProtoMessageProjectionStore,
    private val accountSlot: String = "default",
) : MtProtoMediaMessageRepository {
    override suspend fun sendPhoto(chatId: Long, path: String, caption: String, entities: List<org.monogram.domain.models.MessageEntity>, replyTo: Long?, threadId: Long?, options: MessageSendOptions) =
        send(chatId, caption, entities, replyTo, threadId, options, InputMediaUploadedPhoto(false, uploader.upload(path), null, null))

    override suspend fun sendDocument(chatId: Long, path: String, caption: String, entities: List<org.monogram.domain.models.MessageEntity>, replyTo: Long?, threadId: Long?, options: MessageSendOptions) {
        val uploaded = uploader.upload(path)
        val name = java.io.File(path).name
        send(chatId, caption, entities, replyTo, threadId, options, InputMediaUploadedDocument(false, true, false, uploaded, null, "application/octet-stream", listOf(DocumentAttributeFilename(name)), null, null, null, null))
    }

    private suspend fun send(chatId: Long, caption: String, entities: List<org.monogram.domain.models.MessageEntity>, replyTo: Long?, threadId: Long?, options: MessageSendOptions, media: org.monogram.mtproto.tl.generated.cloud.layer223.InputMedia) {
        require(replyTo == null || replyTo in 1..Int.MAX_VALUE)
        require(threadId == null || threadId in 1..Int.MAX_VALUE)
        val scheduleDate = options.scheduleDate
        require(scheduleDate == null || scheduleDate > 0)
        val config = configSource.createForAccount(accountSlot)
        val scope = MtProtoAuthKeyScope(accountSlot, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId)
        val protocolEntities = entities.map { it.toMtProtoEntity(scope, caption, users) }
        val peer = resolvePeer(scope, chatId)
        transportFactory.open(accountSlot).use { transport ->
            messages.stageLive(scope, transport.execute(SendMedia(options.silent, false, true, false, false, false, false, peer, replyTo?.let { InputReplyToMessage(it.toInt(), threadId?.toInt(), null, null, null, null, null, null) }, media, caption, Random.nextLong(), null, protocolEntities.takeIf { it.isNotEmpty() }, scheduleDate, null, null, null, null, null, null)))
        }
    }

    private suspend fun resolvePeer(scope: MtProtoAuthKeyScope, chatId: Long): InputPeer {
        val decoded = TelegramPeerChatId.decode(chatId)
        return when (decoded.type) {
            DialogPeerType.PRIVATE -> users.get(scope, decoded.id)?.let { InputPeerUser(decoded.id, requireNotNull(it.accessHash)) } ?: error("Missing MTProto user projection")
            DialogPeerType.BASIC_GROUP -> InputPeerChat(decoded.id)
            DialogPeerType.SUPERGROUP, DialogPeerType.CHANNEL -> chats.get(scope, decoded.id)?.let { InputPeerChannel(decoded.id, requireNotNull(it.accessHash)) } ?: error("Missing MTProto chat projection")
            DialogPeerType.UNKNOWN -> error("Cannot send to unknown peer")
        }
    }
}
