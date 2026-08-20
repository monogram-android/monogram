package org.monogram.data.mtproto

import org.monogram.domain.models.BotCommandModel
import org.monogram.domain.models.BotInfoModel
import org.monogram.domain.models.BotMenuButtonModel
import org.monogram.mtproto.tl.generated.cloud.layer223.BotCommandScopePeer
import org.monogram.mtproto.tl.generated.cloud.layer223.BotCommand_0a423bcf36
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerUser
import org.monogram.mtproto.tl.generated.cloud.layer223.InputUser_4020eae812
import org.monogram.mtproto.tl.generated.cloud.layer223.bots.GetBotCommands
import org.monogram.mtproto.tl.generated.cloud.layer223.bots.GetBotInfo
import org.monogram.mtproto.tl.generated.cloud.layer223.bots.BotInfo_90a6cbcc2f

internal interface MtProtoBotCommandRepository {
    suspend fun getCommands(botId: Long): List<BotCommandModel>
    suspend fun getInfo(botId: Long): BotInfoModel?
}

internal class MtProtoBotCommandRepositoryImpl(
    private val configSource: TelegramMtProtoBootstrapConfigSource,
    private val transportFactory: MtProtoSessionTransportFactory,
    private val users: MtProtoUserProjectionStore,
    private val accountSlot: String = "default",
) : MtProtoBotCommandRepository {
    override suspend fun getCommands(botId: Long): List<BotCommandModel> {
        val identity = botIdentity(botId)
        return transportFactory.open(accountSlot).use { transport ->
            transport.execute(GetBotCommands(BotCommandScopePeer(identity.peer), ""))
                .filterIsInstance<BotCommand_0a423bcf36>()
                .map { BotCommandModel(it.command, it.description) }
        }
    }

    override suspend fun getInfo(botId: Long): BotInfoModel? {
        val identity = botIdentity(botId)
        return transportFactory.open(accountSlot).use { transport ->
            (transport.execute(GetBotInfo(identity.user, identity.config.cloud.systemLanguageCode)) as? BotInfo_90a6cbcc2f)?.let {
                BotInfoModel(
                    commands = emptyList(),
                    menuButton = BotMenuButtonModel.Default,
                    shortDescription = it.about.takeIf(String::isNotBlank),
                    description = it.description.takeIf(String::isNotBlank),
                )
            }
        }
    }

    private suspend fun botIdentity(botId: Long): BotIdentity {
        require(botId > 0L) { "MTProto bot ID must be positive" }
        val config = configSource.createForAccount(accountSlot)
        val scope = MtProtoAuthKeyScope(accountSlot, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId)
        val bot = requireNotNull(users.get(scope, botId)) { "Missing MTProto bot projection: $botId" }
        require(bot.isBot) { "MTProto user is not a bot: $botId" }
        val accessHash = requireNotNull(bot.accessHash) { "Missing MTProto bot access hash: $botId" }
        return BotIdentity(
            config = config,
            peer = InputPeerUser(botId, accessHash),
            user = InputUser_4020eae812(botId, accessHash),
        )
    }

    private data class BotIdentity(
        val config: TelegramMtProtoBootstrapConfig,
        val peer: InputPeerUser,
        val user: InputUser_4020eae812,
    )
}
