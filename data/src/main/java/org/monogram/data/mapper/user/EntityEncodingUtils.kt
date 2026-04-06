package org.monogram.data.mapper.user

import org.drinkless.tdlib.TdApi
import org.monogram.domain.models.*

internal fun encodeChatAdministratorRights(rights: TdApi.ChatAdministratorRights?): String? {
    if (rights == null) return null
    return listOf(
        rights.canManageChat,
        rights.canChangeInfo,
        rights.canPostMessages,
        rights.canEditMessages,
        rights.canDeleteMessages,
        rights.canInviteUsers,
        rights.canRestrictMembers,
        rights.canPinMessages,
        rights.canManageTopics,
        rights.canPromoteMembers,
        rights.canManageVideoChats,
        rights.canPostStories,
        rights.canEditStories,
        rights.canDeleteStories,
        rights.canManageDirectMessages,
        rights.canManageTags,
        rights.isAnonymous
    ).joinToString("|") { if (it) "1" else "0" }
}

internal fun decodeChatAdministratorRights(data: String?): TdApi.ChatAdministratorRights? {
    if (data.isNullOrBlank()) return null
    val values = data.split('|')
    fun bit(index: Int): Boolean = values.getOrNull(index) == "1"
    return TdApi.ChatAdministratorRights(
        bit(0),
        bit(1),
        bit(2),
        bit(3),
        bit(4),
        bit(5),
        bit(6),
        bit(7),
        bit(8),
        bit(9),
        bit(10),
        bit(11),
        bit(12),
        bit(13),
        bit(14),
        bit(15),
        bit(16)
    )
}

internal fun encodeAffiliateProgramInfo(affiliateProgram: TdApi.AffiliateProgramInfo?): String? {
    if (affiliateProgram == null) return null
    val params = affiliateProgram.parameters
    val amount = affiliateProgram.dailyRevenuePerUserAmount
    return listOf(
        params.commissionPerMille.toString(),
        params.monthCount.toString(),
        affiliateProgram.endDate.toString(),
        amount.starCount.toString(),
        amount.nanostarCount.toString()
    ).joinToString("|")
}

internal fun decodeAffiliateProgramInfo(data: String?): TdApi.AffiliateProgramInfo? {
    if (data.isNullOrBlank()) return null
    val values = data.split('|')
    val commissionPerMille = values.getOrNull(0)?.toIntOrNull() ?: return null
    val monthCount = values.getOrNull(1)?.toIntOrNull() ?: return null
    val endDate = values.getOrNull(2)?.toIntOrNull() ?: return null
    val starCount = values.getOrNull(3)?.toLongOrNull() ?: return null
    val nanostarCount = values.getOrNull(4)?.toIntOrNull() ?: return null
    return TdApi.AffiliateProgramInfo(
        TdApi.AffiliateProgramParameters(commissionPerMille, monthCount),
        endDate,
        TdApi.StarAmount(starCount, nanostarCount)
    )
}

internal fun encodeProfileAudio(audio: ProfileAudioModel?): String? {
    if (audio == null) return null
    return listOf(
        audio.duration.toString(),
        audio.title.orEmpty().escapeStorage(),
        audio.performer.orEmpty().escapeStorage(),
        audio.fileName.orEmpty().escapeStorage(),
        audio.mimeType.orEmpty().escapeStorage(),
        audio.fileId.toString(),
        audio.filePath.orEmpty().escapeStorage()
    ).joinToString("|")
}

internal fun decodeProfileAudio(data: String?): ProfileAudioModel? {
    if (data.isNullOrBlank()) return null
    val parts = data.split('|')
    return ProfileAudioModel(
        duration = parts.getOrNull(0)?.toIntOrNull() ?: 0,
        title = parts.getOrNull(1)?.unescapeStorage()?.ifEmpty { null },
        performer = parts.getOrNull(2)?.unescapeStorage()?.ifEmpty { null },
        fileName = parts.getOrNull(3)?.unescapeStorage()?.ifEmpty { null },
        mimeType = parts.getOrNull(4)?.unescapeStorage()?.ifEmpty { null },
        fileId = parts.getOrNull(5)?.toIntOrNull() ?: 0,
        filePath = parts.getOrNull(6)?.unescapeStorage()?.ifEmpty { null }
    )
}

internal fun encodeUserRating(rating: UserRatingModel?): String? {
    if (rating == null) return null
    return listOf(
        rating.level.toString(),
        if (rating.isMaximumLevelReached) "1" else "0",
        rating.rating.toString(),
        rating.currentLevelRating.toString(),
        rating.nextLevelRating.toString()
    ).joinToString("|")
}

internal fun decodeUserRating(data: String?): UserRatingModel? {
    if (data.isNullOrBlank()) return null
    val parts = data.split('|')
    return UserRatingModel(
        level = parts.getOrNull(0)?.toIntOrNull() ?: 0,
        isMaximumLevelReached = parts.getOrNull(1) == "1",
        rating = parts.getOrNull(2)?.toLongOrNull() ?: 0L,
        currentLevelRating = parts.getOrNull(3)?.toLongOrNull() ?: 0L,
        nextLevelRating = parts.getOrNull(4)?.toLongOrNull() ?: 0L
    )
}

