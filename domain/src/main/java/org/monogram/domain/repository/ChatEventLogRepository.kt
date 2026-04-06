package org.monogram.domain.repository

import org.monogram.domain.models.ChatEventLogFiltersModel
import org.monogram.domain.models.ChatEventModel

interface ChatEventLogRepository {
    suspend fun getChatEventLog(
        chatId: Long,
        query: String = "",
        fromEventId: Long = 0,
        limit: Int = 50,
        filters: ChatEventLogFiltersModel = ChatEventLogFiltersModel(),
        userIds: List<Long> = emptyList()
    ): List<ChatEventModel>
}