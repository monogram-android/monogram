package org.monogram.data.repository

import org.monogram.data.datasource.remote.UserRemoteDataSource
import org.monogram.data.mapper.user.toDomain
import org.monogram.domain.models.ChatRevenueStatisticsModel
import org.monogram.domain.models.ChatStatisticsModel
import org.monogram.domain.models.StatisticsGraphModel
import org.monogram.domain.repository.ChatStatisticsRepository

class ChatStatisticsRepositoryImpl(
    private val remote: UserRemoteDataSource
) : ChatStatisticsRepository {

    override suspend fun getChatStatistics(chatId: Long, isDark: Boolean): ChatStatisticsModel? {
        val stats = remote.getChatStatistics(chatId, isDark) ?: return null
        return stats.toDomain()
    }

    override suspend fun getChatRevenueStatistics(
        chatId: Long,
        isDark: Boolean
    ): ChatRevenueStatisticsModel? {
        val stats = remote.getChatRevenueStatistics(chatId, isDark) ?: return null
        return stats.toDomain()
    }

    override suspend fun loadStatisticsGraph(
        chatId: Long,
        token: String,
        x: Long
    ): StatisticsGraphModel? {
        val graph = remote.getStatisticsGraph(chatId, token, x) ?: return null
        return graph.toDomain()
    }
}