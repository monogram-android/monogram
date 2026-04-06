package org.monogram.domain.repository

import org.monogram.domain.models.InlineQueryResultModel

interface InlineBotRepository {
    suspend fun getInlineBotResults(
        botUserId: Long,
        chatId: Long,
        query: String,
        offset: String = ""
    ): InlineBotResultsModel?

    suspend fun sendInlineBotResult(
        chatId: Long,
        queryId: Long,
        resultId: String,
        replyToMsgId: Long? = null,
        threadId: Long? = null
    )

    suspend fun onCallbackQuery(chatId: Long, messageId: Long, data: ByteArray)
}

data class InlineBotResultsModel(
    val queryId: Long,
    val nextOffset: String,
    val results: List<InlineQueryResultModel>,
    val switchPmText: String? = null,
    val switchPmParameter: String? = null
)