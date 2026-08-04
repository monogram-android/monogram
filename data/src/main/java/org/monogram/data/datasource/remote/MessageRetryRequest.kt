package org.monogram.data.datasource.remote

import org.drinkless.tdlib.TdApi

internal fun resendTemporaryMessageRequest(
    chatId: Long,
    temporaryMessageId: Long
): TdApi.ResendMessages = TdApi.ResendMessages(
    chatId,
    longArrayOf(temporaryMessageId),
    null,
    0L
)
