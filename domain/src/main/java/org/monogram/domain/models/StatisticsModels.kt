package org.monogram.domain.models

data class ChatStatisticsModel(
    val type: StatisticsType,
    val period: DateRangeModel,
    val memberCount: StatisticsValueModel,
    val messageCount: StatisticsValueModel? = null,
    val viewerCount: StatisticsValueModel? = null,
    val senderCount: StatisticsValueModel? = null,
    val viewCount: StatisticsValueModel? = null,
    val meanViewCount: StatisticsValueModel? = null,
    val meanShareCount: StatisticsValueModel? = null,
    val meanReactionCount: StatisticsValueModel? = null,
    val meanStoryViewCount: StatisticsValueModel? = null,
    val meanStoryShareCount: StatisticsValueModel? = null,
    val meanStoryReactionCount: StatisticsValueModel? = null,
    val enabledNotificationsPercentage: Double? = null,
    val topSenders: List<TopSenderModel> = emptyList(),
    val topAdministrators: List<TopAdministratorModel> = emptyList(),
    val topInviters: List<TopInviterModel> = emptyList(),
    val recentInteractions: List<ChatInteractionInfoModel> = emptyList(),
    val memberCountGraph: StatisticsGraphModel? = null,
    val joinGraph: StatisticsGraphModel? = null,
    val muteGraph: StatisticsGraphModel? = null,
    val viewCountByHourGraph: StatisticsGraphModel? = null,
    val viewCountBySourceGraph: StatisticsGraphModel? = null,
    val joinBySourceGraph: StatisticsGraphModel? = null,
    val languageGraph: StatisticsGraphModel? = null,
    val messageContentGraph: StatisticsGraphModel? = null,
    val actionGraph: StatisticsGraphModel? = null,
    val dayGraph: StatisticsGraphModel? = null,
    val weekGraph: StatisticsGraphModel? = null,
    val topHoursGraph: StatisticsGraphModel? = null,
    val messageReactionGraph: StatisticsGraphModel? = null,
    val storyInteractionGraph: StatisticsGraphModel? = null,
    val storyReactionGraph: StatisticsGraphModel? = null
)

enum class StatisticsType {
    SUPERGROUP, CHANNEL
}

data class ChatRevenueStatisticsModel(
    val revenueByHourGraph: StatisticsGraphModel,
    val revenueGraph: StatisticsGraphModel,
    val revenueAmount: RevenueAmountModel,
    val usdRate: Double
)

data class DateRangeModel(
    val startDate: Int,
    val endDate: Int
)

data class StatisticsValueModel(
    val value: Double,
    val previousValue: Double,
    val growthRatePercentage: Double
)

data class TopSenderModel(
    val userId: Long,
    val sentMessageCount: Int,
    val averageCharacterCount: Int
)

data class TopAdministratorModel(
    val userId: Long,
    val deletedMessageCount: Int,
    val bannedUserCount: Int,
    val restrictedUserCount: Int
)

data class TopInviterModel(
    val userId: Long,
    val addedMemberCount: Int
)

data class RevenueAmountModel(
    val currency: String,
    val balance: Long,
    val availableBalance: Long
)

data class ChatInteractionInfoModel(
    val objectId: Long,
    val type: ChatInteractionType,
    val viewCount: Int,
    val forwardCount: Int,
    val reactionCount: Int
)

enum class ChatInteractionType {
    MESSAGE, STORY
}

sealed class StatisticsGraphModel {
    data class Data(
        val jsonData: String,
        val zoomToken: String?
    ) : StatisticsGraphModel()

    data class Async(
        val token: String
    ) : StatisticsGraphModel()

    data class Error(
        val errorMessage: String
    ) : StatisticsGraphModel()
}
