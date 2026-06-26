package org.monogram.presentation.features.profile

import com.arkivanov.decompose.value.Value
import org.monogram.domain.models.ChatFullInfoModel
import org.monogram.domain.models.ChatModel
import org.monogram.domain.models.ChatPermissionsModel
import org.monogram.domain.models.ChatRevenueStatisticsModel
import org.monogram.domain.models.ChatStatisticsModel
import org.monogram.domain.models.GroupMemberModel
import org.monogram.domain.models.MessageModel
import org.monogram.domain.models.UserModel
import org.monogram.domain.repository.ChatMemberStatus
import org.monogram.domain.repository.MessageRepository
import org.monogram.presentation.core.util.IDownloadUtils
import org.monogram.presentation.features.chats.common.ChatActionState

interface ProfileComponent {
    val state: Value<State>
    val messageRepository: MessageRepository
    val downloadUtils: IDownloadUtils

    fun onBack()
    fun onTabSelected(tabKey: ProfileTabKey)
    fun onMessageClick(message: MessageModel)
    fun onMessageLongClick(message: MessageModel)
    fun onAvatarClick()
    fun onDismissViewer()
    fun onDismissImages()
    fun onDismissVideo()
    fun onDismissInstantView()
    fun onDismissYouTube()
    fun onDismissWebView()
    fun onDismissInvoice(status: String?)
    fun onForwardMessage(message: MessageModel)
    fun onDeleteMessage(message: MessageModel, revoke: Boolean)
    fun onOpenVideo(path: String, messageId: Long?, caption: String?)
    fun onDownloadHighRes(messageId: Long)
    fun onAddToGifs(path: String)
    fun onOpenWebView(url: String)
    fun onDismissMiniAppTOS()
    fun onAcceptMiniAppTOS()
    fun onLoadMoreMedia()
    fun onOpenMiniApp(url: String, name: String, chatId: Long)
    fun onDismissMiniApp()
    fun onToggleMute()
    fun onEdit()
    fun onShowQRCode()
    fun onDismissQRCode()
    fun onSendMessage()
    fun onToggleBlockUser()
    fun onDeleteChat()
    fun onEditContact()
    fun onToggleContact()
    fun onLeave()
    fun onJoinChat()
    fun onReport(reason: String)
    fun onDismissReport()
    fun onShowReport()
    fun onShowLogs()
    fun onMemberClick(userId: Long)
    fun onMemberLongClick(userId: Long)

    fun onUpdateChatTitle(title: String)
    fun onUpdateChatDescription(description: String)
    fun onUpdateChatUsername(username: String)
    fun onUpdateChatPermissions(permissions: ChatPermissionsModel)
    fun onUpdateChatSlowModeDelay(delay: Int)
    fun onUpdateMemberStatus(userId: Long, status: ChatMemberStatus)

    fun onShowStatistics()
    fun onShowRevenueStatistics()
    fun onDismissStatistics()
    fun onLoadStatisticsGraph(token: String)
    fun onDownloadMedia(message: MessageModel)
    fun onLinkedChatClick()

    fun onShowPermissions()
    fun onDismissPermissions()
    fun onTogglePermission(permission: String)

    fun onAcceptTOS()
    fun onDismissTOS()

    fun onLocationClick(lat: Double, lon: Double, address: String)
    fun onDismissLocation()

