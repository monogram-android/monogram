package org.monogram.data.mtproto

import org.monogram.domain.models.StatisticsGraphModel
import org.monogram.mtproto.tl.generated.cloud.layer223.DataJson_340cf194d4
import org.monogram.mtproto.tl.generated.cloud.layer223.StatsGraphAsync
import org.monogram.mtproto.tl.generated.cloud.layer223.StatsGraphError
import org.monogram.mtproto.tl.generated.cloud.layer223.StatsGraph_df47e5db04
import org.monogram.mtproto.tl.generated.cloud.layer223.stats.LoadAsyncGraph

internal fun interface MtProtoChatStatisticsRepository {
    suspend fun loadGraph(token: String, x: Long): StatisticsGraphModel
}

internal class MtProtoChatStatisticsRepositoryImpl(
    private val transportFactory: MtProtoSessionTransportFactory,
    private val accountSlot: String = DEFAULT_ACCOUNT_SLOT,
) : MtProtoChatStatisticsRepository {
    override suspend fun loadGraph(token: String, x: Long): StatisticsGraphModel {
        require(token.isNotBlank()) { "MTProto statistics graph token must not be blank" }
        return transportFactory.open(accountSlot).use { transport ->
            when (val response = transport.execute(LoadAsyncGraph(token, x))) {
                is StatsGraph_df47e5db04 -> StatisticsGraphModel.Data(
                    jsonData = (response.json as? DataJson_340cf194d4)?.data_
                        ?: error("Unsupported MTProto statistics graph JSON payload"),
                    zoomToken = response.zoomToken,
                )
                is StatsGraphAsync -> StatisticsGraphModel.Async(response.token)
                is StatsGraphError -> StatisticsGraphModel.Error(response.error)
            }
        }
    }

    private companion object { const val DEFAULT_ACCOUNT_SLOT = "default" }
}
