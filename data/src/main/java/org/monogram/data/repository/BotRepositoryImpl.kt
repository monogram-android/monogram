package org.monogram.data.repository

import org.drinkless.tdlib.TdApi
import org.monogram.data.datasource.remote.UserRemoteDataSource
import org.monogram.data.mapper.isValidFilePath
import org.monogram.domain.models.*
import org.monogram.domain.repository.BotRepository

class BotRepositoryImpl(
    private val remote: UserRemoteDataSource
) : BotRepository {

    override suspend fun getBotCommands(botId: Long): List<BotCommandModel> {
        val fullInfo = remote.getBotFullInfo(botId) ?: return emptyList()
        return fullInfo.botInfo?.commands?.map {
            BotCommandModel(it.command, it.description)
        } ?: emptyList()
    }

    override suspend fun getBotInfo(botId: Long): BotInfoModel? {
        val fullInfo = remote.getBotFullInfo(botId) ?: return null
        val info = fullInfo.botInfo
        val commands = info?.commands?.map {
            BotCommandModel(it.command, it.description)
        } ?: emptyList()
        val menuButton = when (val btn = info?.menuButton) {
            is TdApi.BotMenuButton -> BotMenuButtonModel.WebApp(btn.text, btn.url)
            else -> BotMenuButtonModel.Default
        }
        val bestPhoto = info?.photo?.sizes?.maxByOrNull { it.width.toLong() * it.height.toLong() }
            ?: info?.photo?.sizes?.lastOrNull()
        val photoPath = bestPhoto?.photo?.local?.path?.takeIf { isValidFilePath(it) }
        val animationPath = info?.animation?.animation?.local?.path?.takeIf { isValidFilePath(it) }

        return BotInfoModel(
            commands = commands,
            menuButton = menuButton,
            shortDescription = info?.shortDescription?.ifEmpty { null },
            description = info?.description?.ifEmpty { null },
            photoFileId = bestPhoto?.photo?.id ?: 0,
            photoPath = photoPath,
            animationFileId = info?.animation?.animation?.id ?: 0,
            animationPath = animationPath,
            managerBotUserId = info?.managerBotUserId ?: 0L,
            privacyPolicyUrl = info?.privacyPolicyUrl?.ifEmpty { null },
            defaultGroupAdministratorRights = info?.defaultGroupAdministratorRights?.toDomain(),
            defaultChannelAdministratorRights = info?.defaultChannelAdministratorRights?.toDomain(),
            affiliateProgram = info?.affiliateProgram?.toDomain(),
            webAppBackgroundLightColor = info?.webAppBackgroundLightColor ?: -1,
            webAppBackgroundDarkColor = info?.webAppBackgroundDarkColor ?: -1,
            webAppHeaderLightColor = info?.webAppHeaderLightColor ?: -1,
            webAppHeaderDarkColor = info?.webAppHeaderDarkColor ?: -1,
            verificationParameters = info?.verificationParameters?.let {
                BotVerificationParametersModel(
                    iconCustomEmojiId = it.iconCustomEmojiId,
                    organizationName = it.organizationName.ifEmpty { null },
                    defaultCustomDescription = it.defaultCustomDescription?.text?.ifEmpty { null },
                    canSetCustomDescription = it.canSetCustomDescription
                )
            },
            canGetRevenueStatistics = info?.canGetRevenueStatistics ?: false,
            canManageEmojiStatus = info?.canManageEmojiStatus ?: false,
            hasMediaPreviews = info?.hasMediaPreviews ?: false,
            editCommandsLinkType = info?.editCommandsLink?.javaClass?.simpleName,
            editDescriptionLinkType = info?.editDescriptionLink?.javaClass?.simpleName,
            editDescriptionMediaLinkType = info?.editDescriptionMediaLink?.javaClass?.simpleName,
            editSettingsLinkType = info?.editSettingsLink?.javaClass?.simpleName
        )
    }
}

private fun TdApi.ChatAdministratorRights.toDomain(): ChatAdministratorRightsModel {
    return ChatAdministratorRightsModel(
        canManageChat = canManageChat,
        canChangeInfo = canChangeInfo,
        canPostMessages = canPostMessages,
        canEditMessages = canEditMessages,
        canDeleteMessages = canDeleteMessages,
        canInviteUsers = canInviteUsers,
        canRestrictMembers = canRestrictMembers,
        canPinMessages = canPinMessages,
        canManageTopics = canManageTopics,
        canPromoteMembers = canPromoteMembers,
        canManageVideoChats = canManageVideoChats,
        canPostStories = canPostStories,
        canEditStories = canEditStories,
        canDeleteStories = canDeleteStories,
        canManageDirectMessages = canManageDirectMessages,
        canManageTags = canManageTags,
        isAnonymous = isAnonymous
    )
}

private fun TdApi.AffiliateProgramInfo.toDomain(): AffiliateProgramInfoModel {
    return AffiliateProgramInfoModel(
        commissionPerMille = parameters.commissionPerMille,
        monthCount = parameters.monthCount,
        endDate = endDate,
        dailyRevenuePerUserStarCount = dailyRevenuePerUserAmount.starCount,
        dailyRevenuePerUserNanostarCount = dailyRevenuePerUserAmount.nanostarCount
    )
}
