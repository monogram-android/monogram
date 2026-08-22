package org.monogram.data.mtproto

import org.monogram.mtproto.tl.generated.cloud.layer223.Document_be725c3b31
import org.monogram.mtproto.tl.generated.cloud.layer223.InputChannel_d22292516d
import org.monogram.mtproto.tl.generated.cloud.layer223.InputMessageId
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerChannel
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerChat
import org.monogram.mtproto.tl.generated.cloud.layer223.MessageMediaDocument
import org.monogram.mtproto.tl.generated.cloud.layer223.MessageMediaPhoto
import org.monogram.mtproto.tl.generated.cloud.layer223.Photo_97e0ed8316
import org.monogram.mtproto.tl.generated.cloud.layer223.channels.GetMessages as GetChannelMessages
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.ChannelMessages
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.GetMessages
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.Messages_3c331441fb

/** Refreshes an expired file provenance by re-fetching its origin message; returns true on success. */
internal fun interface MtProtoFileReferenceRefresher {
    suspend fun refresh(documentId: Long, photoId: Long, chatId: Long, messageId: Long): Boolean
}

/**
 * Re-fetches the message that carries a file and re-stages its media, refreshing the stored
 * `file_reference`. Telegram file references expire whenever the origin message changes context,
 * so downloads must recover through the provenance path instead of failing permanently.
 */
internal class MtProtoServerFileReferenceRefresher(
    private val configSource: TelegramMtProtoBootstrapConfigSource,
    private val transportFactory: MtProtoSessionTransportFactory,
    private val chats: MtProtoChatProjectionStore,
    private val documentLocations: MtProtoDocumentLocationStore,
    private val photoLocations: MtProtoPhotoLocationStore,
    private val accountSlot: String = DEFAULT_ACCOUNT_SLOT,
) : MtProtoFileReferenceRefresher {
    override suspend fun refresh(documentId: Long, photoId: Long, chatId: Long, messageId: Long): Boolean {
        val config = configSource.createForAccount(accountSlot)
        val scope = MtProtoAuthKeyScope(accountSlot, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId)
        val chat = chats.get(scope, chatId) ?: return false
        val channelAccessHash = if (chat.type == MtProtoChatType.SUPERGROUP || chat.type == MtProtoChatType.CHANNEL) {
            chat.accessHash ?: return false
        } else {
            null
        }
        val messageIdInt = messageId.toInt().takeIf { it.toLong() == messageId }
            ?: return false
        transportFactory.open(accountSlot).use { transport ->
            val messages = when (
                val result = if (channelAccessHash != null) {
                    transport.execute(
                        GetChannelMessages(InputChannel_d22292516d(chatId, channelAccessHash), listOf(InputMessageId(messageIdInt))),
                    )
                } else {
                    transport.execute(GetMessages(listOf(InputMessageId(messageIdInt))))
                }
            ) {
                is Messages_3c331441fb -> result.messages
                is ChannelMessages -> result.messages
                else -> return false
            }
            var refreshed = false
            for (message in messages.filterIsInstance<org.monogram.mtproto.tl.generated.cloud.layer223.Message_7b7ecf54a3>()) {
                (message.media as? MessageMediaDocument)?.document
                    ?.let { it as? Document_be725c3b31 }
                    ?.let { document ->
                        documentLocations.upsert(scope, document)
                        if (document.id == documentId) refreshed = true
                    }
                (message.media as? MessageMediaPhoto)?.photo
                    ?.let { it as? Photo_97e0ed8316 }
                    ?.let { photo ->
                        photoLocations.upsert(scope, photo)
                        if (photo.id == photoId) refreshed = true
                    }
            }
            return refreshed
        }
    }

    private companion object {
        const val DEFAULT_ACCOUNT_SLOT = "default"
    }
}
