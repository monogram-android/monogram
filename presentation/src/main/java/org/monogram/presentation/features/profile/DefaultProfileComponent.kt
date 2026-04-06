package org.monogram.presentation.features.profile

import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.arkivanov.decompose.value.update
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.monogram.domain.models.*
import org.monogram.domain.repository.*
import org.monogram.presentation.core.util.IDownloadUtils
import org.monogram.presentation.core.util.coRunCatching
import org.monogram.presentation.core.util.componentScope
import org.monogram.presentation.root.AppComponentContext

class DefaultProfileComponent(
    context: AppComponentContext,
    private val chatId: Long,
    private val onBackClicked: () -> Unit,
    private val onMessageClicked: (MessageModel) -> Unit = {},
    private val onMessageLongClicked: (MessageModel) -> Unit = {},
    private val onAvatarClicked: (String) -> Unit = {},
    private val onEditClicked: () -> Unit = {},
    private val onSendMessageClicked: (Long) -> Unit = {},
    private val onShowLogsClicked: (Long) -> Unit = {},
    private val onMemberClicked: (Long) -> Unit = {},
    private val onMemberLongClicked: (Long, Long) -> Unit = { _, _ -> }
) : ProfileComponent, AppComponentContext by context {

    private val chatListRepository: ChatListRepository = container.repositories.chatListRepository
    private val chatOperationsRepository: ChatOperationsRepository = container.repositories.chatOperationsRepository
    private val chatSettingsRepository: ChatSettingsRepository = container.repositories.chatSettingsRepository
    private val userRepository: UserRepository = container.repositories.userRepository
    private val profilePhotoRepository: ProfilePhotoRepository = container.repositories.profilePhotoRepository
    private val chatInfoRepository: ChatInfoRepository = container.repositories.chatInfoRepository
    private val botRepository: BotRepository = container.repositories.botRepository
    private val chatStatisticsRepository: ChatStatisticsRepository = container.repositories.chatStatisticsRepository
    private val privacyRepository: PrivacyRepository = container.repositories.privacyRepository
    override val messageRepository: MessageRepository = container.repositories.messageRepository
    private val locationRepository: LocationRepository = container.repositories.locationRepository
    private val botPreferences: BotPreferencesProvider = container.preferences.botPreferencesProvider
    override val downloadUtils: IDownloadUtils = container.utils.downloadUtils()

    private val scope = componentScope
    private val _state = MutableValue(ProfileComponent.State(chatId = chatId))
    override val state: Value<ProfileComponent.State> = _state

    private var lastLoadedMessageId: Long = 0L
    private val INITIAL_MEDIA_PAGE_SIZE = 21
    private val MEDIA_PAGE_SIZE = 50
    private val PROFILE_PHOTOS_LIMIT = 50
    private var membersOffset = 0
    private var isCurrentlyLoadingMedia = false
    private val canLoadMoreMediaByFilter = mutableMapOf<ProfileMediaFilter, Boolean>()

    init {
        loadData()
        loadMediaNextPage(isFirstLoad = true)
        observeProfilePhotos()
        observeUserUpdates()
        observeCurrentUser()
    }

    private fun loadData() {
        scope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                val chat = coRunCatching { chatListRepository.getChatById(chatId) }.getOrNull()
                val user = if (chat == null || (!chat.isGroup && !chat.isChannel)) {
                    userRepository.getUser(chatId)
                } else null
                val isBlocked = if (user != null) {
                    privacyRepository.getBlockedUsers().contains(user.id)
                } else {
                    chat?.blockList == true
                }
                val fullInfo = coRunCatching { chatInfoRepository.getChatFullInfo(chatId) }.getOrNull()
                val description = fullInfo?.description
                val link = fullInfo?.inviteLink
                    ?: chat?.username?.let { "https://t.me/$it" }
                    ?: user?.username?.let { "https://t.me/$it" }

                var botWebAppUrl: String? = null
                var botWebAppName: String? = null
                var isTOSAccepted = false

                if (user?.type == UserTypeEnum.BOT) {
                    val botInfo = botRepository.getBotInfo(chatId)
                    val menuButton = botInfo?.menuButton
                    if (menuButton is BotMenuButtonModel.WebApp) {
                        botWebAppUrl = menuButton.url
                        botWebAppName = menuButton.text
                    }
                    isTOSAccepted = botPreferences.getWebappPermission(user.id, "tos_accepted")
                }

                val linkedChatId = fullInfo?.linkedChatId?.takeIf { it != 0L }
                val linkedChat = linkedChatId?.let {
                    coRunCatching { chatListRepository.getChatById(it) }.getOrNull()
                }

                _state.update {
                    it.copy(
                        chat = chat,
                        user = user,
                        isBlocked = isBlocked,
                        fullInfo = fullInfo,
                        about = description,
                        publicLink = link,
                        botWebAppUrl = botWebAppUrl,
                        botWebAppName = botWebAppName,
                        qrContent = link ?: "",
                        personalAvatarPath = user?.personalAvatarPath,
                        linkedChat = linkedChat,
                        isTOSAccepted = isTOSAccepted
                    )
                }
            } finally {
                _state.update { it.copy(isLoading = false) }
            }
        }
    }

    private fun observeCurrentUser() {
        userRepository.currentUserFlow
            .onEach { user ->
                _state.update { it.copy(currentUser = user) }
            }
            .launchIn(scope)
    }

    override fun onLoadMoreMedia() {
        val isGroup = _state.value.chat?.let { it.isGroup || it.isChannel } ?: false
        if (isGroup && _state.value.selectedTabIndex == 1) {
            loadMembersNextPage()
        } else {
            loadMediaNextPage(isFirstLoad = false)
        }
    }

    override fun onDownloadMedia(message: MessageModel) {
        scope.launch {

            val highResId = if (message.content is MessageContent.Photo) {
                messageRepository.getHighResFileId(chatId, message.id)
            } else null
            val fileId = if (highResId != null && highResId != 0) {
                highResId
            } else {
                when (val content = message.content) {
                    is MessageContent.Photo -> content.fileId
                    is MessageContent.Video -> content.fileId
                    else -> 0
                }
            }

            if (fileId != 0) {
                messageRepository.downloadFile(fileId, priority = 16)
                val downloadedPath = awaitDownloadedPath(fileId) ?: return@launch
                withContext(Dispatchers.Main) {
                    onFileDownloaded(fileId, downloadedPath)

                    if (message.content is MessageContent.Photo) {
                        val currentImages = _state.value.fullScreenImages
                        if (currentImages != null && !_state.value.isViewingProfilePhotos) {
                            val allPhotos = _state.value.mediaMessages.filter { it.content is MessageContent.Photo }
                            val index = allPhotos.indexOfFirst { it.id == message.id }

                            if (index != -1 && index < currentImages.size) {
                                val newImages = currentImages.toMutableList()
                                newImages[index] = downloadedPath
                                _state.update { it.copy(fullScreenImages = newImages) }
                            }
                        }
                    }
                }
            }
        }
    }

    fun onFileDownloaded(fileId: Int, newPath: String) {
        if (fileId == 0) return

        val currentState = _state.value

        val updatedMedia = currentState.mediaMessages.map { msg ->
            updateMessagePathIfNeeded(msg, fileId, newPath)
        }

        val updatedFiles = currentState.fileMessages.map { msg ->
            updateMessagePathIfNeeded(msg, fileId, newPath)
        }

        if (updatedMedia != currentState.mediaMessages || updatedFiles != currentState.fileMessages) {
            _state.update {
                var nextState = it.copy(
                    mediaMessages = updatedMedia,
                    fileMessages = updatedFiles
                )

                if (it.fullScreenImages != null && !it.isViewingProfilePhotos) {
                    val allPhotos = updatedMedia.filter { it.content is MessageContent.Photo }
                    val paths = allPhotos.mapNotNull { (it.content as? MessageContent.Photo)?.displayPath() }

                    nextState = nextState.copy(
                        fullScreenImages = paths
                    )
                }
                nextState
            }
        }
    }

    private fun updateMessagePathIfNeeded(msg: MessageModel, targetFileId: Int, newPath: String): MessageModel {
        val content = msg.content
        val shouldUpdate = when (content) {
            is MessageContent.Photo -> content.fileId == targetFileId
            is MessageContent.Video -> content.fileId == targetFileId
            is MessageContent.Document -> content.fileId == targetFileId
            else -> false
        }

        return if (shouldUpdate) {
            val newContent = when (content) {
                is MessageContent.Photo -> content.copy(path = newPath)
                is MessageContent.Video -> content.copy(path = newPath)
                is MessageContent.Document -> content.copy(path = newPath)
                else -> content
            }
            msg.copy(content = newContent)
        } else {
            msg
        }
    }

    private fun MessageContent.Photo.displayPath(): String? = path ?: thumbnailPath

    private fun loadMembersNextPage() {
        if (_state.value.isLoadingMembers || !_state.value.canLoadMoreMembers) return

        scope.launch {
            _state.update { it.copy(isLoadingMembers = true) }
            try {
                val limit = 20
                val newMembers =
                    chatInfoRepository.getChatMembers(chatId, membersOffset, limit, ChatMembersFilter.Recent)

                if (newMembers.isEmpty()) {
                    _state.update { it.copy(canLoadMoreMembers = false) }
                } else {
                    membersOffset += newMembers.size
                    _state.update {
                        it.copy(
                            members = it.members + newMembers,
                            canLoadMoreMembers = newMembers.size >= limit
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _state.update { it.copy(isLoadingMembers = false) }
            }
        }
    }

    private fun loadMediaNextPage(isFirstLoad: Boolean) {
        if (isCurrentlyLoadingMedia) return
        val state = _state.value
        val isGroup = state.chat?.let { it.isGroup || it.isChannel } ?: false
        val tabIndex = state.selectedTabIndex
        if (isGroup && tabIndex == 1) return

        val mediaTypeIndex = if (isGroup && tabIndex > 1) tabIndex - 1 else tabIndex
        val filter = when (mediaTypeIndex) {
            0 -> ProfileMediaFilter.MEDIA
            1 -> ProfileMediaFilter.FILES
            2 -> ProfileMediaFilter.AUDIO
            3 -> ProfileMediaFilter.VOICE
            4 -> ProfileMediaFilter.LINKS
            5 -> ProfileMediaFilter.GIFS
            else -> return
        }
        val canLoadMoreForFilter = canLoadMoreMediaByFilter[filter] ?: true
        if (!isFirstLoad && !canLoadMoreForFilter) return

        val lastId = if (isFirstLoad) 0L else getLastMessageIdForFilter(state, filter)

        scope.launch {
            isCurrentlyLoadingMedia = true
            _state.update {
                it.copy(
                    isLoadingMedia = isFirstLoad,
                    isLoadingMoreMedia = !isFirstLoad
                )
            }

            try {
                val pageLimit = if (isFirstLoad) INITIAL_MEDIA_PAGE_SIZE else MEDIA_PAGE_SIZE
                val messages = messageRepository.getProfileMedia(
                    chatId = chatId,
                    filter = filter,
                    fromMessageId = lastId,
                    limit = pageLimit
                )

                if (messages.isEmpty()) {
                    canLoadMoreMediaByFilter[filter] = false
                    _state.update { it.copy(canLoadMoreMedia = false) }
                } else {
                    _state.update { appendMessagesToState(it, filter, messages, pageLimit) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isCurrentlyLoadingMedia = false
                _state.update {
                    it.copy(
                        isLoadingMedia = false,
                        isLoadingMoreMedia = false
                    )
                }
            }
        }
    }

    private fun getLastMessageIdForFilter(state: ProfileComponent.State, filter: ProfileMediaFilter): Long {
        val list = when (filter) {
            ProfileMediaFilter.MEDIA -> state.mediaMessages
            ProfileMediaFilter.FILES -> state.fileMessages
            ProfileMediaFilter.AUDIO -> state.audioMessages
            ProfileMediaFilter.VOICE -> state.voiceMessages
            ProfileMediaFilter.LINKS -> state.linkMessages
            ProfileMediaFilter.GIFS -> state.gifMessages
        }
        return list.lastOrNull()?.id ?: 0L
    }

    private fun appendMessagesToState(
        currentState: ProfileComponent.State,
        filter: ProfileMediaFilter,
        newMessages: List<MessageModel>,
        pageLimit: Int
    ): ProfileComponent.State {
        val canLoadMore = newMessages.size >= pageLimit
        canLoadMoreMediaByFilter[filter] = canLoadMore

        val nextState = when (filter) {
            ProfileMediaFilter.MEDIA -> currentState.copy(
                mediaMessages = currentState.mediaMessages + newMessages,
                canLoadMoreMedia = canLoadMore
            )
            ProfileMediaFilter.FILES -> currentState.copy(
                fileMessages = currentState.fileMessages + newMessages,
                canLoadMoreMedia = canLoadMore
            )
            ProfileMediaFilter.AUDIO -> currentState.copy(
                audioMessages = currentState.audioMessages + newMessages,
                canLoadMoreMedia = canLoadMore
            )
            ProfileMediaFilter.VOICE -> currentState.copy(
                voiceMessages = currentState.voiceMessages + newMessages,
                canLoadMoreMedia = canLoadMore
            )
            ProfileMediaFilter.LINKS -> currentState.copy(
                linkMessages = currentState.linkMessages + newMessages,
                canLoadMoreMedia = canLoadMore
            )
            ProfileMediaFilter.GIFS -> currentState.copy(
                gifMessages = currentState.gifMessages + newMessages,
                canLoadMoreMedia = canLoadMore
            )
        }

        if (filter == ProfileMediaFilter.MEDIA && nextState.fullScreenImages != null && !nextState.isViewingProfilePhotos) {
            val allPhotos = nextState.mediaMessages.filter { it.content is MessageContent.Photo }
            val paths = allPhotos.mapNotNull { (it.content as? MessageContent.Photo)?.path }
            val captions = allPhotos.map { (it.content as? MessageContent.Photo)?.caption }
            return nextState.copy(
                fullScreenImages = paths,
                fullScreenCaptions = captions
            )
        }

        return nextState
    }

    override fun onTabSelected(index: Int) {
        if (_state.value.selectedTabIndex == index) return

        val currentState = _state.value
        val isGroup = currentState.chat?.let { it.isGroup || it.isChannel } ?: false
        val selectedFilter = tabFilter(index, isGroup)
        val tabCanLoadMore = selectedFilter?.let { canLoadMoreMediaByFilter[it] ?: true } ?: true
        val isTabEmpty = when (selectedFilter) {
            ProfileMediaFilter.MEDIA -> currentState.mediaMessages.isEmpty()
            ProfileMediaFilter.FILES -> currentState.fileMessages.isEmpty()
            ProfileMediaFilter.AUDIO -> currentState.audioMessages.isEmpty()
            ProfileMediaFilter.VOICE -> currentState.voiceMessages.isEmpty()
            ProfileMediaFilter.LINKS -> currentState.linkMessages.isEmpty()
            ProfileMediaFilter.GIFS -> currentState.gifMessages.isEmpty()
            null -> false
        }

        _state.update {
            it.copy(
                selectedTabIndex = index,
                canLoadMoreMedia = tabCanLoadMore,
                isLoadingMedia = isTabEmpty,
                isLoadingMoreMedia = false
            )
        }

        if (isGroup && index == 1) {
            if (_state.value.members.isEmpty()) {
                loadMembersNextPage()
            } else {
                 _state.update { it.copy(isLoadingMedia = false) }
            }
        } else {
            if (isTabEmpty) {
                loadMediaNextPage(isFirstLoad = true)
            } else {
                _state.update { it.copy(isLoadingMedia = false) }
            }
        }
    }

    private fun tabFilter(index: Int, isGroup: Boolean): ProfileMediaFilter? {
        if (isGroup && index == 1) return null
        val mediaTypeIndex = if (isGroup && index > 1) index - 1 else index
        return when (mediaTypeIndex) {
            0 -> ProfileMediaFilter.MEDIA
            1 -> ProfileMediaFilter.FILES
            2 -> ProfileMediaFilter.AUDIO
            3 -> ProfileMediaFilter.VOICE
            4 -> ProfileMediaFilter.LINKS
            5 -> ProfileMediaFilter.GIFS
            else -> null
        }
    }

    private fun observeUserUpdates() {
        userRepository.getUserFlow(chatId)
            .onEach { user ->
                if (user != null) {
                    _state.update { it.copy(user = user, personalAvatarPath = user.personalAvatarPath) }
                }
            }
            .launchIn(scope)
    }

    private fun observeProfilePhotos() {
        val profilePhotosFlow = if (isGroupOrChannelProfile()) {
            profilePhotoRepository.getChatProfilePhotosFlow(chatId)
        } else {
            profilePhotoRepository.getUserProfilePhotosFlow(chatId)
        }

        profilePhotosFlow
            .onEach { photos ->
                if (photos.isNotEmpty()) {
                    _state.update { it.copy(profilePhotos = photos) }
                }
            }
            .launchIn(scope)
    }

    override fun onBack() {
        onBackClicked()
    }

    override fun onMessageClick(message: MessageModel) {
        when (val content = message.content) {
            is MessageContent.Photo -> {
                scope.launch {
                    val bigFileId = messageRepository.getHighResFileId(chatId, message.id)
                    if (bigFileId != null && bigFileId != 0) {
                        messageRepository.downloadFile(bigFileId, priority = 32)
                        val downloadedPath = awaitDownloadedPath(bigFileId) ?: return@launch
                        withContext(Dispatchers.Main) {
                            onFileDownloaded(bigFileId, downloadedPath)
                            val currentImages = _state.value.fullScreenImages
                            if (currentImages != null) {
                                val viewerItems = _state.value.mediaMessages
                                    .asSequence()
                                    .filter { it.content is MessageContent.Photo }
                                    .mapNotNull {
                                        val photo =
                                            it.content as? MessageContent.Photo ?: return@mapNotNull null
                                        photo.displayPath()?.let { path -> it.id to path }
                                    }
                                    .toList()
                                val index = viewerItems.indexOfFirst { it.first == message.id }

                                if (index != -1 && index < currentImages.size) {
                                    val newImages = currentImages.toMutableList()
                                    newImages[index] = downloadedPath
                                    _state.update { it.copy(fullScreenImages = newImages) }
                                }
                            }
                        }
                    }
                }

                val viewerItems = _state.value.mediaMessages
                    .asSequence()
                    .filter { it.content is MessageContent.Photo }
                    .mapNotNull {
                        val photo = it.content as? MessageContent.Photo ?: return@mapNotNull null
                        val displayPath = photo.displayPath() ?: return@mapNotNull null
                        Triple(it.id, displayPath, photo.caption)
                    }
                    .toList()

                if (viewerItems.isNotEmpty()) {
                    val startIndex = viewerItems.indexOfFirst { it.first == message.id }
                        .takeIf { it != -1 } ?: 0

                    _state.update {
                        it.copy(
                            fullScreenImages = viewerItems.map { item -> item.second },
                            fullScreenCaptions = viewerItems.map { item -> item.third },
                            fullScreenStartIndex = startIndex,
                            isViewingProfilePhotos = false
                        )
                    }
                }
            }
            is MessageContent.Video -> {
                content.path?.let { path ->
                    _state.update {
                        it.copy(
                            fullScreenVideoPath = path,
                            fullScreenVideoCaption = content.caption
                        )
                    }
                } ?: run {
                    if (content.fileId != 0) {
                        scope.launch {
                            messageRepository.downloadFile(content.fileId, priority = 32)
                            val downloadedPath = awaitDownloadedPath(content.fileId) ?: return@launch
                            withContext(Dispatchers.Main) {
                                _state.update {
                                    it.copy(
                                        fullScreenVideoPath = downloadedPath,
                                        fullScreenVideoCaption = content.caption
                                    )
                                }
                                onFileDownloaded(content.fileId, downloadedPath)
                            }
                        }
                    }
                }
            }
            is MessageContent.Gif -> {
                content.path?.let { path ->
                    _state.update {
                        it.copy(
                            fullScreenVideoPath = path,
                            fullScreenVideoCaption = content.caption
                        )
                    }
                }
            }
            is MessageContent.VideoNote -> {
                content.path?.let { path ->
                    _state.update {
                        it.copy(
                            fullScreenVideoPath = path,
                            fullScreenVideoCaption = null
                        )
                    }
                }
            }
            is MessageContent.Location -> {
                onLocationClick(content.latitude, content.longitude, "Location")
            }

            is MessageContent.Venue -> {
                onLocationClick(content.latitude, content.longitude, content.title)
            }
            else -> onMessageClicked(message)
        }
    }

    override fun onMessageLongClick(message: MessageModel) {
        onMessageLongClicked(message)
    }

    override fun onAvatarClick() {
        val snapshot = _state.value
        val initialPhotos = snapshot.profilePhotos

        if (initialPhotos.isNotEmpty()) {
            openProfilePhotos(initialPhotos)
        } else {
            val avatarPath = snapshot.personalAvatarPath
                ?: snapshot.chat?.avatarPath
                ?: snapshot.user?.avatarPath
            avatarPath?.let { openProfilePhotos(listOf(it)) }
        }

        val isGroupOrChannel = isGroupOrChannelProfile(snapshot)

        scope.launch {
            _state.update { it.copy(isProfilePhotoHdLoading = true) }
            try {
                val refreshedPhotos = if (isGroupOrChannel) {
                    coRunCatching {
                        profilePhotoRepository.getChatProfilePhotos(
                            chatId = snapshot.chatId,
                            offset = 0,
                            limit = PROFILE_PHOTOS_LIMIT,
                            ensureFullRes = true
                        )
                    }.getOrDefault(emptyList())
                } else {
                    val userId = snapshot.user?.id?.takeIf { it > 0 } ?: snapshot.chatId.takeIf { it > 0 }
                    if (userId == null) return@launch
                    coRunCatching {
                        profilePhotoRepository.getUserProfilePhotos(
                            userId = userId,
                            offset = 0,
                            limit = PROFILE_PHOTOS_LIMIT,
                            ensureFullRes = true
                        )
                    }.getOrDefault(emptyList())
                }

                if (refreshedPhotos.isEmpty()) return@launch

                _state.update { current ->
                    val next = current.copy(profilePhotos = refreshedPhotos)
                    val viewerIsOpen = current.fullScreenImages != null || current.fullScreenVideoPath != null
                    if (!viewerIsOpen) {
                        next
                    } else {
                        applyProfilePhotosToViewer(next, refreshedPhotos)
                    }
                }
            } finally {
                _state.update { it.copy(isProfilePhotoHdLoading = false) }
            }
        }
    }

    private fun openProfilePhotos(photos: List<String>) {
        if (photos.isEmpty()) return
        _state.update { current ->
            applyProfilePhotosToViewer(current, photos)
        }
    }

    private fun applyProfilePhotosToViewer(
        state: ProfileComponent.State,
        photos: List<String>
    ): ProfileComponent.State {
        val firstPhoto = photos.firstOrNull() ?: return state
        if (firstPhoto.endsWith(".mp4", ignoreCase = true)) {
            return state.copy(
                fullScreenVideoPath = firstPhoto,
                fullScreenVideoCaption = null,
                fullScreenImages = null,
                fullScreenCaptions = emptyList(),
                fullScreenStartIndex = 0,
                isViewingProfilePhotos = true
            )
        }

        val images = photos.filter { !it.endsWith(".mp4", ignoreCase = true) }
        if (images.isEmpty()) return state

        val safeIndex = state.fullScreenStartIndex.coerceIn(0, images.lastIndex)
        return state.copy(
            fullScreenImages = images,
            fullScreenCaptions = images.map { null },
            fullScreenStartIndex = safeIndex,
            fullScreenVideoPath = null,
            fullScreenVideoCaption = null,
            isViewingProfilePhotos = true
        )
    }

    override fun onDismissViewer() {
        _state.update {
            it.copy(
                fullScreenImages = null,
                fullScreenCaptions = emptyList(),
                fullScreenVideoPath = null,
                fullScreenVideoCaption = null,
                isViewingProfilePhotos = false,
                isProfilePhotoHdLoading = false
            )
        }
    }

    override fun onOpenMiniApp(url: String, name: String, chatId: Long) {
        _state.update { it.copy(miniAppUrl = url, miniAppName = name, chatId = chatId) }
    }

    override fun onDismissMiniApp() {
        _state.update { it.copy(miniAppUrl = null, miniAppName = null) }
    }

    override fun onToggleMute() {
        val chat = _state.value.chat ?: return
        val shouldMute = !chat.isMuted

        scope.launch {
            chatOperationsRepository.toggleMuteChats(setOf(chatId), shouldMute)
            updateChat(chatId)
        }
    }

    override fun onEdit() {
        onEditClicked()
    }

    override fun onShowQRCode() {
        _state.update { it.copy(isQrVisible = true) }
    }

    override fun onDismissQRCode() {
        _state.update { it.copy(isQrVisible = false) }
    }

    override fun onSendMessage() {
        onSendMessageClicked(chatId)
    }

    override fun onToggleBlockUser() {
        val userId = _state.value.user?.id ?: return
        val shouldBlock = !_state.value.isBlocked
        scope.launch {
            if (shouldBlock) {
                privacyRepository.blockUser(userId)
            } else {
                privacyRepository.unblockUser(userId)
            }
            _state.update { it.copy(isBlocked = shouldBlock) }
            updateChat(chatId)
        }
    }

    override fun onDeleteChat() {
        scope.launch {
            chatOperationsRepository.deleteChats(setOf(chatId))
        }
    }

    private suspend fun awaitDownloadedPath(fileId: Int, timeoutMs: Long = 20_000L): String? {
        if (fileId == 0) return null

        val fileInfo = messageRepository.getFileInfo(fileId)
        if (fileInfo?.local?.isDownloadingCompleted == true && fileInfo.local.path.isNotEmpty()) {
            return fileInfo.local.path
        }

        val completed = withTimeoutOrNull(timeoutMs) {
            messageRepository.messageDownloadCompletedFlow.first { (_, completedFileId, path) ->
                completedFileId == fileId && path.isNotEmpty()
            }
        }
        if (completed != null) {
            return completed.third
        }

        val fallback = messageRepository.getFileInfo(fileId)
        return fallback?.local?.path?.takeIf {
            fallback.local.isDownloadingCompleted && it.isNotEmpty()
        }
    }

    override fun onEditContact(firstName: String, lastName: String) {
        val user = _state.value.user ?: return
        val trimmedFirstName = firstName.trim()
        if (trimmedFirstName.isBlank()) return
        val normalizedLastName = lastName.trim().ifBlank { null }

        scope.launch {
            runCatching {
                userRepository.addContact(
                    user.copy(
                        firstName = trimmedFirstName,
                        lastName = normalizedLastName,
                        isContact = true
                    )
                )
            }.onSuccess {
                _state.update { current ->
                    current.copy(
                        user = current.user?.copy(
                            firstName = trimmedFirstName,
                            lastName = normalizedLastName
                        )
                    )
                }
            }
        }
    }

    private fun isGroupOrChannelProfile(snapshot: ProfileComponent.State = _state.value): Boolean {
        val chat = snapshot.chat
        if (chat != null) return chat.isGroup || chat.isChannel
        return snapshot.chatId < 0
    }

    override fun onToggleContact() {
        val user = _state.value.user ?: return
        scope.launch {
            if (user.isContact) {
                userRepository.removeContact(user.id)
            } else {
                userRepository.addContact(user)
            }
            _state.update { current ->
                current.copy(user = current.user?.copy(isContact = !user.isContact))
            }
        }
    }

    override fun onLeave() {
        scope.launch {
            chatOperationsRepository.leaveChat(chatId)
            updateChat(chatId)
        }
    }

    override fun onJoinChat() {
        scope.launch {
            messageRepository.joinChat(chatId)
            updateChat(chatId)
        }
    }

    override fun onReport(reason: String) {
        scope.launch(Dispatchers.IO) {
            chatOperationsRepository.reportChat(chatId, reason)
            withContext(Dispatchers.Main) {
                _state.update { it.copy(isReportVisible = false) }
            }
        }
    }

    override fun onDismissReport() {
        _state.update { it.copy(isReportVisible = false) }
    }

    override fun onShowReport() {
        _state.update { it.copy(isReportVisible = true) }
    }

    override fun onShowLogs() {
        onShowLogsClicked(chatId)
    }

    override fun onMemberClick(userId: Long) {
        scope.launch {
            val chat = chatListRepository.getChatById(userId)
            if (chat != null && (chat.isGroup || chat.isChannel)) {
                onMemberClicked(userId)
            } else {
                onSendMessageClicked(userId)
            }
        }
    }

    override fun onMemberLongClick(userId: Long) {
        scope.launch {
            val member = chatInfoRepository.getChatMember(chatId, userId)
            if (member?.status is ChatMemberStatus.Administrator || member?.status is ChatMemberStatus.Creator) {
                onMemberLongClicked(chatId, userId)
            }
        }
    }

    override fun onUpdateChatTitle(title: String) {
        scope.launch {
            chatSettingsRepository.setChatTitle(chatId, title)
            updateChat(chatId)
        }
    }

    override fun onUpdateChatDescription(description: String) {
        scope.launch {
            chatSettingsRepository.setChatDescription(chatId, description)
            loadData()
        }
    }

    override fun onUpdateChatUsername(username: String) {
        scope.launch {
            chatSettingsRepository.setChatUsername(chatId, username)
            loadData()
        }
    }

    override fun onUpdateChatPermissions(permissions: ChatPermissionsModel) {
        scope.launch {
            chatSettingsRepository.setChatPermissions(chatId, permissions)
            updateChat(chatId)
        }
    }

    override fun onUpdateChatSlowModeDelay(delay: Int) {
        scope.launch {
            chatSettingsRepository.setChatSlowModeDelay(chatId, delay)
            loadData()
        }
    }

    override fun onUpdateMemberStatus(userId: Long, status: ChatMemberStatus) {
        scope.launch {
            chatInfoRepository.setChatMemberStatus(chatId, userId, status)
            membersOffset = 0
            _state.update { it.copy(members = emptyList(), canLoadMoreMembers = true) }
            loadMembersNextPage()
        }
    }

    override fun onShowStatistics() {
        scope.launch {
            val stats = chatStatisticsRepository.getChatStatistics(chatId, false)
            if (stats != null) {
                val enrichedStats = enrichInteractionPreviews(stats)
                _state.update { it.copy(statistics = enrichedStats, isStatisticsVisible = true) }
            } else {
                loadData()
            }
        }
    }

    override fun onShowRevenueStatistics() {
        scope.launch {
            val stats = chatStatisticsRepository.getChatRevenueStatistics(chatId, false)
            if (stats != null) {
                _state.update {
                    it.copy(revenueStatistics = stats, isRevenueStatisticsVisible = true)
                }
            } else {
                loadData()
            }
        }
    }

    override fun onDismissStatistics() {
        _state.update {
            it.copy(
                isStatisticsVisible = false,
                isRevenueStatisticsVisible = false,
                statistics = null,
                revenueStatistics = null
            )
        }
    }

    override fun onLoadStatisticsGraph(token: String) {
        scope.launch {
            val graph = chatStatisticsRepository.loadStatisticsGraph(chatId, token, 0L)

            if (graph != null) {
                _state.update { state ->
                    val updatedStats = state.statistics?.let { updateStatisticsWithGraph(it, token, graph) }
                    val updatedRevenueStats = state.revenueStatistics?.let {
                        updateRevenueStatisticsWithGraph(it, token, graph)
                    }
                    state.copy(
                        statistics = updatedStats ?: state.statistics,
                        revenueStatistics = updatedRevenueStats ?: state.revenueStatistics
                    )
                }
            }
        }
    }

    override fun onLinkedChatClick() {
        _state.value.linkedChat?.id?.let { onSendMessageClicked(it) }
    }

    override fun onShowPermissions() {
        val botId = _state.value.user?.id ?: return
        val permissions = mapOf(
            "Location" to botPreferences.getWebappPermission(botId, "location"),
            "Biometry" to botPreferences.getWebappPermission(botId, "biometry"),
            "Terms of Service" to botPreferences.getWebappPermission(botId, "tos_accepted")
        )
        _state.update { it.copy(isPermissionsVisible = true, botPermissions = permissions) }
    }

    override fun onDismissPermissions() {
        _state.update { it.copy(isPermissionsVisible = false) }
    }

    override fun onTogglePermission(permission: String) {
        val botId = _state.value.user?.id ?: return
        val key = when (permission) {
            "Location" -> "location"
            "Biometry" -> "biometry"
            "Terms of Service" -> "tos_accepted"
            else -> return
        }
        val current = botPreferences.getWebappPermission(botId, key)
        botPreferences.setWebappPermission(botId, key, !current)

        if (key == "tos_accepted") {
            _state.update { it.copy(isTOSAccepted = !current) }
        }

        onShowPermissions()
    }

    override fun onAcceptTOS() {
        val botId = _state.value.user?.id ?: return
        scope.launch {
            _state.update { it.copy(isAcceptingTOS = true) }
            delay(1000) // Animation delay
            botPreferences.setWebappPermission(botId, "tos_accepted", true)
            _state.update {
                it.copy(
                    isTOSVisible = false,
                    isTOSAccepted = true,
                    isAcceptingTOS = false
                )
            }
        }
    }

    override fun onDismissTOS() {
        _state.update {
            it.copy(
                isTOSVisible = false,
                pendingMiniAppUrl = null,
                pendingMiniAppName = null
            )
        }
    }

    override fun onLocationClick(lat: Double, lon: Double, address: String) {
        scope.launch {
            var finalAddress = address
            if (address == "Location") {
                val reverse = locationRepository.reverseGeocode(lat, lon)
                if (reverse != null) {
                    finalAddress = reverse.address?.city ?: reverse.address?.toString() ?: "Location"
                }
            }
            _state.update {
                it.copy(
                    selectedLocation = ProfileComponent.LocationData(lat, lon, finalAddress)
                )
            }
        }
    }

    override fun onDismissLocation() {
        _state.update { it.copy(selectedLocation = null) }
    }

    private suspend fun enrichInteractionPreviews(stats: ChatStatisticsModel): ChatStatisticsModel {
        if (stats.recentInteractions.isEmpty()) return stats
        val enriched = stats.recentInteractions.map { interaction ->
            if (interaction.type != ChatInteractionType.MESSAGE || interaction.objectId == 0L) {
                interaction
            } else {
                val preview = coRunCatching {
                    messageRepository
                        .getMessagesAround(chatId = chatId, messageId = interaction.objectId, limit = 1)
                        .firstOrNull { it.id == interaction.objectId }
                        ?.content
                        ?.toStatisticsPreview()
                }.getOrNull()
                interaction.copy(previewText = preview)
            }
        }
        return stats.copy(recentInteractions = enriched)
    }

    private fun MessageContent.toStatisticsPreview(): String {
        return when (this) {
            is MessageContent.Text -> text.ifBlank { "Message" }
            is MessageContent.Photo -> caption.ifBlank { "Photo" }
            is MessageContent.Video -> caption.ifBlank { "Video" }
            is MessageContent.Gif -> caption.ifBlank { "GIF" }
            is MessageContent.Document -> caption.ifBlank { fileName.ifBlank { "Document" } }
            is MessageContent.Audio -> caption.ifBlank { title.ifBlank { "Audio" } }
            is MessageContent.Voice -> "Voice message"
            is MessageContent.VideoNote -> "Video message"
            is MessageContent.Sticker -> "Sticker ${emoji.ifBlank { "" }}".trim()
            is MessageContent.Contact -> "Contact: ${firstName} ${lastName}".trim()
            is MessageContent.Location -> "Location"
            is MessageContent.Venue -> "Venue: $title"
            is MessageContent.Poll -> "Poll: $question"
            is MessageContent.Service -> text.ifBlank { "Service message" }
            MessageContent.Unsupported -> "Unsupported message"
        }
    }

    private fun updateStatisticsWithGraph(
        stats: ChatStatisticsModel,
        token: String,
        newGraph: StatisticsGraphModel
    ): ChatStatisticsModel {
        fun StatisticsGraphModel?.matchesToken(token: String): Boolean {
            return when (this) {
                is StatisticsGraphModel.Async -> this.token == token
                is StatisticsGraphModel.Data -> this.zoomToken == token
                else -> false
            }
        }

        return stats.copy(
            memberCountGraph = if (stats.memberCountGraph.matchesToken(token)) newGraph else stats.memberCountGraph,
            joinGraph = if (stats.joinGraph.matchesToken(token)) newGraph else stats.joinGraph,
            muteGraph = if (stats.muteGraph.matchesToken(token)) newGraph else stats.muteGraph,
            viewCountByHourGraph = if (stats.viewCountByHourGraph.matchesToken(token)) newGraph else stats.viewCountByHourGraph,
            viewCountBySourceGraph = if (stats.viewCountBySourceGraph.matchesToken(token)) newGraph else stats.viewCountBySourceGraph,
            joinBySourceGraph = if (stats.joinBySourceGraph.matchesToken(token)) newGraph else stats.joinBySourceGraph,
            languageGraph = if (stats.languageGraph.matchesToken(token)) newGraph else stats.languageGraph,
            messageContentGraph = if (stats.messageContentGraph.matchesToken(token)) newGraph else stats.messageContentGraph,
            actionGraph = if (stats.actionGraph.matchesToken(token)) newGraph else stats.actionGraph,
            dayGraph = if (stats.dayGraph.matchesToken(token)) newGraph else stats.dayGraph,
            weekGraph = if (stats.weekGraph.matchesToken(token)) newGraph else stats.weekGraph,
            topHoursGraph = if (stats.topHoursGraph.matchesToken(token)) newGraph else stats.topHoursGraph,
            messageReactionGraph = if (stats.messageReactionGraph.matchesToken(token)) newGraph else stats.messageReactionGraph,
            storyInteractionGraph = if (stats.storyInteractionGraph.matchesToken(token)) newGraph else stats.storyInteractionGraph,
            storyReactionGraph = if (stats.storyReactionGraph.matchesToken(token)) newGraph else stats.storyReactionGraph
        )
    }

    private fun updateRevenueStatisticsWithGraph(
        stats: ChatRevenueStatisticsModel,
        token: String,
        newGraph: StatisticsGraphModel
    ): ChatRevenueStatisticsModel {
        fun StatisticsGraphModel?.matchesToken(token: String): Boolean {
            return when (this) {
                is StatisticsGraphModel.Async -> this.token == token
                is StatisticsGraphModel.Data -> this.zoomToken == token
                else -> false
            }
        }

        return stats.copy(
            revenueByHourGraph = if (stats.revenueByHourGraph.matchesToken(token)) newGraph else stats.revenueByHourGraph,
            revenueGraph = if (stats.revenueGraph.matchesToken(token)) newGraph else stats.revenueGraph
        )
    }

    private fun updateChat(chatId: Long) {
        scope.launch {
            val updatedChat = chatListRepository.getChatById(chatId)
            withContext(Dispatchers.Main) {
                _state.update {
                    it.copy(
                        chat = updatedChat,
                        isBlocked = updatedChat?.blockList ?: it.isBlocked
                    )
                }
            }
        }
    }
}
