package org.monogram.data.mtproto

import org.monogram.data.repository.LinkParser
import org.monogram.data.repository.ParsedLink
import org.monogram.domain.models.DialogPeerType
import org.monogram.domain.models.TelegramPeerChatId
import org.monogram.domain.repository.LinkAction
import org.monogram.mtproto.tl.generated.cloud.layer223.Channel
import org.monogram.mtproto.tl.generated.cloud.layer223.ChatInviteAlready
import org.monogram.mtproto.tl.generated.cloud.layer223.ChatInvitePeek
import org.monogram.mtproto.tl.generated.cloud.layer223.ChatInvite_e5c19696c2
import org.monogram.mtproto.tl.generated.cloud.layer223.Chat_65eab3b078
import org.monogram.mtproto.tl.generated.cloud.layer223.PeerChannel
import org.monogram.mtproto.tl.generated.cloud.layer223.PeerChat
import org.monogram.mtproto.tl.generated.cloud.layer223.PeerUser
import org.monogram.mtproto.tl.generated.cloud.layer223.contacts.ResolveUsername
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.CheckChatInvite
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.ImportChatInvite
import org.monogram.mtproto.tl.generated.cloud.layer223.UpdatesCombined
import org.monogram.mtproto.tl.generated.cloud.layer223.Updates_02c952992b
import org.monogram.mtproto.tl.generated.cloud.layer223.Updates_faf6aaa3d5
import org.monogram.mtproto.transport.MtProtoRpcException
import org.monogram.mtproto.tl.generated.cloud.layer223.contacts.ResolvedPeer_28e60b6802

internal interface MtProtoLinkHandler {
    suspend fun handle(link: String): LinkAction
    suspend fun joinChat(inviteLink: String): Long? = throw UnsupportedOperationException("MTProto invite joining is not configured")
    suspend fun joinChatAction(inviteLink: String): LinkAction = throw UnsupportedOperationException("MTProto invite joining is not configured")
}

internal class MtProtoLinkHandlerImpl(
    private val parser: LinkParser,
    private val configSource: TelegramMtProtoBootstrapConfigSource,
    private val transportFactory: MtProtoSessionTransportFactory,
    private val users: MtProtoUserProjectionStore,
    private val chats: MtProtoChatProjectionStore,
    private val cloudObjectStager: MtProtoCloudObjectStager = NoOpMtProtoCloudObjectStager,
    private val accountSlot: String = "default",
) : MtProtoLinkHandler {
    override suspend fun handle(link: String): LinkAction = when (val parsed = parser.parsePrimary(parser.normalize(link)) ?: parser.parseFallback(link)) {
        is ParsedLink.AddProxy -> LinkAction.AddProxy(parsed.server, parsed.port, parsed.type)
        is ParsedLink.OpenUser -> LinkAction.OpenUser(parsed.userId)
        is ParsedLink.OpenPublicChat -> resolveUsername(parsed.username)
        is ParsedLink.JoinChat -> checkInvite(parsed.inviteLink)
        is ParsedLink.OpenExternal -> LinkAction.OpenExternalLink(parsed.url)
        ParsedLink.None -> LinkAction.None
        is ParsedLink.ResolveByPhone -> throw UnsupportedOperationException("MTProto phone link resolution is not available")
    }

    override suspend fun joinChat(inviteLink: String): Long? =
        (joinChatAction(inviteLink) as? LinkAction.OpenChat)?.chatId

    override suspend fun joinChatAction(inviteLink: String): LinkAction {
        val hash = inviteHash(inviteLink)
        val scope = scope()
        val updates = try {
            transportFactory.open(accountSlot).use { it.execute(ImportChatInvite(hash)) }
        } catch (rpc: MtProtoRpcException) {
            if (rpc.errorCode == 400 && rpc.rpcMessage == "INVITE_REQUEST_SENT") {
                return LinkAction.JoinChatRequestSent()
            }
            throw rpc
        }
        cloudObjectStager.stageLive(scope, updates)
        return openedChat(scope, updates)
    }

    private suspend fun checkInvite(inviteLink: String): LinkAction {
        val hash = inviteHash(inviteLink)
        val scope = scope()
        return when (val response = transportFactory.open(accountSlot).use { it.execute(CheckChatInvite(hash)) }) {
            is ChatInvite_e5c19696c2 -> {
                response.participants?.let { users.upsert(scope, it) }
                LinkAction.ConfirmJoinInviteLink(
                    inviteLink = inviteLink,
                    title = response.title,
                    description = response.about.orEmpty(),
                    memberCount = response.participantsCount,
                    avatarPath = null,
                    isChannel = response.channel,
                )
            }
            is ChatInviteAlready -> openInvitedChat(scope, response.chat)
            is ChatInvitePeek -> openInvitedChat(scope, response.chat)
        }
    }

    private fun inviteHash(inviteLink: String): String = inviteLink.substringAfter("joinchat/", missingDelimiterValue = "")
        .ifBlank { inviteLink.substringAfter("/+", missingDelimiterValue = "") }
        .takeIf { it.isNotBlank() && it.none { char -> char == '/' || char == '?' || char == '#' } }
        ?: throw IllegalArgumentException("MTProto invite link is invalid")

    private suspend fun openedChat(scope: MtProtoAuthKeyScope, envelope: Updates_faf6aaa3d5): LinkAction {
        val chats = when (envelope) {
            is Updates_02c952992b -> envelope.chats
            is UpdatesCombined -> envelope.chats
            else -> emptyList()
        }
        return openInvitedChat(scope, requireNotNull(chats.singleOrNull()) {
            "MTProto invite join response did not contain one chat"
        })
    }

    private suspend fun openInvitedChat(
        scope: MtProtoAuthKeyScope,
        chat: org.monogram.mtproto.tl.generated.cloud.layer223.Chat_7fdd7beb6e,
    ): LinkAction {
        chats.upsert(scope, listOf(chat))
        return when (chat) {
            is Chat_65eab3b078 -> LinkAction.OpenChat(TelegramPeerChatId.encode(DialogPeerType.BASIC_GROUP, chat.id))
            is Channel -> LinkAction.OpenChat(
                TelegramPeerChatId.encode(
                    if (chat.megagroup) DialogPeerType.SUPERGROUP else DialogPeerType.CHANNEL,
                    chat.id,
                ),
            )
            else -> throw UnsupportedOperationException("MTProto invited chat type is not available")
        }
    }

    private suspend fun resolveUsername(username: String): LinkAction {
        require(username.isNotBlank()) { "MTProto username must not be blank" }
        val scope = scope()
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

    private suspend fun scope(): MtProtoAuthKeyScope {
        val config = configSource.createForAccount(accountSlot)
        return MtProtoAuthKeyScope(accountSlot, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId)
    }
}
