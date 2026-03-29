package org.monogram.data.repository

import org.monogram.data.core.coRunCatching
import androidx.core.net.toUri
import org.drinkless.tdlib.TdApi
import org.monogram.data.gateway.TelegramGateway
import org.monogram.data.infra.FileDownloadQueue
import org.monogram.domain.models.ProxyTypeModel
import org.monogram.domain.repository.ChatsListRepository
import org.monogram.domain.repository.LinkAction
import org.monogram.domain.repository.LinkHandlerRepository
import org.monogram.domain.repository.UserRepository

class LinkHandlerRepositoryImpl(
    private val gateway: TelegramGateway,
    private val chatsListRepository: ChatsListRepository,
    private val userRepository: UserRepository,
    private val fileQueue: FileDownloadQueue
) : LinkHandlerRepository {

    override suspend fun handleLink(link: String): LinkAction {
        val normalized = normalizeLink(link)

        tryParseProxyLink(normalized)?.let { return it }
        tryParseUserLink(normalized)?.let { return it }

        return coRunCatching {
            when (val result = gateway.execute(TdApi.GetInternalLinkType(normalized))) {
                is TdApi.InternalLinkTypePublicChat ->
                    handlePublicChat(result.chatUsername)

                is TdApi.InternalLinkTypeMessage -> {
                    val info = coRunCatching {
                        gateway.execute(TdApi.GetMessageLinkInfo(result.url))
                    }.getOrNull()
                    when {
                        info == null || info.chatId == 0L -> LinkAction.ShowToast("Message not found")
                        info.message != null -> LinkAction.OpenMessage(info.chatId, info.message!!.id)
                        else -> LinkAction.OpenChat(info.chatId)
                    }
                }

                is TdApi.InternalLinkTypeSettings ->
                    LinkAction.OpenSettings(result.section.toDomain())

                is TdApi.InternalLinkTypeStickerSet ->
                    LinkAction.OpenStickerSet(result.stickerSetName)

                is TdApi.InternalLinkTypeChatInvite -> {
                    val inviteInfo = coRunCatching {
                        gateway.execute(TdApi.CheckChatInviteLink(result.inviteLink))
                    }.getOrNull()

                    if (inviteInfo == null) return LinkAction.JoinChat(result.inviteLink)

                    val photo = inviteInfo.photo?.small ?: inviteInfo.photo?.big
                    if (photo != null && photo.local.path.isEmpty()) {
                        fileQueue.enqueue(photo.id, 1, FileDownloadQueue.DownloadType.DEFAULT, synchronous = false)
                        coRunCatching { fileQueue.waitForDownload(photo.id).await() }
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

                is TdApi.InternalLinkTypeBotStartInGroup ->
                    handlePublicChat(result.botUsername)

                is TdApi.InternalLinkTypeVideoChat ->
                    handlePublicChat(result.chatUsername)

                is TdApi.InternalLinkTypeStory ->
                    handlePublicChat(result.storyPosterUsername)

                is TdApi.InternalLinkTypeStoryAlbum ->
                    handlePublicChat(result.storyAlbumOwnerUsername)

                is TdApi.InternalLinkTypeMyProfilePage ->
                    handleMyProfileLink()

                is TdApi.InternalLinkTypeSavedMessages ->
                    handleSavedMessagesLink()

                is TdApi.InternalLinkTypeUserPhoneNumber ->
                    handleUserPhoneNumberLink(result.phoneNumber, result.openProfile)

                is TdApi.InternalLinkTypeUserToken ->
                    handleUserTokenLink(result.token)

                is TdApi.InternalLinkTypePremiumFeaturesPage,
                is TdApi.InternalLinkTypePremiumGiftCode,
                is TdApi.InternalLinkTypePremiumGiftPurchase,
                is TdApi.InternalLinkTypeStarPurchase,
                is TdApi.InternalLinkTypeRestorePurchases ->
                    LinkAction.OpenSettings(LinkAction.SettingsType.PREMIUM)

                is TdApi.InternalLinkTypeWebApp ->
                    LinkAction.OpenWebApp(0L, result.webAppShortName)

                is TdApi.InternalLinkTypeProxy -> {
                    val proxy = result.proxy ?: return LinkAction.ShowToast("Unsupported proxy type")
                    val type = proxy.type.toDomain()
                        ?: return LinkAction.ShowToast("Unsupported proxy type")
                    LinkAction.AddProxy(proxy.server, proxy.port, type)
                }

                is TdApi.InternalLinkTypeUnknownDeepLink ->
                    handleExternalOrUnknownLink(result.link)

                else -> handleExternalOrUnknownLink(normalized)
            }
        }.getOrElse {
            handleExternalOrUnknownLink(normalized)
        }
    }

    override suspend fun joinChat(inviteLink: String): Long? =
        coRunCatching {
            gateway.execute(TdApi.JoinChatByInviteLink(inviteLink)).id
        }.getOrNull()

    private suspend fun handlePublicChat(username: String): LinkAction {
        val chat = coRunCatching {
            gateway.execute(TdApi.SearchPublicChat(username))
        }.getOrNull() ?: return LinkAction.ShowToast("Chat not found")
        return resolveChatAction(chat)
    }

    private suspend fun handleMyProfileLink(): LinkAction {
        val me = coRunCatching { gateway.execute(TdApi.GetMe()) }.getOrNull()
            ?: return LinkAction.ShowToast("User not found")
        return LinkAction.OpenUser(me.id)
    }

    private suspend fun handleSavedMessagesLink(): LinkAction {
        val me = coRunCatching { gateway.execute(TdApi.GetMe()) }.getOrNull()
            ?: return LinkAction.ShowToast("Chat not found")
        val chat = coRunCatching {
            gateway.execute(TdApi.CreatePrivateChat(me.id, false))
        }.getOrNull() ?: return LinkAction.ShowToast("Chat not found")
        return LinkAction.OpenChat(chat.id)
    }

    private suspend fun handleUserPhoneNumberLink(phoneNumber: String, openProfile: Boolean): LinkAction {
        val user = coRunCatching {
            gateway.execute(TdApi.SearchUserByPhoneNumber(phoneNumber, true))
        }.getOrNull() ?: return LinkAction.ShowToast("User not found")

        if (openProfile) return LinkAction.OpenUser(user.id)

        val chat = coRunCatching {
            gateway.execute(TdApi.CreatePrivateChat(user.id, false))
        }.getOrNull()
        return if (chat != null) LinkAction.OpenChat(chat.id) else LinkAction.OpenUser(user.id)
    }

    private suspend fun handleUserTokenLink(token: String): LinkAction {
        val user = coRunCatching {
            gateway.execute(TdApi.SearchUserByToken(token))
        }.getOrNull() ?: return LinkAction.ShowToast("User not found")

        val chat = coRunCatching {
            gateway.execute(TdApi.CreatePrivateChat(user.id, false))
        }.getOrNull()
        return if (chat != null) LinkAction.OpenChat(chat.id) else LinkAction.OpenUser(user.id)
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
        val uri = coRunCatching { link.toUri() }.getOrNull()

        if (uri != null && uri.scheme.equals("tg", ignoreCase = true)) {
            if (uri.host.equals("resolve", ignoreCase = true)) {
                uri.getQueryParameter("user_id")?.toLongOrNull()?.let { return LinkAction.OpenUser(it) }

                uri.getQueryParameter("phone")?.takeIf { it.isNotBlank() }?.let {
                    return handleUserPhoneNumberLink(it, openProfile = false)
                }

                val username = uri.getQueryParameter("domain")?.takeIf { it.isNotBlank() }
                if (username != null) {
                    val hasMessageTarget = uri.getQueryParameter("post") != null ||
                            uri.getQueryParameter("thread") != null ||
                            uri.getQueryParameter("comment") != null
                    if (!hasMessageTarget) {
                        return handlePublicChat(username)
                    }
                }
            }

            return LinkAction.None
        }

        if (uri != null && (uri.scheme.equals("https", ignoreCase = true) || uri.scheme.equals("http", ignoreCase = true))) {
            val host = uri.host?.lowercase()
            val pathSegments = uri.pathSegments.orEmpty()

            if (host == "t.me" || host == "www.t.me" || host == "telegram.me" || host == "www.telegram.me") {
                val first = pathSegments.firstOrNull()
                val second = pathSegments.getOrNull(1)

                if (!first.isNullOrBlank()) {
                    if (first == "joinchat" && !second.isNullOrBlank()) {
                        return LinkAction.JoinChat("https://t.me/joinchat/$second")
                    }

                    if (first.startsWith("+")) {
                        return LinkAction.JoinChat("https://t.me/$first")
                    }

                    if (pathSegments.size == 1) {
                        return handlePublicChat(first)
                    }
                }
            }
        }

        return if (link.startsWith("http://") || link.startsWith("https://")) LinkAction.OpenExternalLink(link) else LinkAction.None
    }

    private fun normalizeLink(link: String): String = when {
        link.startsWith("tg://") -> link
        link.startsWith("https://t.me/") -> link
        link.startsWith("http://t.me/") -> link.replace("http://", "https://")
        link.startsWith("t.me/") -> "https://$link"
        else -> link
    }

    private fun tryParseProxyLink(link: String): LinkAction? {
        val uri = coRunCatching { link.toUri() }.getOrNull() ?: return null

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

    private fun tryParseUserLink(link: String): LinkAction? {
        val uri = coRunCatching { link.toUri() }.getOrNull() ?: return null
        if (!uri.scheme.equals("tg", ignoreCase = true)) return null

        val userId = when {
            uri.host.equals("user", ignoreCase = true) ->
                uri.getQueryParameter("id")?.toLongOrNull()

            uri.host.equals("openmessage", ignoreCase = true) ->
                uri.getQueryParameter("user_id")?.toLongOrNull()

            else -> null
        } ?: return null

        return LinkAction.OpenUser(userId)
    }

    private fun TdApi.SettingsSection?.toDomain(): LinkAction.SettingsType = when (this) {
        is TdApi.SettingsSectionPrivacyAndSecurity -> LinkAction.SettingsType.PRIVACY
        is TdApi.SettingsSectionDevices -> LinkAction.SettingsType.SESSIONS
        is TdApi.SettingsSectionChatFolders -> LinkAction.SettingsType.FOLDERS
        is TdApi.SettingsSectionAppearance,
        is TdApi.SettingsSectionNotifications -> LinkAction.SettingsType.CHAT
        is TdApi.SettingsSectionDataAndStorage -> LinkAction.SettingsType.DATA_STORAGE
        is TdApi.SettingsSectionPowerSaving -> LinkAction.SettingsType.POWER_SAVING
        is TdApi.SettingsSectionPremium -> LinkAction.SettingsType.PREMIUM
        else -> LinkAction.SettingsType.MAIN
    }

    private fun TdApi.ProxyType?.toDomain(): ProxyTypeModel? = when (this) {
        is TdApi.ProxyTypeMtproto -> ProxyTypeModel.Mtproto(secret)
        is TdApi.ProxyTypeSocks5 -> ProxyTypeModel.Socks5(username, password)
        is TdApi.ProxyTypeHttp -> ProxyTypeModel.Http(username, password, httpOnly)
        else -> null
    }
}