    data class State(
        val chatId: Long,
        val chat: ChatModel? = null,
        val user: UserModel? = null,

        val fullInfo: ChatFullInfoModel? = null,

        val isLoading: Boolean = false,
        val about: String? = null,
        val publicLink: String? = null,

        val visibleTabs: List<ProfileTabSpec> = emptyList(),
        val selectedTabKey: ProfileTabKey = ProfileTabKey.MEDIA,
        val messageTabs: Map<ProfileTabKey, MessageTabState> = defaultMessageTabStates(),
        val membersTab: MembersTabState = MembersTabState(),

        val profilePhotos: List<String> = emptyList(),
        val personalAvatarPath: String? = null,

        val fullScreenImages: List<String>? = null,
        val fullScreenImageMessageIds: List<Long> = emptyList(),
        val fullScreenCaptions: List<String?> = emptyList(),
        val fullScreenStartIndex: Int = 0,
        val fullScreenVideoPath: String? = null,
        val fullScreenVideoMessageId: Long? = null,
        val fullScreenVideoCaption: String? = null,
        val isViewingProfilePhotos: Boolean = false,
        val isProfilePhotoHdLoading: Boolean = false,

        val instantViewUrl: String? = null,
        val youtubeUrl: String? = null,
        val webViewUrl: String? = null,
        val invoiceSlug: String? = null,
        val invoiceMessageId: Long? = null,

        val autoDownloadWifi: Boolean = true,
        val autoDownloadRoaming: Boolean = false,
        val autoDownloadMobile: Boolean = true,

        val isPlayerGesturesEnabled: Boolean = true,
        val isPlayerDoubleTapSeekEnabled: Boolean = true,
        val playerSeekDuration: Int = 10,
        val isPlayerZoomEnabled: Boolean = true,
        val isInstalledFromGooglePlay: Boolean = false,

        val miniAppUrl: String? = null,
        val miniAppName: String? = null,
        val currentUser: UserModel? = null,
        val isBlocked: Boolean = false,
        val botWebAppUrl: String? = null,
        val botWebAppName: String? = null,

        val isQrVisible: Boolean = false,
        val qrContent: String = "",
        val isReportVisible: Boolean = false,
        val actionState: ChatActionState = ChatActionState.Idle,

        val statistics: ChatStatisticsModel? = null,
        val revenueStatistics: ChatRevenueStatisticsModel? = null,
        val isStatisticsVisible: Boolean = false,
        val isRevenueStatisticsVisible: Boolean = false,

        val linkedChat: ChatModel? = null,

        val isPermissionsVisible: Boolean = false,
        val botPermissions: Map<String, Boolean> = emptyMap(),

        val isTOSVisible: Boolean = false,
        val showMiniAppTOS: Boolean = false,
        val isTOSAccepted: Boolean = false,
        val isAcceptingTOS: Boolean = false,
        val pendingMiniAppUrl: String? = null,
        val pendingMiniAppName: String? = null,

        val selectedLocation: LocationData? = null
    ) {
        val selectedTab: ProfileTabSpec?
            get() = visibleTabs.firstOrNull { it.key == selectedTabKey }

        fun messageTabState(key: ProfileTabKey): MessageTabState =
            messageTabs[key] ?: MessageTabState()

        val mediaMessages: List<MessageModel>
            get() = messageTabState(ProfileTabKey.MEDIA).items

        val fileMessages: List<MessageModel>
            get() = messageTabState(ProfileTabKey.FILES).items

        val musicMessages: List<MessageModel>
            get() = messageTabState(ProfileTabKey.MUSIC).items

        val voiceMessages: List<MessageModel>
            get() = messageTabState(ProfileTabKey.VOICE).items

        val linkMessages: List<MessageModel>
            get() = messageTabState(ProfileTabKey.LINKS).items

        val gifMessages: List<MessageModel>
            get() = messageTabState(ProfileTabKey.GIFS).items

        val members: List<GroupMemberModel>
            get() = membersTab.items
    }

    data class LocationData(
        val latitude: Double,
        val longitude: Double,
        val address: String
    )

    data class MessageTabState(
        val items: List<MessageModel> = emptyList(),
        val isLoadingInitial: Boolean = false,
        val isLoadingNext: Boolean = false,
        val canLoadMore: Boolean = true,
        val nextFromMessageId: Long = 0L,
        val hasLoaded: Boolean = false
    )

    data class MembersTabState(
        val items: List<GroupMemberModel> = emptyList(),
        val isLoadingInitial: Boolean = false,
        val isLoadingNext: Boolean = false,
        val canLoadMore: Boolean = true,
        val nextOffset: Int = 0,
        val hasLoaded: Boolean = false
    )
}

private fun defaultMessageTabStates(): Map<ProfileTabKey, ProfileComponent.MessageTabState> =
    listOf(
        ProfileTabKey.MEDIA,
        ProfileTabKey.FILES,
        ProfileTabKey.MUSIC,
        ProfileTabKey.VOICE,
        ProfileTabKey.LINKS,
        ProfileTabKey.GIFS
    ).associateWith { ProfileComponent.MessageTabState() }
