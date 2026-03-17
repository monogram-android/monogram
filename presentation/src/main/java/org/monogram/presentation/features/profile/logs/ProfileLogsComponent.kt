package org.monogram.presentation.features.profile.logs

import com.arkivanov.decompose.value.Value
import org.monogram.domain.models.ChatEventLogFiltersModel
import org.monogram.domain.models.ChatEventModel
import org.monogram.domain.repository.MessageRepository
import org.monogram.presentation.core.util.IDownloadUtils
import org.monogram.presentation.features.chats.currentChat.components.VideoPlayerPool

interface ProfileLogsComponent {
    val state: Value<State>
    val messageRepository: MessageRepository
    val downloadUtils: IDownloadUtils
    val videoPlayerPool: VideoPlayerPool

    fun onBack()
    fun onLoadMore()
    fun onToggleFilter(filterType: FilterType)
    fun onToggleUserFilter(userId: Long)
    fun onApplyFilters()
    fun onResetFilters()
    fun onDismissFilters()
    fun onRefresh()
    fun onShowFilters()

    fun onPhotoClick(path: String, caption: String)
    fun onVideoClick(path: String, caption: String, fileId: Int, supportsStreaming: Boolean)
    fun onDismissViewer()
    fun onUserClick(userId: Long)

    data class State(
        val logs: List<ChatEventModel> = emptyList(),
        val isLoading: Boolean = false,
        val isLoadingMore: Boolean = false,
        val canLoadMore: Boolean = true,
        val filters: ChatEventLogFiltersModel = ChatEventLogFiltersModel(),
        val pendingFilters: ChatEventLogFiltersModel = ChatEventLogFiltersModel(),
        val isFiltersVisible: Boolean = false,
        val senderInfo: Map<Long, SenderInfo> = emptyMap(),

        val fullScreenPhotoPath: String? = null,
        val fullScreenPhotoCaption: String? = null,
        val fullScreenVideoPath: String? = null,
        val fullScreenVideoCaption: String? = null,
        val fullScreenVideoFileId: Int = 0,
        val fullScreenVideoSupportsStreaming: Boolean = false
    )

    data class SenderInfo(
        val name: String,
        val avatarPath: String?
    )

    enum class FilterType {
        MESSAGE_EDITS, MESSAGE_DELETIONS, MESSAGE_PINS, MEMBER_JOINS, MEMBER_LEAVES,
        MEMBER_INVITES, MEMBER_PROMOTIONS, MEMBER_RESTRICTIONS, INFO_CHANGES,
        SETTING_CHANGES, INVITE_LINK_CHANGES, VIDEO_CHAT_CHANGES, FORUM_CHANGES,
        SUBSCRIPTION_EXTENSIONS
    }
}