internal fun encodeBotCommands(commands: Array<TdApi.BotCommands>?): String? {
    if (commands.isNullOrEmpty()) return null
    return commands.joinToString("\n") { botCommands ->
        val serializedCommands = (botCommands.commands ?: emptyArray()).joinToString(";") { command ->
            "${command.command.escapeStorage()},${command.description.escapeStorage()}"
        }
        "${botCommands.botUserId}:$serializedCommands"
    }
}

internal fun encodeBotInfoCommands(commands: Array<TdApi.BotCommand>?): String? {
    if (commands.isNullOrEmpty()) return null
    return commands.joinToString(";") { command ->
        "${command.command.escapeStorage()},${command.description.escapeStorage()}"
    }
}

internal fun decodeBotInfoCommands(data: String?): Array<TdApi.BotCommand> {
    if (data.isNullOrBlank()) return emptyArray()
    return data.split(';').mapNotNull { item ->
        val commandSeparator = item.indexOf(',')
        if (commandSeparator < 0) return@mapNotNull null
        val command = item.substring(0, commandSeparator).unescapeStorage()
        val description = item.substring(commandSeparator + 1).unescapeStorage()
        TdApi.BotCommand(command, description)
    }.toTypedArray()
}

internal fun decodeBotCommands(data: String?): List<SupergroupBotCommandsModel> {
    if (data.isNullOrBlank()) return emptyList()
    return data.split('\n').mapNotNull { line ->
        val separator = line.indexOf(':')
        if (separator <= 0) return@mapNotNull null
        val botUserId = line.substring(0, separator).toLongOrNull() ?: return@mapNotNull null
        val commandsRaw = line.substring(separator + 1)
        val commands = if (commandsRaw.isBlank()) {
            emptyList()
        } else {
            commandsRaw.split(';').mapNotNull { item ->
                val commandSeparator = item.indexOf(',')
                if (commandSeparator < 0) return@mapNotNull null
                val command = item.substring(0, commandSeparator).unescapeStorage()
                val description = item.substring(commandSeparator + 1).unescapeStorage()
                BotCommandModel(command, description)
            }
        }
        SupergroupBotCommandsModel(botUserId = botUserId, commands = commands)
    }
}

internal fun TdApi.ProfileTab?.toTypeString(): String? {
    return when (this) {
        is TdApi.ProfileTabPosts -> "POSTS"
        is TdApi.ProfileTabGifts -> "GIFTS"
        is TdApi.ProfileTabMedia -> "MEDIA"
        is TdApi.ProfileTabFiles -> "FILES"
        is TdApi.ProfileTabLinks -> "LINKS"
        is TdApi.ProfileTabMusic -> "MUSIC"
        is TdApi.ProfileTabVoice -> "VOICE"
        is TdApi.ProfileTabGifs -> "GIFS"
        else -> null
    }
}

internal fun String?.toTdApiProfileTab(): TdApi.ProfileTab? {
    return when (this) {
        "POSTS" -> TdApi.ProfileTabPosts()
        "GIFTS" -> TdApi.ProfileTabGifts()
        "MEDIA" -> TdApi.ProfileTabMedia()
        "FILES" -> TdApi.ProfileTabFiles()
        "LINKS" -> TdApi.ProfileTabLinks()
        "MUSIC" -> TdApi.ProfileTabMusic()
        "VOICE" -> TdApi.ProfileTabVoice()
        "GIFS" -> TdApi.ProfileTabGifs()
        else -> null
    }
}

internal fun String?.toProfileTabType(): ProfileTabType? {
    return when (this) {
        "POSTS" -> ProfileTabType.POSTS
        "GIFTS" -> ProfileTabType.GIFTS
        "MEDIA" -> ProfileTabType.MEDIA
        "FILES" -> ProfileTabType.FILES
        "LINKS" -> ProfileTabType.LINKS
        "MUSIC" -> ProfileTabType.MUSIC
        "VOICE" -> ProfileTabType.VOICE
        "GIFS" -> ProfileTabType.GIFS
        else -> null
    }
}

internal fun String?.toTdApiChatPhoto(): TdApi.ChatPhoto? {
    if (this.isNullOrBlank()) return null
    return TdApi.ChatPhoto().apply {
        sizes = arrayOf(
            TdApi.PhotoSize().apply {
                type = "x"
                width = 0
                height = 0
                photo = TdApi.File().apply {
                    local = TdApi.LocalFile().apply { path = this@toTdApiChatPhoto }
                }
            }
        )
    }
}

internal fun TdApi.BlockList?.toTypeString(): String? {
    return when (this) {
        is TdApi.BlockListMain -> "MAIN"
        is TdApi.BlockListStories -> "STORIES"
        else -> null
    }
}

internal fun String.escapeStorage(): String {
    return this
        .replace("\\", "\\\\")
        .replace("\n", "\\n")
        .replace("|", "\\p")
        .replace(",", "\\c")
        .replace(";", "\\s")
}

internal fun String.unescapeStorage(): String {
    return this
        .replace("\\s", ";")
        .replace("\\c", ",")
        .replace("\\p", "|")
        .replace("\\n", "\n")
        .replace("\\\\", "\\")
}
