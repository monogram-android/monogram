package org.monogram.domain.repository

import org.monogram.domain.models.ChatFullInfoModel
import org.monogram.domain.models.ChatModel
import org.monogram.domain.models.ProxyTypeModel

interface LinkHandlerRepository {
    suspend fun handleLink(link: String): LinkAction
    suspend fun joinChat(inviteLink: String): Long?
}

sealed class LinkAction {
    data class OpenChat(val chatId: Long) : LinkAction()
    data class OpenUser(val userId: Long) : LinkAction()
    data class OpenMessage(val chatId: Long, val messageId: Long) : LinkAction()
    data class OpenSettings(val settingsType: SettingsType) : LinkAction()
    data class OpenStickerSet(val name: String) : LinkAction()
    data class JoinChat(val inviteLink: String) : LinkAction()
    data class ConfirmJoinChat(val chat: ChatModel, val fullInfo: ChatFullInfoModel) : LinkAction()
    data class ConfirmJoinInviteLink(
        val inviteLink: String,
        val title: String,
        val description: String,
        val memberCount: Int,
        val avatarPath: String?,
        val isChannel: Boolean
    ) : LinkAction()
    data class OpenWebApp(val botUserId: Long, val url: String) : LinkAction()
    data class OpenExternalLink(val url: String) : LinkAction()
    data object OpenActiveSessions : LinkAction()
    data class ShowToast(val message: String) : LinkAction()
    data class AddProxy(val server: String, val port: Int, val type: ProxyTypeModel) : LinkAction()
    data object None : LinkAction()

    enum class SettingsType {
        MAIN, PRIVACY, SESSIONS, FOLDERS, CHAT, DATA_STORAGE, POWER_SAVING, PREMIUM
    }
}