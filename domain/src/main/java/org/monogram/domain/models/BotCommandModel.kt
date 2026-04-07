package org.monogram.domain.models

data class BotCommandModel(
    val command: String,
    val description: String
)

sealed interface BotMenuButtonModel {
    object Commands : BotMenuButtonModel
    data class WebApp(val text: String, val url: String) : BotMenuButtonModel
    object Default : BotMenuButtonModel
}

data class BotInfoModel(
    val commands: List<BotCommandModel>,
    val menuButton: BotMenuButtonModel,
    val shortDescription: String? = null,
    val description: String? = null,
    val photoFileId: Int = 0,
    val photoPath: String? = null,
    val animationFileId: Int = 0,
    val animationPath: String? = null,
    val managerBotUserId: Long = 0L,
    val privacyPolicyUrl: String? = null,
    val defaultGroupAdministratorRights: ChatAdministratorRightsModel? = null,
    val defaultChannelAdministratorRights: ChatAdministratorRightsModel? = null,
    val affiliateProgram: AffiliateProgramInfoModel? = null,
    val webAppBackgroundLightColor: Int = -1,
    val webAppBackgroundDarkColor: Int = -1,
    val webAppHeaderLightColor: Int = -1,
    val webAppHeaderDarkColor: Int = -1,
    val verificationParameters: BotVerificationParametersModel? = null,
    val canGetRevenueStatistics: Boolean = false,
    val canManageEmojiStatus: Boolean = false,
    val hasMediaPreviews: Boolean = false,
    val editCommandsLinkType: String? = null,
    val editDescriptionLinkType: String? = null,
    val editDescriptionMediaLinkType: String? = null,
    val editSettingsLinkType: String? = null
)

data class InlineQueryResultModel(
    val id: String,
    val type: String,
    val title: String?,
    val description: String?,
    val thumbUrl: String?,
    val thumbFileId: Int = 0,
    val content: MessageContent? = null,
    val replyMarkup: ReplyMarkupModel? = null,
    val width: Int = 0,
    val height: Int = 0
)
