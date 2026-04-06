package org.monogram.data.repository

import org.drinkless.tdlib.TdApi
import org.monogram.data.datasource.remote.UserRemoteDataSource
import org.monogram.domain.models.BotCommandModel
import org.monogram.domain.models.BotInfoModel
import org.monogram.domain.models.BotMenuButtonModel
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
        val commands = fullInfo.botInfo?.commands?.map {
            BotCommandModel(it.command, it.description)
        } ?: emptyList()
        val menuButton = when (val btn = fullInfo.botInfo?.menuButton) {
            is TdApi.BotMenuButton -> BotMenuButtonModel.WebApp(btn.text, btn.url)
            else -> BotMenuButtonModel.Default
        }
        return BotInfoModel(commands, menuButton)
    }
}