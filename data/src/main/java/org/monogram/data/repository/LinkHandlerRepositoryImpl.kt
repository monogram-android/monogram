package org.monogram.data.repository

import androidx.core.net.toUri
import org.drinkless.tdlib.TdApi
import org.monogram.data.gateway.TelegramGateway
import org.monogram.domain.models.ProxyTypeModel
import org.monogram.domain.repository.ChatsListRepository
import org.monogram.domain.repository.LinkAction
import org.monogram.domain.repository.LinkHandlerRepository
import org.monogram.domain.repository.UserRepository

class LinkHandlerRepositoryImpl(
    private val gateway: TelegramGateway,
    private val chatsListRepository: ChatsListRepository,
    private val userRepository: UserRepository
) : LinkHandlerRepository {

    override suspend fun handleLink(link: String): LinkAction {
        val normalized = normalizeLink(link)

        tryParseProxyLink(normalized)?.let { return it }

        return runCatching {
            when (val result = gateway.execute(TdApi.GetInternalLinkType(normalized))) {
                is TdApi.InternalLinkTypePublicChat ->
                    handlePublicChat(result.chatUsername)

                is TdApi.InternalLinkTypeMessage -> {
                    val info = runCatching {
                        gateway.execute(TdApi.GetMessageLinkInfo(result.url))
                    }.getOrNull()
                    when {
                        info == null || info.chatId == 0L -> LinkAction.ShowToast("Message not found")
                        info.message != null -> LinkAction.OpenMessage(info.chatId, info.message!!.id)
                        else -> LinkAction.OpenChat(info.chatId)
                    }
                }

                is TdApi.InternalLinkTypeSettings ->
                    LinkAction.OpenSettings(LinkAction.SettingsType.MAIN)

                is TdApi.InternalLinkTypeStickerSet ->
                    LinkAction.OpenStickerSet(result.stickerSetName)

                is TdApi.InternalLinkTypeChatInvite -> {
                    val inviteInfo = runCatching {
                        gateway.execute(TdApi.CheckChatInviteLink(result.inviteLink))
                    }.getOrNull()

                    if (inviteInfo == null) return LinkAction.JoinChat(result.inviteLink)

                    val photo = inviteInfo.photo?.small ?: inviteInfo.photo?.big
                    if (photo != null && photo.local.path.isEmpty()) {
                        runCatching { gateway.execute(TdApi.DownloadFile(photo.id, 1, 0, 0, true)) }
                    }

                    LinkAction.ConfirmJoinInviteLink(
                        inviteLink = result.inviteLink,
                        title = inviteInfo.title,
                        description = inviteInfo.description,
                        memberCount = inviteInfo.memberCount,
                        avatarPath = photo?.local?.path?.ifEmpty { null },
                        isChannel = inviteInfo.type is TdApi.InviteLinkChatTypeChannel
                    )
                }

                is TdApi.InternalLinkTypeBotStart ->
                    handlePublicChat(result.botUsername)

                is TdApi.InternalLinkTypeWebApp ->
                    LinkAction.OpenWebApp(0L, result.webAppShortName)

                is TdApi.InternalLinkTypeProxy -> {
                    val proxy = result.proxy ?: return LinkAction.ShowToast("Unsupported proxy type")
                    val type = proxy.type.toDomain()
                        ?: return LinkAction.ShowToast("Unsupported proxy type")
                    LinkAction.AddProxy(proxy.server, proxy.port, type)
                }

                is TdApi.InternalLinkTypeUnknownDeepLink ->
                    LinkAction.ShowToast("Unknown link type")

                else -> handleExternalOrUnknownLink(normalized)
            }
        }.getOrElse {
            handleExternalOrUnknownLink(normalized)
        }
    }

    override suspend fun joinChat(inviteLink: String): Long? =
        runCatching {
            gateway.execute(TdApi.JoinChatByInviteLink(inviteLink)).id
        }.getOrNull()

    private suspend fun handlePublicChat(username: String): LinkAction {
        val chat = runCatching {
            gateway.execute(TdApi.SearchPublicChat(username))
        }.getOrNull() ?: return LinkAction.ShowToast("Chat not found")
        return resolveChatAction(chat)
    }

    private suspend fun resolveChatAction(chat: TdApi.Chat): LinkAction {
        val needsConfirm = chat.type is TdApi.ChatTypeSupergroup && chat.positions.isEmpty()
        if (!needsConfirm) return LinkAction.OpenChat(chat.id)

        val chatModel = chatsListRepository.getChatById(chat.id)
        val fullInfo = userRepository.getChatFullInfo(chat.id)
        return if (chatModel != null && fullInfo != null) {
            LinkAction.ConfirmJoinChat(chatModel, fullInfo)
        } else {
            LinkAction.OpenChat(chat.id)
        }
    }

    private suspend fun handleExternalOrUnknownLink(link: String): LinkAction {
        if (link.startsWith("https://t.me/") || link.startsWith("tg://resolve?domain=")) {
            val username = link
                .removePrefix("https://t.me/")
                .removePrefix("tg://resolve?domain=")
                .split("/")
                .firstOrNull()
                ?.takeIf { it.isNotBlank() && !it.contains("?") }
                ?: return LinkAction.None

            val chat = runCatching {
                gateway.execute(TdApi.SearchPublicChat(username))
            }.getOrNull() ?: return LinkAction.None

            return resolveChatAction(chat)
        }

        return if (link.startsWith("http://") || link.startsWith("https://")) {
            LinkAction.OpenExternalLink(link)
        } else {
            LinkAction.None
        }
    }

    private fun normalizeLink(link: String): String = when {
        link.startsWith("tg://") -> link
        link.startsWith("https://t.me/") -> link
        link.startsWith("http://t.me/") -> link.replace("http://", "https://")
        link.startsWith("t.me/") -> "https://$link"
        else -> link
    }

    private fun tryParseProxyLink(link: String): LinkAction? {
        val uri = runCatching { link.toUri() }.getOrNull() ?: return null

        val isProxy = link.contains("/proxy?") || link.startsWith("tg://proxy")
        val isSocks = link.contains("/socks?") || link.startsWith("tg://socks")
        val isHttp = link.contains("/http?") || link.startsWith("tg://http")
        if (!isProxy && !isSocks && !isHttp) return null

        val server = uri.getQueryParameter("server") ?: return null
        val port = uri.getQueryParameter("port")?.toIntOrNull() ?: return null
        val secret = uri.getQueryParameter("secret")
        val user = uri.getQueryParameter("user")
        val pass = uri.getQueryParameter("pass")

        val type = when {
            secret != null -> ProxyTypeModel.Mtproto(secret)
            isHttp -> ProxyTypeModel.Http(user ?: "", pass ?: "", false)
            else -> ProxyTypeModel.Socks5(user ?: "", pass ?: "")
        }
        return LinkAction.AddProxy(server, port, type)
    }

    private fun TdApi.ProxyType?.toDomain(): ProxyTypeModel? = when (this) {
        is TdApi.ProxyTypeMtproto -> ProxyTypeModel.Mtproto(secret)
        is TdApi.ProxyTypeSocks5 -> ProxyTypeModel.Socks5(username, password)
        is TdApi.ProxyTypeHttp -> ProxyTypeModel.Http(username, password, httpOnly)
        else -> null
    }
}