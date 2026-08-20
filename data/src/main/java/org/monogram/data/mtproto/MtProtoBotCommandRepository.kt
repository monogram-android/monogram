package org.monogram.data.mtproto

import org.monogram.domain.models.BotCommandModel
import org.monogram.mtproto.tl.generated.cloud.layer223.BotCommandScopePeer
import org.monogram.mtproto.tl.generated.cloud.layer223.BotCommand_0a423bcf36
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerUser
import org.monogram.mtproto.tl.generated.cloud.layer223.bots.GetBotCommands

internal interface MtProtoBotCommandRepository {
    suspend fun getCommands(botId: Long): List<BotCommandModel>
}

internal class MtProtoBotCommandRepositoryImpl(
    private val configSource: TelegramMtProtoBootstrapConfigSource,
    private val transportFactory: MtProtoSessionTransportFactory,
    private val users: MtProtoUserProjectionStore,
    private val accountSlot: String = "default",
) : MtProtoBotCommandRepository {
    override suspend fun getCommands(botId: Long): List<BotCommandModel> {
        require(botId > 0L) { "MTProto bot ID must be positive" }
        val config = configSource.createForAccount(accountSlot)
        val scope = MtProtoAuthKeyScope(accountSlot, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId)
        val bot = requireNotNull(users.get(scope, botId)) { "Missing MTProto bot projection: $botId" }
        require(bot.isBot) { "MTProto user is not a bot: $botId" }
        val accessHash = requireNotNull(bot.accessHash) { "Missing MTProto bot access hash: $botId" }
        return transportFactory.open(accountSlot).use { transport ->
            transport.execute(GetBotCommands(BotCommandScopePeer(InputPeerUser(botId, accessHash)), ""))
                .filterIsInstance<BotCommand_0a423bcf36>()
                .map { BotCommandModel(it.command, it.description) }
        }
    }
}
