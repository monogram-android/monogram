package org.monogram.domain.repository

import org.monogram.domain.models.ChatRevenueStatisticsModel
import org.monogram.domain.models.ChatStatisticsModel
import org.monogram.domain.models.StatisticsGraphModel

interface ChatStatisticsRepository {
    suspend fun getChatStatistics(chatId: Long, isDark: Boolean): ChatStatisticsModel?
    suspend fun getChatRevenueStatistics(chatId: Long, isDark: Boolean): ChatRevenueStatisticsModel?
    suspend fun loadStatisticsGraph(chatId: Long, token: String, x: Long): StatisticsGraphModel?
}