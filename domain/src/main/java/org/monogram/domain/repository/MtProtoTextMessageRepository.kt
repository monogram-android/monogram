package org.monogram.domain.repository

import org.monogram.domain.models.DialogPeerType

/** Sends a plain text message through the selected account's owned MTProto session. */
interface MtProtoTextMessageRepository {
    suspend fun sendText(
        chatId: Long,
        peerType: DialogPeerType,
        text: String,
    )
}
