package org.monogram.presentation.features.profile.logs

import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.arkivanov.decompose.value.update
import kotlinx.coroutines.launch
import org.monogram.domain.models.ChatEventActionModel
import org.monogram.domain.models.ChatEventLogFiltersModel
import org.monogram.domain.models.MessageSenderModel
import org.monogram.domain.repository.ChatEventLogRepository
import org.monogram.domain.repository.UserRepository
import org.monogram.presentation.core.util.IDownloadUtils
import org.monogram.presentation.core.util.componentScope
import org.monogram.presentation.root.AppComponentContext

class DefaultProfileLogsComponent(
    context: AppComponentContext,
    private val chatId: Long,
    private val onBackClicked: () -> Unit,
    private val onUserClicked: (Long) -> Unit,
    override val downloadUtils: IDownloadUtils
) : ProfileLogsComponent, AppComponentContext by context {

    override val messageRepository: ChatEventLogRepository = container.repositories.chatEventLogRepository
    private val userRepository: UserRepository = container.repositories.userRepository

    private val scope = componentScope
    private val _state = MutableValue(ProfileLogsComponent.State())
    override val state: Value<ProfileLogsComponent.State> = _state

    private val PAGE_SIZE = 50

    init {
        loadLogs(isFirstLoad = true)
    }

    private fun loadLogs(isFirstLoad: Boolean) {
        if (isFirstLoad) {
            _state.update { it.copy(isLoading = true, logs = emptyList(), canLoadMore = true) }
        } else {
            if (_state.value.isLoadingMore || !_state.value.canLoadMore) return
            _state.update { it.copy(isLoadingMore = true) }
        }

        scope.launch {
            try {
                val fromEventId = if (isFirstLoad) 0L else _state.value.logs.lastOrNull()?.id ?: 0L
                val newLogs = messageRepository.getChatEventLog(
                    chatId = chatId,
                    fromEventId = fromEventId,
                    limit = PAGE_SIZE,
                    filters = _state.value.filters,
                    userIds = _state.value.filters.userIds
                )

                val senderIds = mutableSetOf<Long>()
                newLogs.forEach { event ->
                    when (val sender = event.memberId) {
                        is MessageSenderModel.User -> senderIds.add(sender.userId)
                        else -> {}
                    }

                    when (val action = event.action) {
                        is ChatEventActionModel.MemberJoined -> senderIds.add(action.userId)
                        is ChatEventActionModel.MemberLeft -> senderIds.add(action.userId)
                        is ChatEventActionModel.MemberInvited -> senderIds.add(action.userId)
                        is ChatEventActionModel.MemberPromoted -> senderIds.add(action.userId)
                        is ChatEventActionModel.MemberRestricted -> senderIds.add(action.userId)
                        else -> {}
                    }
                }

                val newSenderInfo = senderIds.mapNotNull { userId ->
                    userRepository.getUser(userId)?.let { user ->
                        userId to ProfileLogsComponent.SenderInfo(
                            name = "${user.firstName} ${user.lastName ?: ""}".trim(),
                            avatarPath = user.avatarPath
                        )
                    }
                }.toMap()

                _state.update {
                    it.copy(
                        logs = if (isFirstLoad) newLogs else it.logs + newLogs,
                        isLoading = false,
                        isLoadingMore = false,
                        canLoadMore = newLogs.size >= PAGE_SIZE,
                        senderInfo = it.senderInfo + newSenderInfo
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, isLoadingMore = false) }
            }
        }
    }

    override fun onBack() {
        onBackClicked()
    }

    override fun onLoadMore() {
        loadLogs(isFirstLoad = false)
    }

    override fun onToggleFilter(filterType: ProfileLogsComponent.FilterType) {
        val current = _state.value.pendingFilters
        val next = when (filterType) {
            ProfileLogsComponent.FilterType.MESSAGE_EDITS -> current.copy(messageEdits = !current.messageEdits)
            ProfileLogsComponent.FilterType.MESSAGE_DELETIONS -> current.copy(messageDeletions = !current.messageDeletions)
            ProfileLogsComponent.FilterType.MESSAGE_PINS -> current.copy(messagePins = !current.messagePins)
            ProfileLogsComponent.FilterType.MEMBER_JOINS -> current.copy(memberJoins = !current.memberJoins)
            ProfileLogsComponent.FilterType.MEMBER_LEAVES -> current.copy(memberLeaves = !current.memberLeaves)
            ProfileLogsComponent.FilterType.MEMBER_INVITES -> current.copy(memberInvites = !current.memberInvites)
            ProfileLogsComponent.FilterType.MEMBER_PROMOTIONS -> current.copy(memberPromotions = !current.memberPromotions)
            ProfileLogsComponent.FilterType.MEMBER_RESTRICTIONS -> current.copy(memberRestrictions = !current.memberRestrictions)
            ProfileLogsComponent.FilterType.INFO_CHANGES -> current.copy(infoChanges = !current.infoChanges)
            ProfileLogsComponent.FilterType.SETTING_CHANGES -> current.copy(settingChanges = !current.settingChanges)
            ProfileLogsComponent.FilterType.INVITE_LINK_CHANGES -> current.copy(inviteLinkChanges = !current.inviteLinkChanges)
            ProfileLogsComponent.FilterType.VIDEO_CHAT_CHANGES -> current.copy(videoChatChanges = !current.videoChatChanges)
            ProfileLogsComponent.FilterType.FORUM_CHANGES -> current.copy(forumChanges = !current.forumChanges)
            ProfileLogsComponent.FilterType.SUBSCRIPTION_EXTENSIONS -> current.copy(subscriptionExtensions = !current.subscriptionExtensions)
        }
        _state.update { it.copy(pendingFilters = next) }
    }

    override fun onToggleUserFilter(userId: Long) {
        val current = _state.value.pendingFilters
        val newUserIds = if (current.userIds.contains(userId)) {
            current.userIds - userId
        } else {
            current.userIds + userId
        }
        _state.update { it.copy(pendingFilters = current.copy(userIds = newUserIds)) }
    }

    override fun onApplyFilters() {
        _state.update { it.copy(filters = it.pendingFilters, isFiltersVisible = false) }
        loadLogs(isFirstLoad = true)
    }

    override fun onResetFilters() {
        _state.update { it.copy(pendingFilters = ChatEventLogFiltersModel()) }
    }

    override fun onDismissFilters() {
        _state.update { it.copy(isFiltersVisible = false) }
    }

    override fun onRefresh() {
        loadLogs(isFirstLoad = true)
    }

    override fun onShowFilters() {
        _state.update { it.copy(isFiltersVisible = true, pendingFilters = it.filters) }
    }

    override fun onPhotoClick(path: String, caption: String) {
        _state.update {
            it.copy(
                fullScreenPhotoPath = path,
                fullScreenPhotoCaption = caption
            )
        }
    }

    override fun onVideoClick(path: String, caption: String, fileId: Int, supportsStreaming: Boolean) {
        _state.update {
            it.copy(
                fullScreenVideoPath = path,
                fullScreenVideoCaption = caption,
                fullScreenVideoFileId = fileId,
                fullScreenVideoSupportsStreaming = supportsStreaming
            )
        }
    }

    override fun onDismissViewer() {
        _state.update {
            it.copy(
                fullScreenPhotoPath = null,
                fullScreenPhotoCaption = null,
                fullScreenVideoPath = null,
                fullScreenVideoCaption = null
            )
        }
    }

    override fun onUserClick(userId: Long) {
        onUserClicked(userId)
    }
}
