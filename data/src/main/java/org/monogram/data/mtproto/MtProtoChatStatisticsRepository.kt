package org.monogram.data.mtproto

import org.monogram.domain.models.ChatInteractionInfoModel
import org.monogram.domain.models.ChatInteractionType
import org.monogram.domain.models.ChatStatisticsModel
import org.monogram.domain.models.DateRangeModel
import org.monogram.domain.models.StatisticsGraphModel
import org.monogram.domain.models.StatisticsType
import org.monogram.domain.models.StatisticsValueModel
import org.monogram.domain.models.TelegramPeerChatId
import org.monogram.domain.models.TopAdministratorModel
import org.monogram.domain.models.TopInviterModel
import org.monogram.domain.models.TopSenderModel
import org.monogram.mtproto.tl.generated.cloud.layer223.DataJson_340cf194d4
import org.monogram.mtproto.tl.generated.cloud.layer223.InputChannel_d22292516d
import org.monogram.mtproto.tl.generated.cloud.layer223.PostInteractionCountersMessage
import org.monogram.mtproto.tl.generated.cloud.layer223.PostInteractionCountersStory
import org.monogram.mtproto.tl.generated.cloud.layer223.StatsAbsValueAndPrev_33e6024c6f
import org.monogram.mtproto.tl.generated.cloud.layer223.StatsDateRangeDays_704b9f97f7
import org.monogram.mtproto.tl.generated.cloud.layer223.StatsGraphAsync
import org.monogram.mtproto.tl.generated.cloud.layer223.StatsGraphError
import org.monogram.mtproto.tl.generated.cloud.layer223.StatsGraph_df47e5db04
import org.monogram.mtproto.tl.generated.cloud.layer223.StatsPercentValue_e2865ffc72
import org.monogram.mtproto.tl.generated.cloud.layer223.StatsGroupTopAdmin_e251e2be8c
import org.monogram.mtproto.tl.generated.cloud.layer223.StatsGroupTopInviter_deb90aa57a
import org.monogram.mtproto.tl.generated.cloud.layer223.StatsGroupTopPoster_4a1989eb4e
import org.monogram.mtproto.tl.generated.cloud.layer223.stats.BroadcastStats_6504ee4edb
import org.monogram.mtproto.tl.generated.cloud.layer223.stats.GetBroadcastStats
import org.monogram.mtproto.tl.generated.cloud.layer223.stats.GetMegagroupStats
import org.monogram.mtproto.tl.generated.cloud.layer223.stats.LoadAsyncGraph
import org.monogram.mtproto.tl.generated.cloud.layer223.stats.MegagroupStats_1adebce85d

internal interface MtProtoChatStatisticsRepository {
    suspend fun getChatStatistics(chatId: Long, isDark: Boolean): ChatStatisticsModel
    suspend fun loadGraph(token: String, x: Long): StatisticsGraphModel
}

internal class MtProtoChatStatisticsRepositoryImpl(
    private val transportFactory: MtProtoSessionTransportFactory,
    private val configSource: TelegramMtProtoBootstrapConfigSource? = null,
    private val chats: MtProtoChatProjectionStore? = null,
    private val users: MtProtoUserProjectionStore? = null,
    private val accountSlot: String = DEFAULT_ACCOUNT_SLOT,
) : MtProtoChatStatisticsRepository {
    override suspend fun getChatStatistics(chatId: Long, isDark: Boolean): ChatStatisticsModel {
        val config = requireNotNull(configSource) { "MTProto chat statistics are not configured" }.createForAccount(accountSlot)
        val chatStore = requireNotNull(chats) { "MTProto chat statistics are not configured" }
        val scope = MtProtoAuthKeyScope(accountSlot, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId)
        val peer = TelegramPeerChatId.decode(chatId, isChannel = false)
        val chat = requireNotNull(chatStore.get(scope, peer.id)) { "Missing MTProto chat projection: ${peer.id}" }
        val accessHash = requireNotNull(chat.accessHash) { "Missing MTProto channel access hash: ${peer.id}" }
        return transportFactory.open(accountSlot).use { transport ->
            when (chat.type) {
                MtProtoChatType.CHANNEL -> (transport.execute(GetBroadcastStats(isDark, InputChannel_d22292516d(peer.id, accessHash))) as? BroadcastStats_6504ee4edb)
                    ?.toDomain() ?: error("Unsupported MTProto broadcast statistics response")
                MtProtoChatType.SUPERGROUP -> (transport.execute(GetMegagroupStats(isDark, InputChannel_d22292516d(peer.id, accessHash))) as? MegagroupStats_1adebce85d)
                    ?.also { users?.upsert(scope, it.users) }
                    ?.toDomain() ?: error("Unsupported MTProto megagroup statistics response")
                MtProtoChatType.BASIC_GROUP -> error("MTProto statistics require a channel or supergroup")
            }
        }
    }

    override suspend fun loadGraph(token: String, x: Long): StatisticsGraphModel {
        require(token.isNotBlank()) { "MTProto statistics graph token must not be blank" }
        return transportFactory.open(accountSlot).use { transport ->
            transport.execute(LoadAsyncGraph(token, x)).toDomainGraph()
        }
    }

    private companion object { const val DEFAULT_ACCOUNT_SLOT = "default" }
}

