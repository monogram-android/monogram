package org.monogram.data.mtproto

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.domain.models.ChatInteractionType
import org.monogram.domain.models.DialogPeerType
import org.monogram.domain.models.RevenueAmountModel
import org.monogram.domain.models.StatisticsGraphModel
import org.monogram.domain.models.StatisticsType
import org.monogram.domain.models.TelegramPeerChatId
import org.monogram.mtproto.handshake.MtProtoHandshakeConfig
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerChannel
import org.monogram.mtproto.tl.generated.cloud.layer223.PostInteractionCountersMessage
import org.monogram.mtproto.tl.generated.cloud.layer223.PostInteractionCountersStory
import org.monogram.mtproto.tl.generated.cloud.layer223.StatsAbsValueAndPrev_33e6024c6f
import org.monogram.mtproto.tl.generated.cloud.layer223.StatsDateRangeDays_704b9f97f7
import org.monogram.mtproto.tl.generated.cloud.layer223.StatsPercentValue_e2865ffc72
import org.monogram.mtproto.tl.generated.cloud.layer223.StarsRevenueStatus_9c5d49c845
import org.monogram.mtproto.tl.generated.cloud.layer223.StarsTonAmount
import org.monogram.mtproto.tl.generated.cloud.layer223.stats.BroadcastStats_6504ee4edb
import org.monogram.mtproto.tl.generated.cloud.layer223.stats.GetBroadcastStats
import org.monogram.mtproto.tl.generated.cloud.layer223.payments.GetStarsRevenueStats
import org.monogram.mtproto.tl.generated.cloud.layer223.payments.StarsRevenueStats_c001a03e15
import org.monogram.mtproto.transport.CloudLayer223ConnectionConfig
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

    @Test
    fun `loads TON revenue statistics through owned transport`() = runBlocking {
        val transport = Transport(
            StarsRevenueStats_c001a03e15(
                topHoursGraph = graph(),
                revenueGraph = graph(),
                status = StarsRevenueStatus_9c5d49c845(
                    withdrawalEnabled = true,
                    currentBalance = StarsTonAmount(20),
                    availableBalance = StarsTonAmount(10),
                    overallRevenue = StarsTonAmount(30),
                    nextWithdrawalAt = null,
                ),
                usdRate = 20_000_000.0,
            ),
        )
        val repository = MtProtoChatStatisticsRepositoryImpl(
            transportFactory = MtProtoSessionTransportFactory { transport },
            configSource = TelegramMtProtoBootstrapConfigSource { config() },
            chats = channel(),
        )

        val result = repository.getRevenueStatistics(TelegramPeerChatId.encode(DialogPeerType.CHANNEL, 5), isDark = true)

        assertEquals(RevenueAmountModel("TON", 20, 10), result.revenueAmount)
        assertEquals(2.0, result.usdRate, 0.0)
        assertEquals(
            GetStarsRevenueStats(true, ton = true, InputPeerChannel(5, 99)),
            transport.requests.single(),
        )
        assertTrue(transport.closed)
    }

    @Test
    fun `rejects revenue responses without the required hourly graph`() = runBlocking {
        val transport = Transport(
            StarsRevenueStats_c001a03e15(
                topHoursGraph = null,
                revenueGraph = graph(),
                status = StarsRevenueStatus_9c5d49c845(
                    withdrawalEnabled = true,
                    currentBalance = StarsTonAmount(20),
                    availableBalance = StarsTonAmount(10),
                    overallRevenue = StarsTonAmount(30),
                    nextWithdrawalAt = null,
                ),
                usdRate = 20_000_000.0,
            ),
        )
        val repository = MtProtoChatStatisticsRepositoryImpl(
            transportFactory = MtProtoSessionTransportFactory { transport },
            configSource = TelegramMtProtoBootstrapConfigSource { config() },
            chats = channel(),
        )

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.getRevenueStatistics(TelegramPeerChatId.encode(DialogPeerType.CHANNEL, 5), false) }
        }
        assertTrue(transport.closed)
    }

    @Test
    fun `loads complete broadcast statistics through owned transport`() = runBlocking {
        val transport = Transport(broadcastStatistics())
        val repository = MtProtoChatStatisticsRepositoryImpl(
            transportFactory = MtProtoSessionTransportFactory { transport },
            configSource = TelegramMtProtoBootstrapConfigSource { config() },
            chats = channel(),
        )

        val result = repository.getChatStatistics(TelegramPeerChatId.encode(DialogPeerType.CHANNEL, 5), isDark = true)

        assertEquals(StatisticsType.CHANNEL, result.type)
        assertEquals(12.0, result.memberCount.value, 0.0)
        assertEquals(20.0, result.memberCount.growthRatePercentage, 0.0)
        assertEquals(25.0, requireNotNull(result.enabledNotificationsPercentage), 0.0)
        assertEquals(listOf(ChatInteractionType.MESSAGE, ChatInteractionType.STORY), result.recentInteractions.map { it.type })
        assertTrue(transport.requests.single() is GetBroadcastStats)
        assertTrue(transport.closed)
    }

    private fun broadcastStatistics() = BroadcastStats_6504ee4edb(
        period = StatsDateRangeDays_704b9f97f7(1, 2),
        followers = StatsAbsValueAndPrev_33e6024c6f(12.0, 10.0),
        viewsPerPost = value(), sharesPerPost = value(), reactionsPerPost = value(),
        viewsPerStory = value(), sharesPerStory = value(), reactionsPerStory = value(),
        enabledNotifications = StatsPercentValue_e2865ffc72(1.0, 4.0),
        growthGraph = graph(), followersGraph = graph(), muteGraph = graph(), topHoursGraph = graph(),
        interactionsGraph = graph(), ivInteractionsGraph = graph(), viewsBySourceGraph = graph(),
        newFollowersBySourceGraph = graph(), languagesGraph = graph(), reactionsByEmotionGraph = graph(),
        storyInteractionsGraph = graph(), storyReactionsByEmotionGraph = graph(),
        recentPostsInteractions = listOf(PostInteractionCountersMessage(4, 3, 2, 1), PostInteractionCountersStory(5, 6, 7, 8)),
    )

    private fun value() = StatsAbsValueAndPrev_33e6024c6f(1.0, 1.0)
    private fun graph() = StatsGraphError("unavailable")
    private fun channel() = object : MtProtoChatProjectionStore by NoOpMtProtoChatProjectionStore {
        override suspend fun get(scope: MtProtoAuthKeyScope, chatId: Long) = MtProtoChatReadModel(
            chatId = chatId,
            type = MtProtoChatType.CHANNEL,
            accessHash = 99L,
            title = null,
            username = null,
            participantsCount = null,
            isDeleted = false,
            isForbidden = false,
            isLeft = false,
            isDeactivated = false,
            isVerified = false,
            isRestricted = false,
            isScam = false,
            isFake = false,
            isForum = false,
            signaturesEnabled = false,
            signatureProfilesEnabled = false,
            forumTabs = false,
            isMin = false,
        )
    }
    private fun config() = TelegramMtProtoBootstrapConfig(
        TelegramMtProtoEndpoint(2, "dc", 443),
        MtProtoHandshakeConfig(2, listOf("key")),
        CloudLayer223ConnectionConfig(1, "device", "system", "app", "en"),
    )

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
