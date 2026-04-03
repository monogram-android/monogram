package org.monogram.presentation.features.profile

import com.arkivanov.decompose.value.Value
import org.monogram.domain.models.*
import org.monogram.domain.repository.ChatMemberStatus
import org.monogram.domain.repository.MessageRepository
import org.monogram.presentation.core.util.IDownloadUtils

interface ProfileComponent {
    val state: Value<State>
    val messageRepository: MessageRepository
    val downloadUtils: IDownloadUtils

    fun onBack()
    fun onTabSelected(index: Int)
    fun onMessageClick(message: MessageModel)
    fun onMessageLongClick(message: MessageModel)
    fun onAvatarClick()
    fun onDismissViewer()
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
    fun onEditContact(firstName: String, lastName: String)
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

        val selectedTabIndex: Int = 0,

        val mediaMessages: List<MessageModel> = emptyList(),
        val fileMessages: List<MessageModel> = emptyList(),
        val audioMessages: List<MessageModel> = emptyList(),
        val voiceMessages: List<MessageModel> = emptyList(),
        val linkMessages: List<MessageModel> = emptyList(),
        val gifMessages: List<MessageModel> = emptyList(),
        val members: List<GroupMemberModel> = emptyList(),
        val isLoadingMembers: Boolean = false,
        val canLoadMoreMembers: Boolean = true,

        val isLoadingMedia: Boolean = false,
        val isLoadingMoreMedia: Boolean = false,
        val canLoadMoreMedia: Boolean = true,

        val profilePhotos: List<String> = emptyList(),
        val personalAvatarPath: String? = null,

        val fullScreenImages: List<String>? = null,
        val fullScreenCaptions: List<String?> = emptyList(),
        val fullScreenStartIndex: Int = 0,
        val fullScreenVideoPath: String? = null,
        val fullScreenVideoCaption: String? = null,
        val isViewingProfilePhotos: Boolean = false,
        val isProfilePhotoHdLoading: Boolean = false,

        val miniAppUrl: String? = null,
        val miniAppName: String? = null,
        val currentUser: UserModel? = null,
        val isBlocked: Boolean = false,
        val botWebAppUrl: String? = null,
        val botWebAppName: String? = null,

        val isQrVisible: Boolean = false,
        val qrContent: String = "",
        val isReportVisible: Boolean = false,

        val statistics: ChatStatisticsModel? = null,
        val revenueStatistics: ChatRevenueStatisticsModel? = null,
        val isStatisticsVisible: Boolean = false,
        val isRevenueStatisticsVisible: Boolean = false,

        val linkedChat: ChatModel? = null,

        val isPermissionsVisible: Boolean = false,
        val botPermissions: Map<String, Boolean> = emptyMap(),

        val isTOSVisible: Boolean = false,
        val isTOSAccepted: Boolean = false,
        val isAcceptingTOS: Boolean = false,
        val pendingMiniAppUrl: String? = null,
        val pendingMiniAppName: String? = null,

        val selectedLocation: LocationData? = null
    )

    data class LocationData(
        val latitude: Double,
        val longitude: Double,
        val address: String
    )
}
