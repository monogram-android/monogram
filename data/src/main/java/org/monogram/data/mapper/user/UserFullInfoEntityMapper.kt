package org.monogram.data.mapper.user

import org.drinkless.tdlib.TdApi
import org.monogram.data.db.model.UserFullInfoEntity
import org.monogram.data.mapper.isValidFilePath

fun TdApi.UserFullInfo.toEntity(userId: Long): UserFullInfoEntity {
    val businessLocation = businessInfo?.location
    val businessOpeningHours = businessInfo?.openingHours
    val businessStartPage = businessInfo?.startPage
    val birth = birthdate
    val personalPhotoPath = personalPhoto?.animation?.file?.local?.path?.takeIf { isValidFilePath(it) }
        ?: (personalPhoto?.sizes?.maxByOrNull { it.width.toLong() * it.height.toLong() }
            ?: personalPhoto?.sizes?.lastOrNull())?.photo?.local?.path?.takeIf { isValidFilePath(it) }
    val publicPhotoPath = publicPhoto?.animation?.file?.local?.path?.takeIf { isValidFilePath(it) }
        ?: (publicPhoto?.sizes?.maxByOrNull { it.width.toLong() * it.height.toLong() }
            ?: publicPhoto?.sizes?.lastOrNull())?.photo?.local?.path?.takeIf { isValidFilePath(it) }
    val botInfoPhotoPath = botInfo?.photo?.let { photo ->
        val bestPhotoSize = photo.sizes.maxByOrNull { it.width.toLong() * it.height.toLong() }
            ?: photo.sizes.lastOrNull()
        bestPhotoSize?.photo?.local?.path?.takeIf { isValidFilePath(it) }
    }
    val botInfoPhotoFileId = botInfo?.photo?.sizes?.lastOrNull()?.photo?.id ?: 0
    val botInfoAnimationFileId = botInfo?.animation?.animation?.id ?: 0
    val botInfoAnimationPath = botInfo?.animation?.animation?.local?.path?.takeIf { isValidFilePath(it) }
    val botVerification = botVerification
    val firstProfileAudio = firstProfileAudio
    val rating = rating
    val pendingRating = pendingRating
    val botInfoVerificationParams = botInfo?.verificationParameters

    return UserFullInfoEntity(
        userId = userId,
        bio = bio?.text?.ifEmpty { null },
        commonGroupsCount = groupInCommonCount,
        giftCount = giftCount,
        botInfoDescription = botInfo?.description?.ifEmpty { null },
        botInfoShortDescription = botInfo?.shortDescription?.ifEmpty { null },
        botInfoPhotoFileId = botInfoPhotoFileId,
        botInfoPhotoPath = botInfoPhotoPath,
        botInfoAnimationFileId = botInfoAnimationFileId,
        botInfoAnimationPath = botInfoAnimationPath,
        botInfoManagerBotUserId = botInfo?.managerBotUserId ?: 0L,
        botInfoMenuButtonText = botInfo?.menuButton?.text?.ifEmpty { null },
        botInfoMenuButtonUrl = botInfo?.menuButton?.url?.ifEmpty { null },
        botInfoCommandsData = encodeBotInfoCommands(botInfo?.commands),
        botInfoPrivacyPolicyUrl = botInfo?.privacyPolicyUrl?.ifEmpty { null },
        botInfoDefaultGroupRightsData = encodeChatAdministratorRights(botInfo?.defaultGroupAdministratorRights),
        botInfoDefaultChannelRightsData = encodeChatAdministratorRights(botInfo?.defaultChannelAdministratorRights),
        botInfoAffiliateProgramData = encodeAffiliateProgramInfo(botInfo?.affiliateProgram),
        botInfoWebAppBackgroundLightColor = botInfo?.webAppBackgroundLightColor ?: -1,
        botInfoWebAppBackgroundDarkColor = botInfo?.webAppBackgroundDarkColor ?: -1,
        botInfoWebAppHeaderLightColor = botInfo?.webAppHeaderLightColor ?: -1,
        botInfoWebAppHeaderDarkColor = botInfo?.webAppHeaderDarkColor ?: -1,
        botInfoVerificationParametersIconCustomEmojiId = botInfoVerificationParams?.iconCustomEmojiId ?: 0L,
        botInfoVerificationParametersOrganizationName = botInfoVerificationParams?.organizationName?.ifEmpty { null },
        botInfoVerificationParametersDefaultCustomDescription = botInfoVerificationParams?.defaultCustomDescription?.text?.ifEmpty { null },
        botInfoVerificationParametersCanSetCustomDescription = botInfoVerificationParams?.canSetCustomDescription
            ?: false,
        botInfoCanManageEmojiStatus = botInfo?.canManageEmojiStatus ?: false,
        botInfoHasMediaPreviews = botInfo?.hasMediaPreviews ?: false,
        botInfoEditCommandsLinkType = botInfo?.editCommandsLink?.javaClass?.simpleName,
        botInfoEditDescriptionLinkType = botInfo?.editDescriptionLink?.javaClass?.simpleName,
        botInfoEditDescriptionMediaLinkType = botInfo?.editDescriptionMediaLink?.javaClass?.simpleName,
        botInfoEditSettingsLinkType = botInfo?.editSettingsLink?.javaClass?.simpleName,
        personalChatId = personalChatId,
        birthdateDay = birth?.day ?: 0,
        birthdateMonth = birth?.month ?: 0,
        birthdateYear = birth?.year ?: 0,
        publicPhotoPath = publicPhotoPath,
        blockListType = blockList.toTypeString(),
        businessLocationAddress = businessLocation?.address?.ifEmpty { null },
        businessLocationLatitude = businessLocation?.location?.latitude ?: 0.0,
        businessLocationLongitude = businessLocation?.location?.longitude ?: 0.0,
        businessOpeningHoursTimeZone = businessOpeningHours?.timeZoneId,
        businessNextOpenIn = businessInfo?.nextOpenIn ?: 0,
        businessNextCloseIn = businessInfo?.nextCloseIn ?: 0,
        businessStartPageTitle = businessStartPage?.title?.ifEmpty { null },
        businessStartPageMessage = businessStartPage?.message?.ifEmpty { null },
        note = note?.text?.ifEmpty { null },
        personalPhotoPath = personalPhotoPath,
        isBlocked = blockList != null,
        hasSponsoredMessagesEnabled = hasSponsoredMessagesEnabled,
        needPhoneNumberPrivacyException = needPhoneNumberPrivacyException,
        usesUnofficialApp = usesUnofficialApp,
        botVerificationBotUserId = botVerification?.botUserId ?: 0L,
        botVerificationIconCustomEmojiId = botVerification?.iconCustomEmojiId ?: 0L,
        botVerificationCustomDescription = botVerification?.customDescription?.text?.ifEmpty { null },
        mainProfileTab = mainProfileTab.toTypeString(),
        firstProfileAudioDuration = firstProfileAudio?.duration ?: 0,
        firstProfileAudioTitle = firstProfileAudio?.title?.ifEmpty { null },
        firstProfileAudioPerformer = firstProfileAudio?.performer?.ifEmpty { null },
        firstProfileAudioFileName = firstProfileAudio?.fileName?.ifEmpty { null },
        firstProfileAudioMimeType = firstProfileAudio?.mimeType?.ifEmpty { null },
        firstProfileAudioFileId = firstProfileAudio?.audio?.id ?: 0,
        firstProfileAudioPath = firstProfileAudio?.audio?.local?.path?.takeIf { isValidFilePath(it) },
        ratingLevel = rating?.level ?: 0,
        ratingIsMaximumLevelReached = rating?.isMaximumLevelReached ?: false,
        ratingValue = rating?.rating ?: 0L,
        ratingCurrentLevelValue = rating?.currentLevelRating ?: 0L,
        ratingNextLevelValue = rating?.nextLevelRating ?: 0L,
        pendingRatingLevel = pendingRating?.level ?: 0,
        pendingRatingIsMaximumLevelReached = pendingRating?.isMaximumLevelReached ?: false,
        pendingRatingValue = pendingRating?.rating ?: 0L,
        pendingRatingCurrentLevelValue = pendingRating?.currentLevelRating ?: 0L,
        pendingRatingNextLevelValue = pendingRating?.nextLevelRating ?: 0L,
        pendingRatingDate = pendingRatingDate,
        canBeCalled = canBeCalled,
        supportsVideoCalls = supportsVideoCalls,
        hasPrivateCalls = hasPrivateCalls,
        hasPrivateForwards = hasPrivateForwards,
        hasRestrictedVoiceAndVideoNoteMessages = hasRestrictedVoiceAndVideoNoteMessages,
        hasPostedToProfileStories = hasPostedToProfileStories,
        setChatBackground = setChatBackground,
        canGetRevenueStatistics = botInfo?.canGetRevenueStatistics ?: false,
        createdAt = System.currentTimeMillis()
    )
}

