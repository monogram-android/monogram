package org.monogram.data.mtproto

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.domain.models.StatisticsGraphModel
import org.monogram.mtproto.tl.generated.cloud.layer223.DataJson_340cf194d4
import org.monogram.mtproto.tl.generated.cloud.layer223.StatsGraphAsync
import org.monogram.mtproto.tl.generated.cloud.layer223.StatsGraphError
import org.monogram.mtproto.tl.generated.cloud.layer223.StatsGraph_df47e5db04
import org.monogram.mtproto.tl.generated.cloud.layer223.stats.LoadAsyncGraph
import org.monogram.mtproto.tl.runtime.TlMethod
import org.monogram.mtproto.transport.MtProtoRpcTransport

class MtProtoChatStatisticsRepositoryTest {
    @Test
    fun `loads graph data through owned transport`() = runBlocking {
        val transport = Transport(StatsGraph_df47e5db04(DataJson_340cf194d4("{\"v\":1}"), "zoom"))
        val repository = MtProtoChatStatisticsRepositoryImpl(MtProtoSessionTransportFactory { transport })

        assertEquals(StatisticsGraphModel.Data("{\"v\":1}", "zoom"), repository.loadGraph("token", 0))
        assertEquals(LoadAsyncGraph("token", 0), transport.requests.single())
        assertTrue(transport.closed)
    }

    @Test
    fun `maps pending and error graph responses`() = runBlocking {
        val async = MtProtoChatStatisticsRepositoryImpl(MtProtoSessionTransportFactory { Transport(StatsGraphAsync("next")) })
        val error = MtProtoChatStatisticsRepositoryImpl(MtProtoSessionTransportFactory { Transport(StatsGraphError("denied")) })

        assertEquals(StatisticsGraphModel.Async("next"), async.loadGraph("token", 7))
        assertEquals(StatisticsGraphModel.Error("denied"), error.loadGraph("token", 7))
    }

    private class Transport(private val response: Any) : MtProtoRpcTransport {
        val requests = mutableListOf<TlMethod<*>>()
        var closed = false
        @Suppress("UNCHECKED_CAST")
        override suspend fun <R> execute(method: TlMethod<R>): R {
            requests += method
            return response as R
        }
        override fun close() { closed = true }
    }
}
