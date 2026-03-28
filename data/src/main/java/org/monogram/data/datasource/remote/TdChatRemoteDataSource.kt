package org.monogram.data.datasource.remote

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import org.drinkless.tdlib.TdApi
import org.monogram.data.gateway.TelegramGateway
import org.monogram.domain.models.ChatPermissionsModel

class TdChatRemoteSource(
    private val gateway: TelegramGateway,
    private val connectivityManager: ConnectivityManager
) : ChatRemoteSource {

    override suspend fun loadChats(chatList: TdApi.ChatList, limit: Int) {
        runCatching { gateway.execute(TdApi.LoadChats(chatList, limit)) }
    }

    override suspend fun searchChats(query: String, limit: Int): TdApi.Chats? {
        return runCatching { gateway.execute(TdApi.SearchChats(query, limit)) }.getOrNull()
    }

    override suspend fun searchPublicChats(query: String): TdApi.Chats? {
        return runCatching { gateway.execute(TdApi.SearchPublicChats(query)) }.getOrNull()
    }

    override suspend fun searchMessages(query: String, offset: String, limit: Int): TdApi.FoundMessages? {
        return runCatching {
            gateway.execute(
                TdApi.SearchMessages(null, query, offset, limit, TdApi.SearchMessagesFilterEmpty(), null, 0, 0)
            )
        }.getOrNull()
    }

    override suspend fun getChat(chatId: Long): TdApi.Chat? {
        return runCatching { gateway.execute(TdApi.GetChat(chatId)) }.getOrNull()
    }

    override suspend fun createGroup(title: String, userIds: List<Long>, messageAutoDeleteTime: Int): Long {
        return runCatching {
            gateway.execute(TdApi.CreateNewBasicGroupChat(userIds.toLongArray(), title, messageAutoDeleteTime)).chatId
        }.getOrDefault(0L)
    }

    override suspend fun createChannel(
        title: String,
        description: String,
        isMegagroup: Boolean,
        messageAutoDeleteTime: Int
    ): Long {
        return runCatching {
            gateway.execute(
                TdApi.CreateNewSupergroupChat(title, isMegagroup, !isMegagroup, description, null, messageAutoDeleteTime, false)
            ).id
        }.getOrDefault(0L)
    }

    override suspend fun setChatPhoto(chatId: Long, photoPath: String) {
        runCatching {
            gateway.execute(TdApi.SetChatPhoto(chatId, TdApi.InputChatPhotoStatic(TdApi.InputFileLocal(photoPath))))
        }
    }

    override suspend fun setChatTitle(chatId: Long, title: String) {
        runCatching { gateway.execute(TdApi.SetChatTitle(chatId, title)) }
    }

    override suspend fun setChatDescription(chatId: Long, description: String) {
        runCatching {
            val chat = gateway.execute(TdApi.GetChat(chatId))
            val groupId = when (val type = chat.type) {
                is TdApi.ChatTypeSupergroup -> type.supergroupId
                is TdApi.ChatTypeBasicGroup -> type.basicGroupId
                else -> return
            }
            gateway.execute(TdApi.SetChatDescription(groupId, description))
        }
    }

    override suspend fun setChatUsername(chatId: Long, username: String) {
        runCatching {
            val chat = gateway.execute(TdApi.GetChat(chatId))
            val type = chat.type as? TdApi.ChatTypeSupergroup ?: return
            gateway.execute(TdApi.SetSupergroupUsername(type.supergroupId, username))
        }
    }

    override suspend fun setChatPermissions(chatId: Long, permissions: ChatPermissionsModel) {
        runCatching {
            gateway.execute(TdApi.SetChatPermissions(chatId, permissions.toTd()))
        }
    }

    override suspend fun setChatSlowModeDelay(chatId: Long, slowModeDelay: Int) {
        runCatching {
            val chat = gateway.execute(TdApi.GetChat(chatId))
            val supergroupId = (chat.type as? TdApi.ChatTypeSupergroup)?.supergroupId ?: chatId
            gateway.execute(TdApi.SetChatSlowModeDelay(supergroupId, slowModeDelay))
        }
    }

    override suspend fun toggleChatIsForum(chatId: Long, isForum: Boolean) {
        runCatching {
            val chat = gateway.execute(TdApi.GetChat(chatId))
            val type = chat.type as? TdApi.ChatTypeSupergroup ?: return
            gateway.execute(TdApi.ToggleSupergroupIsForum(type.supergroupId, isForum, isForum))
        }
    }

    override suspend fun toggleChatIsTranslatable(chatId: Long, isTranslatable: Boolean) {
        runCatching {
            val chat = gateway.execute(TdApi.GetChat(chatId))
            if (chat.type is TdApi.ChatTypeSupergroup) {
                val supergroupId = (chat.type as TdApi.ChatTypeSupergroup).supergroupId
                gateway.execute(TdApi.ToggleSupergroupHasAutomaticTranslation(supergroupId, isTranslatable))
            } else {
                gateway.execute(TdApi.ToggleChatIsTranslatable(chatId, isTranslatable))
            }
        }
    }

    override suspend fun getChatLink(chatId: Long): String? {
        return runCatching {
            val chat = gateway.execute(TdApi.GetChat(chatId))
            when (val type = chat.type) {
                is TdApi.ChatTypePrivate -> {
                    val user = gateway.execute(TdApi.GetUser(type.userId))
                    user.usernames?.activeUsernames?.firstOrNull()?.let { "https://t.me/$it" }
                }
                is TdApi.ChatTypeSupergroup -> {
                    if (type.supergroupId == 0L) return@runCatching null
                    val sg = gateway.execute(TdApi.GetSupergroup(type.supergroupId))
                    sg.usernames?.activeUsernames?.firstOrNull()?.let { "https://t.me/$it" }
                }
                else -> null
            }
        }.getOrNull()
    }

    override suspend fun deleteFolder(folderId: Int) {
        runCatching { gateway.execute(TdApi.DeleteChatFolder(folderId, longArrayOf())) }
    }

    override suspend fun muteChat(chatId: Long, muteFor: Int) {
        runCatching {
            val settings = TdApi.ChatNotificationSettings(
                false, muteFor, false, 0L,
                false, false, false, false,
                false, 0L, false, false,
                false, false, false, false
            )
            gateway.execute(TdApi.SetChatNotificationSettings(chatId, settings))
        }
    }

    override suspend fun archiveChat(chatId: Long, archive: Boolean) {
        runCatching {
            val list = if (archive) TdApi.ChatListArchive() else TdApi.ChatListMain()
            gateway.execute(TdApi.AddChatToList(chatId, list))
        }
    }

    override suspend fun deleteChat(chatId: Long) {
        runCatching { gateway.execute(TdApi.DeleteChat(chatId)) }
    }

    override suspend fun leaveChat(chatId: Long) {
        runCatching { gateway.execute(TdApi.LeaveChat(chatId)) }
    }

    override suspend fun clearChatHistory(chatId: Long, revoke: Boolean) {
        runCatching { gateway.execute(TdApi.DeleteChatHistory(chatId, false, revoke)) }
    }

    override suspend fun reportChat(chatId: Long, reason: String, messageIds: List<Long>) {
        runCatching {
            val msgArray = messageIds.toLongArray()
            val predefined = setOf(
                "spam", "violence", "pornography", "child_abuse",
                "copyright", "fake", "illegal_drugs", "personal_details", "unrelated_location"
            )
            val isCustom = reason !in predefined
            val reportText = if (isCustom) reason else ""

            val first = gateway.execute(TdApi.ReportChat(chatId, null, msgArray, reportText))
            if (first is TdApi.ReportChatResultOptionRequired) {
                val option = if (isCustom) first.options.lastOrNull()
                else first.options.find { it.text.contains(reason, ignoreCase = true) }
                    ?: first.options.firstOrNull()
                option?.let { gateway.execute(TdApi.ReportChat(chatId, it.id, msgArray, reportText)) }
            }
        }
    }

    override suspend fun getMyUserId(): Long {
        return runCatching { gateway.execute(TdApi.GetMe()).id }.getOrDefault(0L)
    }

    override suspend fun setNetworkType(): Boolean {
        val networkType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val activeNetwork = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
            when {
                capabilities == null -> TdApi.NetworkTypeNone()
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> TdApi.NetworkTypeWiFi()
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        // Check for roaming if possible, otherwise default to Mobile
                        TdApi.NetworkTypeMobile()
                    } else {
                        TdApi.NetworkTypeMobile()
                    }
                }

                else -> TdApi.NetworkTypeOther()
            }
        } else {
            @Suppress("DEPRECATION")
            val activeNetworkInfo = connectivityManager.activeNetworkInfo
            when (activeNetworkInfo?.type) {
                ConnectivityManager.TYPE_WIFI -> TdApi.NetworkTypeWiFi()
                ConnectivityManager.TYPE_MOBILE -> TdApi.NetworkTypeMobile()
                else -> TdApi.NetworkTypeOther()
            }
        }
        return runCatching { gateway.execute(TdApi.SetNetworkType(networkType)) }.isSuccess
    }

    override suspend fun getConnectionState(): TdApi.ConnectionState? {
        val option = runCatching { gateway.execute(TdApi.GetOption("connection_state")) }.getOrNull() ?: return null

        val normalized = when (option) {
            is TdApi.OptionValueString -> option.value.lowercase()
            is TdApi.OptionValueInteger -> option.value.toString()
            else -> return null
        }

        return when {
            normalized == "4" || normalized.contains("ready") -> TdApi.ConnectionStateReady()
            normalized == "3" || normalized.contains("updating") -> TdApi.ConnectionStateUpdating()
            normalized == "2" || normalized.contains("proxy") -> TdApi.ConnectionStateConnectingToProxy()
            normalized == "1" || normalized.contains("network") -> TdApi.ConnectionStateWaitingForNetwork()
            normalized == "0" || normalized.contains("connecting") -> TdApi.ConnectionStateConnecting()
            else -> null
        }
    }

    override suspend fun getForumTopics(
        chatId: Long,
        query: String,
        offsetDate: Int,
        offsetMessageId: Long,
        offsetForumTopicId: Int,
        limit: Int
    ): TdApi.ForumTopics? {
        return runCatching {
            gateway.execute(
                TdApi.GetForumTopics(chatId, query, offsetDate, offsetMessageId, offsetForumTopicId, limit)
            )
        }.getOrNull()
    }

    private fun ChatPermissionsModel.toTd() = TdApi.ChatPermissions(
        canSendBasicMessages,
        canSendAudios,
        canSendDocuments,
        canSendPhotos,
        canSendVideos,
        canSendVideoNotes,
        canSendVoiceNotes,
        canSendPolls,
        canSendOtherMessages,
        canAddLinkPreviews,
        canEditTag,
        canChangeInfo,
        canInviteUsers,
        canPinMessages,
        canCreateTopics
    )
}