fun UserFullInfoEntity.toTdApi(): TdApi.UserFullInfo {
    return TdApi.UserFullInfo().apply {
        bio = this@toTdApi.bio?.let { TdApi.FormattedText(it, emptyArray()) }
        groupInCommonCount = commonGroupsCount
        giftCount = this@toTdApi.giftCount
        personalChatId = this@toTdApi.personalChatId
        birthdate = if (birthdateDay > 0 && birthdateMonth > 0) {
            TdApi.Birthdate(birthdateDay, birthdateMonth, birthdateYear)
        } else {
            null
        }
        botInfo = if (
            botInfoDescription != null ||
            botInfoShortDescription != null ||
            botInfoManagerBotUserId != 0L ||
            botInfoPrivacyPolicyUrl != null
        ) {
            TdApi.BotInfo().apply {
                shortDescription = botInfoShortDescription.orEmpty()
                description = botInfoDescription.orEmpty()
                photo = if (botInfoPhotoFileId != 0 || !botInfoPhotoPath.isNullOrEmpty()) {
                    TdApi.Photo().apply {
                        sizes = arrayOf(
                            TdApi.PhotoSize().apply {
                                type = "x"
                                width = 0
                                height = 0
                                photo = TdApi.File().apply {
                                    id = botInfoPhotoFileId
                                    local = TdApi.LocalFile().apply { path = botInfoPhotoPath.orEmpty() }
                                }
                            }
                        )
                    }
                } else {
                    null
                }
                animation = if (botInfoAnimationFileId != 0 || !botInfoAnimationPath.isNullOrEmpty()) {
                    TdApi.Animation().apply {
                        animation = TdApi.File().apply {
                            id = botInfoAnimationFileId
                            local = TdApi.LocalFile().apply { path = botInfoAnimationPath.orEmpty() }
                        }
                    }
                } else {
                    null
                }
                managerBotUserId = botInfoManagerBotUserId
                menuButton = if (!botInfoMenuButtonText.isNullOrEmpty() || !botInfoMenuButtonUrl.isNullOrEmpty()) {
                    TdApi.BotMenuButton(
                        botInfoMenuButtonText.orEmpty(),
                        botInfoMenuButtonUrl.orEmpty()
                    )
                } else {
                    null
                }
                commands = decodeBotInfoCommands(botInfoCommandsData)
                privacyPolicyUrl = botInfoPrivacyPolicyUrl.orEmpty()
                defaultGroupAdministratorRights = decodeChatAdministratorRights(botInfoDefaultGroupRightsData)
                defaultChannelAdministratorRights = decodeChatAdministratorRights(botInfoDefaultChannelRightsData)
                affiliateProgram = decodeAffiliateProgramInfo(botInfoAffiliateProgramData)
                webAppBackgroundLightColor = botInfoWebAppBackgroundLightColor
                webAppBackgroundDarkColor = botInfoWebAppBackgroundDarkColor
                webAppHeaderLightColor = botInfoWebAppHeaderLightColor
                webAppHeaderDarkColor = botInfoWebAppHeaderDarkColor
                verificationParameters = if (
                    botInfoVerificationParametersIconCustomEmojiId != 0L ||
                    !botInfoVerificationParametersOrganizationName.isNullOrEmpty() ||
                    !botInfoVerificationParametersDefaultCustomDescription.isNullOrEmpty()
                ) {
                    TdApi.BotVerificationParameters(
                        botInfoVerificationParametersIconCustomEmojiId,
                        botInfoVerificationParametersOrganizationName.orEmpty(),
                        botInfoVerificationParametersDefaultCustomDescription?.let {
                            TdApi.FormattedText(it, emptyArray())
                        },
                        botInfoVerificationParametersCanSetCustomDescription
                    )
                } else {
                    null
                }
                canGetRevenueStatistics = canGetRevenueStatistics
                canManageEmojiStatus = botInfoCanManageEmojiStatus
                hasMediaPreviews = botInfoHasMediaPreviews
                editCommandsLink = null
                editDescriptionLink = null
                editDescriptionMediaLink = null
                editSettingsLink = null
            }
        } else {
            null
        }
        businessInfo = if (
            businessLocationAddress != null ||
            businessOpeningHoursTimeZone != null ||
            businessStartPageTitle != null ||
            businessStartPageMessage != null
        ) {
            TdApi.BusinessInfo().apply {
                location = businessLocationAddress?.let { address ->
                    TdApi.BusinessLocation(
                        TdApi.Location(businessLocationLatitude, businessLocationLongitude, 0.0),
                        address
                    )
                }
                openingHours = businessOpeningHoursTimeZone?.let { tz ->
                    TdApi.BusinessOpeningHours(tz, emptyArray())
                }
                startPage = if (businessStartPageTitle != null || businessStartPageMessage != null) {
                    TdApi.BusinessStartPage(
                        businessStartPageTitle.orEmpty(),
                        businessStartPageMessage.orEmpty(),
                        null
                    )
                } else {
                    null
                }
                nextOpenIn = businessNextOpenIn
                nextCloseIn = businessNextCloseIn
            }
        } else {
            null
        }
        personalPhoto = personalPhotoPath.toTdApiChatPhoto()
        photo = null
        publicPhoto = publicPhotoPath.toTdApiChatPhoto()
        blockList = when (blockListType) {
            "STORIES" -> TdApi.BlockListStories()
            "MAIN" -> TdApi.BlockListMain()
            else -> if (isBlocked) TdApi.BlockListMain() else null
        }
        canBeCalled = this@toTdApi.canBeCalled
        supportsVideoCalls = this@toTdApi.supportsVideoCalls
        hasPrivateCalls = this@toTdApi.hasPrivateCalls
        hasPrivateForwards = this@toTdApi.hasPrivateForwards
        hasRestrictedVoiceAndVideoNoteMessages = this@toTdApi.hasRestrictedVoiceAndVideoNoteMessages
        hasPostedToProfileStories = this@toTdApi.hasPostedToProfileStories
        hasSponsoredMessagesEnabled = this@toTdApi.hasSponsoredMessagesEnabled
        needPhoneNumberPrivacyException = this@toTdApi.needPhoneNumberPrivacyException
        setChatBackground = this@toTdApi.setChatBackground
        usesUnofficialApp = this@toTdApi.usesUnofficialApp
        incomingPaidMessageStarCount = this@toTdApi.incomingPaidMessageStarCount
        outgoingPaidMessageStarCount = this@toTdApi.outgoingPaidMessageStarCount
        botVerification = if (
            botVerificationBotUserId != 0L ||
            botVerificationIconCustomEmojiId != 0L ||
            !botVerificationCustomDescription.isNullOrEmpty()
        ) {
            TdApi.BotVerification(
                botVerificationBotUserId,
                botVerificationIconCustomEmojiId,
                TdApi.FormattedText(botVerificationCustomDescription.orEmpty(), emptyArray())
            )
        } else {
            null
        }
        mainProfileTab = this@toTdApi.mainProfileTab.toTdApiProfileTab()
        firstProfileAudio = if (
            firstProfileAudioFileId != 0 ||
            !firstProfileAudioPath.isNullOrEmpty() ||
            firstProfileAudioDuration > 0 ||
            !firstProfileAudioTitle.isNullOrEmpty() ||
            !firstProfileAudioPerformer.isNullOrEmpty()
        ) {
            TdApi.Audio(
                firstProfileAudioDuration,
                firstProfileAudioTitle.orEmpty(),
                firstProfileAudioPerformer.orEmpty(),
                firstProfileAudioFileName.orEmpty(),
                firstProfileAudioMimeType.orEmpty(),
                null,
                null,
                emptyArray(),
                TdApi.File().apply {
                    id = firstProfileAudioFileId
                    local = TdApi.LocalFile().apply { path = firstProfileAudioPath.orEmpty() }
                }
            )
        } else {
            null
        }
        rating = if (
            ratingLevel != 0 ||
            ratingIsMaximumLevelReached ||
            ratingValue != 0L ||
            ratingCurrentLevelValue != 0L ||
            ratingNextLevelValue != 0L
        ) {
            TdApi.UserRating(
                ratingLevel,
                ratingIsMaximumLevelReached,
                ratingValue,
                ratingCurrentLevelValue,
                ratingNextLevelValue
            )
        } else {
            null
        }
        pendingRating = if (
            pendingRatingLevel != 0 ||
            pendingRatingIsMaximumLevelReached ||
            pendingRatingValue != 0L ||
            pendingRatingCurrentLevelValue != 0L ||
            pendingRatingNextLevelValue != 0L
        ) {
            TdApi.UserRating(
                pendingRatingLevel,
                pendingRatingIsMaximumLevelReached,
                pendingRatingValue,
                pendingRatingCurrentLevelValue,
                pendingRatingNextLevelValue
            )
        } else {
            null
        }
        pendingRatingDate = this@toTdApi.pendingRatingDate
        note = this@toTdApi.note?.let { TdApi.FormattedText(it, emptyArray()) }
    }
}
