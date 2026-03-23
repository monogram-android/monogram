package org.monogram.presentation.features.chats.currentChat

import android.util.Log
import androidx.compose.ui.platform.ClipboardManager
import com.arkivanov.essenty.lifecycle.doOnResume
import com.arkivanov.essenty.lifecycle.doOnStart
import com.arkivanov.essenty.lifecycle.doOnStop
import com.arkivanov.mvikotlin.extensions.coroutines.labels
import com.arkivanov.mvikotlin.extensions.coroutines.stateFlow
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import org.monogram.core.DispatcherProvider
import org.monogram.domain.managers.DistrManager
import org.monogram.domain.models.*
import org.monogram.domain.repository.*
import org.monogram.presentation.core.util.AppPreferences
import org.monogram.presentation.core.util.IDownloadUtils
import org.monogram.presentation.core.util.componentScope
import org.monogram.presentation.features.chats.currentChat.components.VideoPlayerPool
import org.monogram.presentation.features.chats.currentChat.impl.*
import org.monogram.presentation.root.AppComponentContext
import org.monogram.presentation.settings.storage.CacheController
import java.io.File

class DefaultChatComponent(
    context: AppComponentContext,
    val chatId: Long,
    private val toProfiles: (Long) -> Unit,
    private val onBack: () -> Unit,
    private val onProfileClick: () -> Unit,
    private val onForward: (Long, List<Long>) -> Unit,
    private val onLink: (String) -> Unit,
    private val initialMessageId: Long? = null
) : ChatComponent, AppComponentContext by context {

    internal val settingsRepository: SettingsRepository = container.repositories.settingsRepository
    override val downloadUtils: IDownloadUtils = container.utils.downloadUtils()
    internal val userRepository: UserRepository = container.repositories.userRepository
    override val stickerRepository: StickerRepository = container.repositories.stickerRepository
    internal val privacyRepository: PrivacyRepository = container.repositories.privacyRepository
    internal val botPreferences: BotPreferencesProvider = container.preferences.botPreferencesProvider
    internal val toastMessageDisplayer: MessageDisplayer = container.utils.messageDisplayer()
    internal val chatsListRepository: ChatsListRepository = container.repositories.chatsListRepository
    override val repositoryMessage: MessageRepository = container.repositories.messageRepository
    override val appPreferences: AppPreferences = container.preferences.appPreferences
    internal val cacheProvider: CacheProvider = container.cacheProvider
    override val videoPlayerPool: VideoPlayerPool = container.utils.videoPlayerPool
    internal val cacheController: CacheController = container.utils.cacheController
    internal val distrManager: DistrManager = container.utils.distrManager()
    internal val dispatcherProvider: DispatcherProvider = container.utils.dispatcherProvider

    val scope = componentScope
    val messageMutex = Mutex()
    var messageLoadingJob: Job? = null
    var loadMoreJob: Job? = null
    var loadNewerJob: Job? = null
    var inlineBotJob: Job? = null
    private var autoLoadJob: Job? = null
    private var mentionJob: Job? = null

    internal var lastLoadedOlderId: Long = 0L
    internal var lastLoadedNewerId: Long = 0L

    internal val _state = MutableStateFlow(
        ChatComponent.State(
            chatId = chatId,
            fontSize = appPreferences.fontSize.value,
            bubbleRadius = appPreferences.bubbleRadius.value,
            wallpaper = appPreferences.wallpaper.value,
            isWallpaperBlurred = appPreferences.isWallpaperBlurred.value,
            wallpaperBlurIntensity = appPreferences.wallpaperBlurIntensity.value,
            isWallpaperMoving = appPreferences.isWallpaperMoving.value,
            wallpaperDimming = appPreferences.wallpaperDimming.value,
            isWallpaperGrayscale = appPreferences.isWallpaperGrayscale.value,
            isPlayerGesturesEnabled = appPreferences.isPlayerGesturesEnabled.value,
            isPlayerDoubleTapSeekEnabled = appPreferences.isPlayerDoubleTapSeekEnabled.value,
            playerSeekDuration = appPreferences.playerSeekDuration.value,
            isPlayerZoomEnabled = appPreferences.isPlayerZoomEnabled.value,
            autoDownloadMobile = appPreferences.autoDownloadMobile.value,
            autoDownloadWifi = appPreferences.autoDownloadWifi.value,
            autoDownloadRoaming = appPreferences.autoDownloadRoaming.value,
            autoDownloadFiles = appPreferences.autoDownloadFiles.value,
            autoplayGifs = appPreferences.autoplayGifs.value,
            autoplayVideos = appPreferences.autoplayVideos.value,
            isWhitelistedInAdBlock = appPreferences.adBlockWhitelistedChannels.value.contains(chatId),
            scrollToMessageId = initialMessageId,
            highlightedMessageId = initialMessageId,
            lastScrollPosition = cacheProvider.getChatScrollPosition(chatId),
            isInstalledFromGooglePlay = distrManager.isInstalledFromGooglePlay()
        )
    )

    private val store = ChatStoreFactory(
        storeFactory = DefaultStoreFactory(),
        component = this
    ).create()

    override val state: StateFlow<ChatComponent.State> = store.stateFlow

    private var availableWallpapers: List<WallpaperModel> = emptyList()
    internal var allMembers: List<UserModel> = emptyList()

    init {
        setupLifecycle()
        setupCollectors()
        initialLoad()
    }

    private fun setupLifecycle() {
        lifecycle.doOnStart {
            startAutoLoad()
        }

        lifecycle.doOnStop {
            autoLoadJob?.cancel()
        }

        lifecycle.doOnResume {
            loadChatInfo()
            handleResume(initialMessageId)
        }

        scope.launch {
            try {
                awaitCancellation()
            } finally {
                repositoryMessage.closeChat(chatId)
            }
        }

        store.labels
            .onEach { label ->
                when (label) {
                    ChatStore.Label.Back -> onBack()
                    is ChatStore.Label.Profile -> toProfiles(label.id)
                    is ChatStore.Label.Forward -> onForward(label.chatId, label.messageIds)
                    is ChatStore.Label.Link -> onLink(label.url)
                }
            }
            .launchIn(scope)
    }

    private fun setupCollectors() {
        setupMessageCollectors()
        setupPinnedMessageCollector()
        observeUserUpdates()
        observeCurrentUser()
        observeFileDownloads()
        cacheProvider.attachBots
            .onEach { bots ->
                _state.update {
                    it.copy(
                        attachMenuBots = bots
                    )
                }
            }
            .launchIn(scope)

        appPreferences.adBlockWhitelistedChannels
            .onEach { channels ->
                _state.update { it.copy(isWhitelistedInAdBlock = channels.contains(chatId)) }
            }
            .launchIn(scope)

        loadWallpapers { wallpapers ->
            availableWallpapers = wallpapers
            observePreferences(availableWallpapers)
        }

        _state.onEach {
            store.accept(ChatStore.Intent.UpdateState(it))
        }.launchIn(scope)
    }

    private fun initialLoad() {
        scope.launch {
            repositoryMessage.openChat(chatId)
            withContext(Dispatchers.Main) {
                loadChatInfo()
                loadDraft()
                loadPinnedMessage()
                loadMembers()
            }
        }
    }

    private fun startAutoLoad() {
        autoLoadJob?.cancel()
        autoLoadJob = scope.launch {
            while (isActive) {
                val currentState = _state.value
                if (initialMessageId == null && currentState.messages.size <= 1 && !currentState.isLoading && !currentState.isLoadingOlder) {
                    Log.d("DefaultChatComponent", "Auto-loading messages...")
                    loadMessages()
                }
                delay(5000)
            }
        }
    }

    private fun handleResume(initialMessageId: Long?) {
        val currentState = _state.value
        if (!currentState.viewAsTopics) {
            if (initialMessageId != null) {
                scrollToMessage(initialMessageId)
            } else if (currentState.messages.isEmpty()) {
                loadMessages()
            }
        } else if (currentState.messages.size <= 1 && currentState.currentTopicId == null) {
            loadMessages()
        }
    }

    private fun loadMembers() {
        scope.launch {
            val currentState = _state.value
            if (currentState.isGroup || currentState.isChannel) {
                if (currentState.isChannel && !currentState.isAdmin) return@launch

                try {
                    allMembers = userRepository.getChatMembers(chatId, 0, 200, ChatMembersFilter.Recent)
                        .map { it.user }
                } catch (e: Exception) {
                    Log.e("DefaultChatComponent", "Failed to load members", e)
                }
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

    private fun observeFileDownloads() {
        repositoryMessage.messageDownloadCompletedFlow
            .onEach { (fileId, path) ->
                if (path.isNotEmpty()) {
                    updateMessagesWithFile(fileId.toInt(), path)
                    updateInlineResultsWithFile(fileId.toInt(), path)
                }
            }
            .launchIn(scope)
    }

    private fun updateInlineResultsWithFile(fileId: Int, newPath: String) {
        _state.update { currentState ->
            val currentResults = currentState.inlineBotResults ?: return@update currentState
            val updatedResults = currentResults.results.map { result ->
                if (result.thumbFileId == fileId) result.copy(thumbUrl = newPath) else result
            }
            currentState.copy(inlineBotResults = currentResults.copy(results = updatedResults))
        }
    }

    private fun updateMessagesWithFile(fileId: Int, newPath: String) {
        _state.update { currentState ->
            val updatedMessages = currentState.messages.map { msg ->
                updateMessagePathIfNeeded(msg, fileId, newPath)
            }
            currentState.copy(messages = updatedMessages)
        }
    }

    private fun updateMessagePathIfNeeded(msg: MessageModel, targetFileId: Int, newPath: String): MessageModel {
        return when (val content = msg.content) {
            is MessageContent.Photo -> if (content.fileId == targetFileId) msg.copy(content = content.copy(path = newPath, isDownloading = false, downloadProgress = 1f)) else msg
            is MessageContent.Video -> if (content.fileId == targetFileId) msg.copy(content = content.copy(path = newPath, isDownloading = false, downloadProgress = 1f)) else msg
            is MessageContent.Document -> if (content.fileId == targetFileId) msg.copy(content = content.copy(path = newPath, isDownloading = false, downloadProgress = 1f)) else msg
            is MessageContent.Audio -> if (content.fileId == targetFileId) msg.copy(content = content.copy(path = newPath, isDownloading = false, downloadProgress = 1f)) else msg
            is MessageContent.Sticker -> if (content.fileId == targetFileId) msg.copy(content = content.copy(path = newPath, isDownloading = false, downloadProgress = 1f)) else msg
            is MessageContent.Voice -> if (content.fileId == targetFileId) msg.copy(content = content.copy(path = newPath, isDownloading = false, downloadProgress = 1f)) else msg
            is MessageContent.VideoNote -> if (content.fileId == targetFileId) msg.copy(content = content.copy(path = newPath, isDownloading = false, downloadProgress = 1f)) else msg
            is MessageContent.Gif -> if (content.fileId == targetFileId) msg.copy(content = content.copy(path = newPath, isDownloading = false, downloadProgress = 1f)) else msg
            else -> msg
        }
    }

    override fun onSendMessage(text: String, entities: List<MessageEntity>) =
        store.accept(ChatStore.Intent.SendMessage(text, entities))

    override fun onSendSticker(stickerPath: String) = store.accept(ChatStore.Intent.SendSticker(stickerPath))
    override fun onSendPhoto(photoPath: String, caption: String) =
        store.accept(ChatStore.Intent.SendPhoto(photoPath, caption))

    override fun onSendVideo(videoPath: String, caption: String) =
        store.accept(ChatStore.Intent.SendVideo(videoPath, caption))

    override fun onSendGif(gif: GifModel) = store.accept(ChatStore.Intent.SendGif(gif))
    override fun onSendGifFile(path: String, caption: String) =
        store.accept(ChatStore.Intent.SendGifFile(path, caption))

    override fun onSendAlbum(paths: List<String>, caption: String) =
        store.accept(ChatStore.Intent.SendAlbum(paths, caption))

    override fun onSendVoice(path: String, duration: Int, waveform: ByteArray) =
        store.accept(ChatStore.Intent.SendVoice(path, duration, waveform))

    override fun onVideoRecorded(file: File) = store.accept(ChatStore.Intent.VideoRecorded(file))

    override fun loadMore() = store.accept(ChatStore.Intent.LoadMore)
    override fun loadNewer() = store.accept(ChatStore.Intent.LoadNewer)

    override fun onBackClicked() = store.accept(ChatStore.Intent.BackClicked)

    override fun onProfileClicked() = store.accept(ChatStore.Intent.ProfileClicked)
    override fun onMessageClicked(id: Long) = store.accept(ChatStore.Intent.MessageClicked(id))
    override fun onMessageVisible(messageId: Long) = store.accept(ChatStore.Intent.MessageVisible(messageId))

    override fun onReplyMessage(message: MessageModel) = store.accept(ChatStore.Intent.ReplyMessage(message))

    override fun onCancelReply() = store.accept(ChatStore.Intent.CancelReply)

    override fun onCancelEdit() = store.accept(ChatStore.Intent.CancelEdit)

    override fun onForwardMessage(message: MessageModel) = store.accept(ChatStore.Intent.ForwardMessage(message))

    override fun onForwardSelectedMessages() = store.accept(ChatStore.Intent.ForwardSelectedMessages)

    override fun onDeleteMessage(message: MessageModel, revoke: Boolean) =
        store.accept(ChatStore.Intent.DeleteMessage(message, revoke))

    override fun onDeleteSelectedMessages(revoke: Boolean) =
        store.accept(ChatStore.Intent.DeleteSelectedMessages(revoke))

    override fun onEditMessage(message: MessageModel) = store.accept(ChatStore.Intent.EditMessage(message))

    override fun onSaveEditedMessage(text: String, entities: List<MessageEntity>) =
        store.accept(ChatStore.Intent.SaveEditedMessage(text, entities))

    override fun onDraftChange(text: String) = store.accept(ChatStore.Intent.DraftChange(text))

    override fun onPinMessage(message: MessageModel) = store.accept(ChatStore.Intent.PinMessage(message))

    override fun onUnpinMessage(message: MessageModel) = store.accept(ChatStore.Intent.UnpinMessage(message))

    override fun onPinnedMessageClick(message: MessageModel?) =
        store.accept(ChatStore.Intent.PinnedMessageClick(message))

    override fun onShowAllPinnedMessages() = store.accept(ChatStore.Intent.ShowAllPinnedMessages)

    override fun onDismissPinnedMessages() = store.accept(ChatStore.Intent.DismissPinnedMessages)

    override fun onScrollToMessageConsumed() = store.accept(ChatStore.Intent.ScrollToMessageConsumed)

    override fun onScrollToBottom() = store.accept(ChatStore.Intent.ScrollToBottom)

    override fun onDownloadFile(fileId: Int) = store.accept(ChatStore.Intent.DownloadFile(fileId))

    override fun onDownloadHighRes(messageId: Long) = store.accept(ChatStore.Intent.DownloadHighRes(messageId))

    override fun onCancelDownloadFile(fileId: Int) = store.accept(ChatStore.Intent.CancelDownloadFile(fileId))

    override fun updateScrollPosition(messageId: Long) {
        if (_state.value.currentTopicId == null) {
            cacheProvider.saveChatScrollPosition(chatId, messageId)
        }
        _state.update { it.copy(lastScrollPosition = messageId) }
        store.accept(ChatStore.Intent.UpdateScrollPosition(messageId))
    }

    override fun onBottomReached(isAtBottom: Boolean) = store.accept(ChatStore.Intent.BottomReached(isAtBottom))

    override fun onHighlightConsumed() = store.accept(ChatStore.Intent.HighlightConsumed)

    override fun onTyping() = store.accept(ChatStore.Intent.Typing)

    override fun onSendReaction(messageId: Long, reaction: String) =
        store.accept(ChatStore.Intent.SendReaction(messageId, reaction))

    override suspend fun getMessageReadDate(chatId: Long, messageId: Long, messageDate: Int): Int {
        return repositoryMessage.getMessageReadDate(chatId, messageId, messageDate)
    }

    override suspend fun getMessageViewers(chatId: Long, messageId: Long): List<MessageViewerModel> {
        return repositoryMessage.getMessageViewers(chatId, messageId)
    }

    override fun toProfile(id: Long) = toProfiles(id)
    override fun onToggleMessageSelection(messageId: Long) =
        store.accept(ChatStore.Intent.ToggleMessageSelection(messageId))

    override fun onClearSelection() = store.accept(ChatStore.Intent.ClearSelection)
    override fun onClearMessages() = store.accept(ChatStore.Intent.ClearMessages)

    override fun onCopySelectedMessages(clipboardManager: ClipboardManager) =
        store.accept(ChatStore.Intent.CopySelectedMessages(clipboardManager))

    override fun onStickerClick(setId: Long) = store.accept(ChatStore.Intent.StickerClick(setId))
    override fun onDismissStickerSet() = store.accept(ChatStore.Intent.DismissStickerSet)

    override fun onAddToGifs(path: String) = store.accept(ChatStore.Intent.AddToGifs(path))

    override fun onPollOptionClick(messageId: Long, optionId: Int) =
        store.accept(ChatStore.Intent.PollOptionClick(messageId, optionId))

    override fun onRetractVote(messageId: Long) = store.accept(ChatStore.Intent.RetractVote(messageId))
    override fun onShowVoters(messageId: Long, optionId: Int) =
        store.accept(ChatStore.Intent.ShowVoters(messageId, optionId))

    override fun onDismissVoters() = store.accept(ChatStore.Intent.DismissVoters)
    override fun onTopicClick(topicId: Int) = store.accept(ChatStore.Intent.TopicClick(topicId))

    override fun onOpenInstantView(url: String) = store.accept(ChatStore.Intent.OpenInstantView(url))

    override fun onDismissInstantView() = store.accept(ChatStore.Intent.DismissInstantView)

    override fun onOpenYouTube(url: String) = store.accept(ChatStore.Intent.OpenYouTube(url))

    override fun onDismissYouTube() = store.accept(ChatStore.Intent.DismissYouTube)

    override fun onOpenMiniApp(url: String, name: String, botUserId: Long) =
        store.accept(ChatStore.Intent.OpenMiniApp(url, name, botUserId))

    override fun onDismissMiniApp() = store.accept(ChatStore.Intent.DismissMiniApp)
    override fun onAcceptMiniAppTOS() = store.accept(ChatStore.Intent.AcceptMiniAppTOS)
    override fun onDismissMiniAppTOS() = store.accept(ChatStore.Intent.DismissMiniAppTOS)

    override fun onOpenWebView(url: String) = store.accept(ChatStore.Intent.OpenWebView(url))

    override fun onDismissWebView() = store.accept(ChatStore.Intent.DismissWebView)

    override fun onOpenImages(images: List<String>, captions: List<String?>, startIndex: Int, messageId: Long?) =
        store.accept(ChatStore.Intent.OpenImages(images, captions, startIndex, messageId))

    override fun onDismissImages() = store.accept(ChatStore.Intent.DismissImages)

    override fun onOpenVideo(path: String?, messageId: Long?, caption: String?) =
        store.accept(ChatStore.Intent.OpenVideo(path, messageId, caption))

    override fun onDismissVideo() = store.accept(ChatStore.Intent.DismissVideo)

    override fun onAddToAdBlockWhitelist() = store.accept(ChatStore.Intent.AddToAdBlockWhitelist)

    override fun onRemoveFromAdBlockWhitelist() = store.accept(ChatStore.Intent.RemoveFromAdBlockWhitelist)

    override fun onToggleMute() = store.accept(ChatStore.Intent.ToggleMute)

    override fun onSearchToggle() = store.accept(ChatStore.Intent.SearchToggle)

    override fun onSearchQueryChange(query: String) = store.accept(ChatStore.Intent.SearchQueryChange(query))

    override fun onClearHistory() = store.accept(ChatStore.Intent.ClearHistory)
    override fun onDeleteChat() = store.accept(ChatStore.Intent.DeleteChat)

    override fun onReport() = store.accept(ChatStore.Intent.Report)

    override fun onReportMessage(message: MessageModel) = store.accept(ChatStore.Intent.ReportMessage(message))

    override fun onReportReasonSelected(reason: String) = store.accept(ChatStore.Intent.ReportReasonSelected(reason))

    override fun onDismissReportDialog() = store.accept(ChatStore.Intent.DismissReportDialog)

    override fun onCopyLink(clipboardManager: ClipboardManager) =
        store.accept(ChatStore.Intent.CopyLink(clipboardManager))

    override fun scrollToMessage(messageId: Long) = store.accept(ChatStore.Intent.ScrollToMessage(messageId))
    override fun onBotCommandClick(command: String) = store.accept(ChatStore.Intent.BotCommandClick(command))
    override fun onShowBotCommands() = store.accept(ChatStore.Intent.ShowBotCommands)
    override fun onDismissBotCommands() = store.accept(ChatStore.Intent.DismissBotCommands)

    override fun onCommentsClick(messageId: Long) = store.accept(ChatStore.Intent.CommentsClick(messageId))

    override fun onReplyMarkupButtonClick(messageId: Long, button: InlineKeyboardButtonModel, botUserId: Long) =
        store.accept(ChatStore.Intent.ReplyMarkupButtonClick(messageId, button, botUserId))

    override fun onReplyMarkupButtonClick(messageId: Long, button: KeyboardButtonModel, botUserId: Long) =
        store.accept(ChatStore.Intent.KeyboardButtonClick(messageId, button, botUserId))

    override fun onLinkClick(url: String) = store.accept(ChatStore.Intent.LinkClick(url))

    override fun onOpenInvoice(slug: String?, messageId: Long?) =
        store.accept(ChatStore.Intent.OpenInvoice(slug, messageId))

    override fun onDismissInvoice(status: String) = store.accept(ChatStore.Intent.DismissInvoice(status))

    override fun onMentionQueryChange(query: String?) = store.accept(ChatStore.Intent.MentionQueryChange(query))

    override fun onJoinChat() = store.accept(ChatStore.Intent.JoinChat)

    override fun onBlockUser(userId: Long) = store.accept(ChatStore.Intent.BlockUser(userId))

    override fun onUnblockUser(userId: Long) = store.accept(ChatStore.Intent.UnblockUser(userId))

    override fun onRestrictUser(userId: Long, permissions: ChatPermissionsModel) =
        store.accept(ChatStore.Intent.RestrictUser(userId, permissions))

    override fun onDismissRestrictDialog() = store.accept(ChatStore.Intent.DismissRestrictDialog)

    override fun onConfirmRestrict(permissions: ChatPermissionsModel, untilDate: Int) =
        store.accept(ChatStore.Intent.ConfirmRestrict(permissions, untilDate))

    override fun onInlineQueryChange(botUsername: String, query: String) =
        store.accept(ChatStore.Intent.InlineQueryChange(botUsername, query))

    override fun onLoadMoreInlineResults(offset: String) = store.accept(ChatStore.Intent.LoadMoreInlineResults(offset))
    override fun onSendInlineResult(resultId: String) = store.accept(ChatStore.Intent.SendInlineResult(resultId))
    override fun onOpenAttachBot(botUserId: Long, fallbackName: String) {
        scope.launch {
            val botInfo = userRepository.getBotInfo(botUserId)
            val menuButton = botInfo?.menuButton
            if (menuButton is BotMenuButtonModel.WebApp) {
                onOpenMiniApp(
                    menuButton.url,
                    menuButton.text.ifBlank { fallbackName },
                    botUserId
                )
            }
        }
    }

    override fun onClosePoll(messageId: Long) = store.accept(ChatStore.Intent.ClosePoll(messageId))
}
