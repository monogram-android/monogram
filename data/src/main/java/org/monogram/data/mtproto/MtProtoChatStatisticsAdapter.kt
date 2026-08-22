package org.monogram.data.mtproto

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.monogram.data.mtproto.MtProtoChatStatisticsRepository
import org.monogram.domain.models.ChatRevenueStatisticsModel
import org.monogram.domain.models.ChatStatisticsModel
import org.monogram.domain.models.StatisticsGraphModel
import org.monogram.domain.repository.ChatStatisticsRepository

internal class MtProtoChatStatisticsAdapter(
    private val mtProtoFactory: () -> MtProtoChatStatisticsRepository,
) : ChatStatisticsRepository {
    private val mtProto by lazy(LazyThreadSafetyMode.NONE, mtProtoFactory)

    override suspend fun getChatStatistics(chatId: Long, isDark: Boolean): ChatStatisticsModel? = mtProto.getChatStatistics(chatId, isDark)

    override suspend fun getChatRevenueStatistics(chatId: Long, isDark: Boolean): ChatRevenueStatisticsModel? = mtProto.getRevenueStatistics(chatId, isDark)

    override suspend fun loadStatisticsGraph(chatId: Long, token: String, x: Long): StatisticsGraphModel? = mtProto.loadGraph(token, x)

}
