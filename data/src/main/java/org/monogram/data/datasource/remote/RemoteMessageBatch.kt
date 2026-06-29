package org.monogram.data.datasource.remote

import org.drinkless.tdlib.TdApi
import org.monogram.domain.models.MessageModel

data class MessageMapOptions(
    val resolveReplyPreviewFromNetwork: Boolean = true,
    val allowAutoDownload: Boolean = true
)

data class RemoteMessageBatch(
    val rawMessages: List<TdApi.Message>,
    val models: List<MessageModel>
)

data class RemoteOlderMessagesPage(
    val rawMessages: List<TdApi.Message>,
    val models: List<MessageModel>,
    val reachedOldest: Boolean,
    val isRemote: Boolean = true
)