private fun BroadcastStats_6504ee4edb.toDomain() = ChatStatisticsModel(
    type = StatisticsType.CHANNEL,
    period = (period as? StatsDateRangeDays_704b9f97f7)?.toDateRange()
        ?: error("Unsupported MTProto statistics period"),
    memberCount = (followers as? StatsAbsValueAndPrev_33e6024c6f)?.toStatisticsValue()
        ?: error("Unsupported MTProto follower statistic"),
    meanViewCount = (viewsPerPost as? StatsAbsValueAndPrev_33e6024c6f)?.toStatisticsValue(),
    meanShareCount = (sharesPerPost as? StatsAbsValueAndPrev_33e6024c6f)?.toStatisticsValue(),
    meanReactionCount = (reactionsPerPost as? StatsAbsValueAndPrev_33e6024c6f)?.toStatisticsValue(),
    meanStoryViewCount = (viewsPerStory as? StatsAbsValueAndPrev_33e6024c6f)?.toStatisticsValue(),
    meanStoryShareCount = (sharesPerStory as? StatsAbsValueAndPrev_33e6024c6f)?.toStatisticsValue(),
    meanStoryReactionCount = (reactionsPerStory as? StatsAbsValueAndPrev_33e6024c6f)?.toStatisticsValue(),
    enabledNotificationsPercentage = (enabledNotifications as? StatsPercentValue_e2865ffc72)?.toPercentage(),
    recentInteractions = recentPostsInteractions.mapNotNull { it.toInteraction() },
    memberCountGraph = followersGraph.toDomainGraph(),
    joinGraph = growthGraph.toDomainGraph(),
    muteGraph = muteGraph.toDomainGraph(),
    viewCountByHourGraph = topHoursGraph.toDomainGraph(),
    viewCountBySourceGraph = viewsBySourceGraph.toDomainGraph(),
    joinBySourceGraph = newFollowersBySourceGraph.toDomainGraph(),
    languageGraph = languagesGraph.toDomainGraph(),
    messageContentGraph = interactionsGraph.toDomainGraph(),
    actionGraph = ivInteractionsGraph.toDomainGraph(),
    messageReactionGraph = reactionsByEmotionGraph.toDomainGraph(),
    storyInteractionGraph = storyInteractionsGraph.toDomainGraph(),
    storyReactionGraph = storyReactionsByEmotionGraph.toDomainGraph(),
)

private fun MegagroupStats_1adebce85d.toDomain() = ChatStatisticsModel(
    type = StatisticsType.SUPERGROUP,
    period = (period as? StatsDateRangeDays_704b9f97f7)?.toDateRange()
        ?: error("Unsupported MTProto statistics period"),
    memberCount = (members as? StatsAbsValueAndPrev_33e6024c6f)?.toStatisticsValue()
        ?: error("Unsupported MTProto member statistic"),
    messageCount = (messages as? StatsAbsValueAndPrev_33e6024c6f)?.toStatisticsValue(),
    viewerCount = (viewers as? StatsAbsValueAndPrev_33e6024c6f)?.toStatisticsValue(),
    senderCount = (posters as? StatsAbsValueAndPrev_33e6024c6f)?.toStatisticsValue(),
    topSenders = topPosters.map { (it as? StatsGroupTopPoster_4a1989eb4e)?.toTopSender()
        ?: error("Unsupported MTProto top poster") },
    topAdministrators = topAdmins.map { (it as? StatsGroupTopAdmin_e251e2be8c)?.toTopAdministrator()
        ?: error("Unsupported MTProto top administrator") },
    topInviters = topInviters.map { (it as? StatsGroupTopInviter_deb90aa57a)?.toTopInviter()
        ?: error("Unsupported MTProto top inviter") },
    memberCountGraph = membersGraph.toDomainGraph(),
    joinGraph = growthGraph.toDomainGraph(),
    muteGraph = newMembersBySourceGraph.toDomainGraph(),
    joinBySourceGraph = newMembersBySourceGraph.toDomainGraph(),
    languageGraph = languagesGraph.toDomainGraph(),
    messageContentGraph = messagesGraph.toDomainGraph(),
    actionGraph = actionsGraph.toDomainGraph(),
    dayGraph = topHoursGraph.toDomainGraph(),
    weekGraph = weekdaysGraph.toDomainGraph(),
)

private fun StatsDateRangeDays_704b9f97f7.toDateRange() = DateRangeModel(minDate, maxDate)
private fun StatsAbsValueAndPrev_33e6024c6f.toStatisticsValue() = StatisticsValueModel(current, previous, if (previous == 0.0) 0.0 else (current - previous) * 100 / previous)
private fun StatsPercentValue_e2865ffc72.toPercentage() = if (total == 0.0) 0.0 else part * 100 / total
private fun StatsGroupTopPoster_4a1989eb4e.toTopSender() = TopSenderModel(userId, messages, avgChars)
private fun StatsGroupTopAdmin_e251e2be8c.toTopAdministrator() = TopAdministratorModel(userId, deleted, kicked, banned)
private fun StatsGroupTopInviter_deb90aa57a.toTopInviter() = TopInviterModel(userId, invitations)
private fun Any.toDomainGraph(): StatisticsGraphModel = when (this) {
    is StatsGraph_df47e5db04 -> StatisticsGraphModel.Data((json as? DataJson_340cf194d4)?.data_ ?: error("Unsupported MTProto statistics graph JSON payload"), zoomToken)
    is StatsGraphAsync -> StatisticsGraphModel.Async(token)
    is StatsGraphError -> StatisticsGraphModel.Error(error)
    else -> error("Unsupported MTProto statistics graph response")
}

private fun Any.toInteraction(): ChatInteractionInfoModel? = when (this) {
    is PostInteractionCountersMessage -> ChatInteractionInfoModel(msgId.toLong(), ChatInteractionType.MESSAGE, views, forwards, reactions)
    is PostInteractionCountersStory -> ChatInteractionInfoModel(storyId.toLong(), ChatInteractionType.STORY, views, forwards, reactions)
    else -> null
}
