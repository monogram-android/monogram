package org.monogram.domain.repository

import org.monogram.domain.models.BotCommandModel
import org.monogram.domain.models.BotInfoModel

interface BotRepository {
    suspend fun getBotCommands(botId: Long): List<BotCommandModel>
    suspend fun getBotInfo(botId: Long): BotInfoModel?
}