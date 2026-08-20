package org.monogram.data.mtproto

import org.monogram.data.repository.LinkParser
import org.monogram.data.repository.ParsedLink
import org.monogram.domain.models.DialogPeerType
import org.monogram.domain.models.TelegramPeerChatId
import org.monogram.domain.repository.LinkAction
import org.monogram.mtproto.tl.generated.cloud.layer223.PeerChannel
import org.monogram.mtproto.tl.generated.cloud.layer223.PeerChat
import org.monogram.mtproto.tl.generated.cloud.layer223.PeerUser
import org.monogram.mtproto.tl.generated.cloud.layer223.contacts.ResolveUsername
import org.monogram.mtproto.tl.generated.cloud.layer223.contacts.ResolvedPeer_28e60b6802

internal interface MtProtoLinkHandler {
    suspend fun handle(link: String): LinkAction
}

internal class MtProtoLinkHandlerImpl(
    private val parser: LinkParser,
    private val configSource: TelegramMtProtoBootstrapConfigSource,
    private val transportFactory: MtProtoSessionTransportFactory,
    private val users: MtProtoUserProjectionStore,
    private val chats: MtProtoChatProjectionStore,
    private val accountSlot: String = "default",
) : MtProtoLinkHandler {
    override suspend fun handle(link: String): LinkAction = when (val parsed = parser.parsePrimary(parser.normalize(link)) ?: parser.parseFallback(link)) {
        is ParsedLink.AddProxy -> LinkAction.AddProxy(parsed.server, parsed.port, parsed.type)
        is ParsedLink.OpenUser -> LinkAction.OpenUser(parsed.userId)
        is ParsedLink.OpenPublicChat -> resolveUsername(parsed.username)
        else -> throw UnsupportedOperationException("MTProto link type is not available")
    }

    private suspend fun resolveUsername(username: String): LinkAction {
        require(username.isNotBlank()) { "MTProto username must not be blank" }
        val config = configSource.createForAccount(accountSlot)
        val scope = MtProtoAuthKeyScope(accountSlot, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId)
        val response = transportFactory.open(accountSlot).use { transport ->
            transport.execute(ResolveUsername(username, null)) as? ResolvedPeer_28e60b6802
        } ?: return LinkAction.ShowToast("Chat not found")
        users.upsert(scope, response.users)
        chats.upsert(scope, response.chats)
        return when (val peer = response.peer) {
            is PeerUser -> LinkAction.OpenUser(peer.userId)
            is PeerChat -> LinkAction.OpenChat(TelegramPeerChatId.encode(DialogPeerType.BASIC_GROUP, peer.chatId))
            is PeerChannel -> {
                val chat = requireNotNull(chats.get(scope, peer.channelId)) { "Missing MTProto resolved channel: ${peer.channelId}" }
                val type = if (chat.type == MtProtoChatType.CHANNEL) DialogPeerType.CHANNEL else DialogPeerType.SUPERGROUP
                LinkAction.OpenChat(TelegramPeerChatId.encode(type, peer.channelId))
            }
        }
    }
}
