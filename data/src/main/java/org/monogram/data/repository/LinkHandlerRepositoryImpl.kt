package org.monogram.data.repository

import android.util.Log
import org.drinkless.tdlib.TdApi
import org.monogram.data.core.coRunCatching
import org.monogram.data.datasource.remote.LinkRemoteDataSource
import org.monogram.data.infra.FileDownloadQueue
import org.monogram.data.mapper.toLinkProxyTypeOrNull
import org.monogram.data.mapper.toLinkSettingsType
import org.monogram.domain.repository.ChatInfoRepository
import org.monogram.domain.repository.ChatListRepository
import org.monogram.domain.repository.LinkAction
import org.monogram.domain.repository.LinkHandlerRepository

class LinkHandlerRepositoryImpl(
    private val parser: LinkParser,
    private val remote: LinkRemoteDataSource,
    private val chatListRepository: ChatListRepository,
    private val chatInfoRepository: ChatInfoRepository,
    private val fileQueue: FileDownloadQueue
) : LinkHandlerRepository {
    override suspend fun handleLink(link: String): LinkAction {
        val normalized = parser.normalize(link)

        parser.parsePrimary(normalized)?.let { return handleParsedLink(it) }

        val internalLink = remote.getInternalLinkType(normalized)
            ?: return handleParsedLink(parser.parseFallback(normalized))

        return coRunCatching {
            handleInternalLink(internalLink, normalized)
        }.onFailure {
            Log.w(TAG, "Failed to handle internal link: $normalized", it)
        }.getOrElse {
            handleParsedLink(parser.parseFallback(normalized))
        }
    }

    override suspend fun joinChat(inviteLink: String): Long? {
        val chat = remote.joinChatByInviteLink(inviteLink)
        if (chat == null) {
            Log.w(TAG, "Failed to join chat by invite link")
        }
        return chat?.id
    }

    private suspend fun handleInternalLink(
        internalLink: TdApi.InternalLinkType,
        normalized: String
    ): LinkAction = when (internalLink) {
        is TdApi.InternalLinkTypePublicChat -> handlePublicChat(internalLink.chatUsername)
        is TdApi.InternalLinkTypeMessage -> handleMessageLink(internalLink.url)
        is TdApi.InternalLinkTypeSettings -> LinkAction.OpenSettings(internalLink.section.toLinkSettingsType())
        is TdApi.InternalLinkTypeStickerSet -> LinkAction.OpenStickerSet(internalLink.stickerSetName)
        is TdApi.InternalLinkTypeChatInvite -> handleChatInviteLink(internalLink.inviteLink)
        is TdApi.InternalLinkTypeBotStart -> handlePublicChat(internalLink.botUsername)
        is TdApi.InternalLinkTypeBotStartInGroup -> handlePublicChat(internalLink.botUsername)
        is TdApi.InternalLinkTypeVideoChat -> handlePublicChat(internalLink.chatUsername)
        is TdApi.InternalLinkTypeStory -> handlePublicChat(internalLink.storyPosterUsername)
        is TdApi.InternalLinkTypeStoryAlbum -> handlePublicChat(internalLink.storyAlbumOwnerUsername)
        is TdApi.InternalLinkTypeMyProfilePage -> handleMyProfileLink()
        is TdApi.InternalLinkTypeSavedMessages -> handleSavedMessagesLink()
        is TdApi.InternalLinkTypeUserPhoneNumber ->
            handleUserPhoneNumberLink(internalLink.phoneNumber, internalLink.openProfile)

        is TdApi.InternalLinkTypeUserToken -> handleUserTokenLink(internalLink.token)

        is TdApi.InternalLinkTypePremiumFeaturesPage,
        is TdApi.InternalLinkTypePremiumGiftCode,
        is TdApi.InternalLinkTypePremiumGiftPurchase,
        is TdApi.InternalLinkTypeStarPurchase,
        is TdApi.InternalLinkTypeRestorePurchases ->
            LinkAction.OpenSettings(LinkAction.SettingsType.PREMIUM)

        is TdApi.InternalLinkTypeWebApp -> LinkAction.OpenWebApp(0L, internalLink.webAppShortName)
        is TdApi.InternalLinkTypeProxy -> handleInternalProxy(internalLink)
        is TdApi.InternalLinkTypeUnknownDeepLink -> handleParsedLink(parser.parseFallback(internalLink.link))
        else -> handleParsedLink(parser.parseFallback(normalized))
    }

    private suspend fun handleParsedLink(parsed: ParsedLink): LinkAction = when (parsed) {
        is ParsedLink.AddProxy -> LinkAction.AddProxy(parsed.server, parsed.port, parsed.type)
        is ParsedLink.OpenUser -> LinkAction.OpenUser(parsed.userId)
        is ParsedLink.ResolveByPhone -> handleUserPhoneNumberLink(parsed.phoneNumber, parsed.openProfile)
        is ParsedLink.OpenPublicChat -> handlePublicChat(parsed.username)
        is ParsedLink.JoinChat -> LinkAction.JoinChat(parsed.inviteLink)
        is ParsedLink.OpenExternal -> LinkAction.OpenExternalLink(parsed.url)
        ParsedLink.None -> LinkAction.None
    }

    private suspend fun handleMessageLink(url: String): LinkAction {
        val info = remote.getMessageLinkInfo(url)
        val message = info?.message
        return when {
            info == null || info.chatId == 0L -> LinkAction.ShowToast(MSG_MESSAGE_NOT_FOUND)
            message != null -> LinkAction.OpenMessage(info.chatId, message.id)
            else -> LinkAction.OpenChat(info.chatId)
        }
    }

    private suspend fun handleChatInviteLink(inviteLink: String): LinkAction {
        val inviteInfo = remote.checkChatInviteLink(inviteLink) ?: return LinkAction.JoinChat(inviteLink)

        val photo = inviteInfo.photo?.small ?: inviteInfo.photo?.big
        if (photo != null && photo.local.path.isEmpty()) {
            fileQueue.enqueue(photo.id, 1, FileDownloadQueue.DownloadType.DEFAULT, synchronous = false)
            coRunCatching {
                fileQueue.waitForDownload(photo.id).await()
            }.onFailure {
                Log.w(TAG, "Failed to download invite photo for file ${photo.id}", it)
            }
        }

        return LinkAction.ConfirmJoinInviteLink(
            inviteLink = inviteLink,
            title = inviteInfo.title,
            description = inviteInfo.description,
            memberCount = inviteInfo.memberCount,
            avatarPath = photo?.local?.path?.ifEmpty { null },
            isChannel = inviteInfo.type is TdApi.InviteLinkChatTypeChannel
        )
    }

    private suspend fun handlePublicChat(username: String): LinkAction {
        val chat = remote.searchPublicChat(username)
            ?: return LinkAction.ShowToast(MSG_CHAT_NOT_FOUND)
        return resolveChatAction(chat)
    }

    private suspend fun handleMyProfileLink(): LinkAction {
        val me = remote.getMe() ?: return LinkAction.ShowToast(MSG_USER_NOT_FOUND)
        return LinkAction.OpenUser(me.id)
    }

    private suspend fun handleSavedMessagesLink(): LinkAction {
        val me = remote.getMe() ?: return LinkAction.ShowToast(MSG_CHAT_NOT_FOUND)
        val chat = remote.createPrivateChat(me.id) ?: return LinkAction.ShowToast(MSG_CHAT_NOT_FOUND)
        return LinkAction.OpenChat(chat.id)
    }

    private suspend fun handleUserPhoneNumberLink(phoneNumber: String, openProfile: Boolean): LinkAction {
        val user = remote.searchUserByPhoneNumber(phoneNumber)
            ?: return LinkAction.ShowToast(MSG_USER_NOT_FOUND)

        if (openProfile) return LinkAction.OpenUser(user.id)
        return resolveUserAction(user.id)
    }

    private suspend fun handleUserTokenLink(token: String): LinkAction {
        val user = remote.searchUserByToken(token) ?: return LinkAction.ShowToast(MSG_USER_NOT_FOUND)
        return resolveUserAction(user.id)
    }

    private suspend fun resolveUserAction(userId: Long): LinkAction {
        val chat = remote.createPrivateChat(userId)
        return if (chat != null) {
            LinkAction.OpenChat(chat.id)
        } else {
            LinkAction.OpenUser(userId)
        }
    }

    private suspend fun resolveChatAction(chat: TdApi.Chat): LinkAction {
        val needsConfirm = chat.type is TdApi.ChatTypeSupergroup && chat.positions.isEmpty()
        if (!needsConfirm) return LinkAction.OpenChat(chat.id)

        val chatModel = chatListRepository.getChatById(chat.id)
        val fullInfo = chatInfoRepository.getChatFullInfo(chat.id)
        return if (chatModel != null && fullInfo != null) {
            LinkAction.ConfirmJoinChat(chatModel, fullInfo)
        } else {
            LinkAction.OpenChat(chat.id)
        }
    }

    private fun handleInternalProxy(internalLink: TdApi.InternalLinkTypeProxy): LinkAction {
        val proxy = internalLink.proxy ?: return LinkAction.ShowToast(MSG_UNSUPPORTED_PROXY_TYPE)
        val type = proxy.type.toLinkProxyTypeOrNull()
            ?: return LinkAction.ShowToast(MSG_UNSUPPORTED_PROXY_TYPE)
        return LinkAction.AddProxy(proxy.server, proxy.port, type)
    }

    companion object {
        private const val TAG = "LinkHandlerRepository"
        private const val MSG_CHAT_NOT_FOUND = "Chat not found"
        private const val MSG_USER_NOT_FOUND = "User not found"
        private const val MSG_MESSAGE_NOT_FOUND = "Message not found"
        private const val MSG_UNSUPPORTED_PROXY_TYPE = "Unsupported proxy type"
    }
}