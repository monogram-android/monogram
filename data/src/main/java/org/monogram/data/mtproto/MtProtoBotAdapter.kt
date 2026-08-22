package org.monogram.data.mtproto

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.monogram.data.mtproto.MtProtoBotCommandRepository
import org.monogram.domain.models.BotCommandModel
import org.monogram.domain.models.BotInfoModel
import org.monogram.domain.repository.BotRepository

internal class MtProtoBotAdapter(
    private val mtProtoFactory: () -> MtProtoBotCommandRepository,
) : BotRepository {
    private val mtProto by lazy(LazyThreadSafetyMode.NONE, mtProtoFactory)

    override suspend fun getBotCommands(botId: Long): List<BotCommandModel> = mtProto.getCommands(botId)

    override suspend fun getBotInfo(botId: Long): BotInfoModel? = mtProto.getInfo(botId)

}
