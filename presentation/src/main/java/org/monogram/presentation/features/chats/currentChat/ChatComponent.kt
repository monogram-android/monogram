package org.monogram.presentation.features.chats.currentChat

import androidx.compose.runtime.Stable
import androidx.compose.ui.platform.ClipboardManager
import kotlinx.coroutines.flow.StateFlow
import org.monogram.domain.models.*
import org.monogram.domain.repository.InlineBotResultsModel
import org.monogram.domain.repository.MessageRepository
import org.monogram.domain.repository.StickerRepository
import org.monogram.presentation.core.util.AppPreferences
import org.monogram.presentation.core.util.IDownloadUtils
import org.monogram.presentation.features.chats.currentChat.components.VideoPlayerPool
import java.io.File

@Stable
interface ChatComponent {
    val appPreferences: AppPreferences
    val videoPlayerPool: VideoPlayerPool
    val stickerRepository: StickerRepository
    val state: StateFlow<State>
    val repositoryMessage: MessageRepository
    val downloadUtils: IDownloadUtils

    fun onSendMessage(
        text: String,
        entities: List<MessageEntity> = emptyList(),
        sendOptions: MessageSendOptions = MessageSendOptions()
    )

    fun onSendSticker(stickerPath: String)
    fun onSendPhoto(
        photoPath: String,
        caption: String = "",
        captionEntities: List<MessageEntity> = emptyList(),
        sendOptions: MessageSendOptions = MessageSendOptions()
    )

    fun onSendVideo(
        videoPath: String,
        caption: String = "",
        captionEntities: List<MessageEntity> = emptyList(),
        sendOptions: MessageSendOptions = MessageSendOptions()
    )

    fun onSendGif(gif: GifModel)
    fun onSendGifFile(
        path: String,
        caption: String = "",
        captionEntities: List<MessageEntity> = emptyList(),
        sendOptions: MessageSendOptions = MessageSendOptions()
    )

    fun onSendAlbum(
        paths: List<String>,
        caption: String = "",
        captionEntities: List<MessageEntity> = emptyList(),
        sendOptions: MessageSendOptions = MessageSendOptions()
    )

    fun onSendVoice(path: String, duration: Int, waveform: ByteArray)
    fun onRefreshScheduledMessages()
    fun onSendScheduledNow(message: MessageModel)
    fun loadMore()
    fun loadNewer()
    fun onBackClicked()
    fun onProfileClicked()
    fun onMessageClicked(id: Long)
    fun onMessageVisible(messageId: Long)
    fun onReplyMessage(message: MessageModel)
    fun onCancelReply()
    fun onVideoRecorded(file: File)
    fun onForwardMessage(message: MessageModel)
    fun onForwardSelectedMessages()
    fun onDeleteMessage(message: MessageModel, revoke: Boolean = false)
    fun onEditMessage(message: MessageModel)
    fun onCancelEdit()
    fun onSaveEditedMessage(text: String, entities: List<MessageEntity> = emptyList())
    fun onDraftChange(text: String)
    fun onPinMessage(message: MessageModel)
    fun onUnpinMessage(message: MessageModel)
    fun onPinnedMessageClick(message: MessageModel? = null)
    fun onShowAllPinnedMessages()
    fun onDismissPinnedMessages()
    fun onScrollToMessageConsumed()
    fun onScrollToBottom()
    fun onDownloadFile(fileId: Int)
    fun onDownloadHighRes(messageId: Long)
    fun onCancelDownloadFile(fileId: Int)
    fun updateScrollPosition(messageId: Long)
    fun onBottomReached(isAtBottom: Boolean)
    fun onHighlightConsumed()
    fun onTyping()
    fun onSendReaction(messageId: Long, reaction: String)
    suspend fun getMessageReadDate(chatId: Long, messageId: Long, messageDate: Int): Int
    suspend fun getMessageViewers(chatId: Long, messageId: Long): List<MessageViewerModel>
    fun toProfile(id: Long)
    fun onToggleMessageSelection(messageId: Long)
    fun onClearSelection()
    fun onClearMessages()
    fun onDeleteSelectedMessages(revoke: Boolean = false)
    fun onCopySelectedMessages(clipboardManager: ClipboardManager)

    fun onStickerClick(setId: Long)
    fun onDismissStickerSet()
    fun onAddToGifs(path: String)
    fun onPollOptionClick(messageId: Long, optionId: Int)
    fun onRetractVote(messageId: Long)
    fun onShowVoters(messageId: Long, optionId: Int)
    fun onDismissVoters()
    fun onClosePoll(messageId: Long)

    fun onTopicClick(topicId: Int)

    fun onOpenInstantView(url: String)
    fun onDismissInstantView()

    fun onOpenYouTube(url: String)
    fun onDismissYouTube()

    fun onOpenMiniApp(url: String, name: String, botUserId: Long = 0L)
    fun onDismissMiniApp()
    fun onAcceptMiniAppTOS()
    fun onDismissMiniAppTOS()

    fun onOpenWebView(url: String)
    fun onDismissWebView()

    fun onOpenImages(images: List<String>, captions: List<String?>, startIndex: Int, messageId: Long? = null)
    fun onDismissImages()

    fun onOpenVideo(path: String? = null, messageId: Long? = null, caption: String? = null)
    fun onDismissVideo()

    fun onAddToAdBlockWhitelist()
    fun onRemoveFromAdBlockWhitelist()

    fun onToggleMute()
    fun onSearchToggle()
    fun onSearchQueryChange(query: String)
    fun onClearHistory()
    fun onDeleteChat()
    fun onReport()
    fun onReportMessage(message: MessageModel)
    fun onReportReasonSelected(reason: String)
    fun onDismissReportDialog()
    fun onCopyLink(clipboardManager: ClipboardManager)

    fun scrollToMessage(messageId: Long)

    fun onBotCommandClick(command: String)
    fun onShowBotCommands()
    fun onDismissBotCommands()

    fun onCommentsClick(messageId: Long)

    fun onReplyMarkupButtonClick(messageId: Long, button: InlineKeyboardButtonModel, botUserId: Long)
    fun onReplyMarkupButtonClick(messageId: Long, button: KeyboardButtonModel, botUserId: Long)

    fun onLinkClick(url: String)

    fun onOpenInvoice(slug: String? = null, messageId: Long? = null)
    fun onDismissInvoice(status: String)

    fun onMentionQueryChange(query: String?)

    fun onJoinChat()

    fun onBlockUser(userId: Long)
    fun onUnblockUser(userId: Long)
    fun onRestrictUser(userId: Long, permissions: ChatPermissionsModel)
    fun onConfirmRestrict(permissions: ChatPermissionsModel, untilDate: Int)
    fun onDismissRestrictDialog()

    fun onInlineQueryChange(botUsername: String, query: String)
    fun onLoadMoreInlineResults(offset: String)
    fun onSendInlineResult(resultId: String)
    fun onOpenAttachBot(botUserId: Long, fallbackName: String)

    @Stable
    data class State(
        val chatId: Long = 0L,
        val chatTitle: String = "Chat",
        val chatAvatar: String? = null,
        val chatPersonalAvatar: String? = null,
        val chatEmojiStatus: String? = null,
        val isGroup: Boolean = false,
        val isChannel: Boolean = false,
        val isOnline: Boolean = false,
        val isVerified: Boolean = false,
        val isSponsor: Boolean = false,
        val canWrite: Boolean = false,
        val isAdmin: Boolean = false,
        val permissions: ChatPermissionsModel = ChatPermissionsModel(),
        val memberCount: Int = 0,
        val onlineCount: Int = 0,
        val unreadCount: Int = 0,
        val unreadMentionCount: Int = 0,
        val unreadReactionCount: Int = 0,
        val userStatus: String? = null,
        val typingAction: String? = null,
        val messages: List<MessageModel> = emptyList(),
        val isLoading: Boolean = false,
        val isLoadingOlder: Boolean = false,
        val isLoadingNewer: Boolean = false,
        val replyMessage: MessageModel? = null,
        val editingMessage: MessageModel? = null,
        val editRequestTime: Long = 0L,
        val draftText: String = "",
        val pinnedMessage: MessageModel? = null,
        val allPinnedMessages: List<MessageModel> = emptyList(),
        val showPinnedMessagesList: Boolean = false,
        val pinnedMessageCount: Int = 0,
        val pinnedMessageIndex: Int = 0,
        val scrollToMessageId: Long? = null,
        val highlightedMessageId: Long? = null,
        val isAtBottom: Boolean = true,
        val currentScrollMessageId: Long = 0L,
        val lastScrollPosition: Long = 0L,
        val isLatestLoaded: Boolean = true,
        val isOldestLoaded: Boolean = false,
        val fontSize: Float = 16f,
        val bubbleRadius: Float = 18f,
        val wallpaper: String? = null,
        val wallpaperModel: WallpaperModel? = null,
        val isWallpaperBlurred: Boolean = false,
        val wallpaperBlurIntensity: Int = 20,
        val isWallpaperMoving: Boolean = false,
        val wallpaperDimming: Int = 0,
        val isWallpaperGrayscale: Boolean = false,
        val isPlayerGesturesEnabled: Boolean = true,
        val isPlayerDoubleTapSeekEnabled: Boolean = true,
        val playerSeekDuration: Int = 10,
        val isPlayerZoomEnabled: Boolean = true,
        val autoDownloadMobile: Boolean = true,
        val autoDownloadWifi: Boolean = true,
        val autoDownloadRoaming: Boolean = false,
        val autoDownloadFiles: Boolean = false,
        val autoplayGifs: Boolean = true,
        val autoplayVideos: Boolean = true,
        val showLinkPreviews: Boolean = true,
        val isChatAnimationsEnabled: Boolean = true,
        val selectedMessageIds: Set<Long> = emptySet(),
        val selectedStickerSet: StickerSetModel? = null,
        val pollVoters: List<UserModel> = emptyList(),
        val showPollVoters: Boolean = false,
        val isPollVotersLoading: Boolean = false,
        val viewAsTopics: Boolean = false,
        val topics: List<TopicModel> = emptyList(),
        val currentTopicId: Long? = null,
        val rootMessage: MessageModel? = null,
        val isLoadingTopics: Boolean = false,
        val instantViewUrl: String? = null,
        val youtubeUrl: String? = null,
        val miniAppUrl: String? = null,
        val miniAppName: String? = null,
        val miniAppBotUserId: Long = 0L,
        val showMiniAppTOS: Boolean = false,
        val miniAppTOSBotUserId: Long = 0L,
        val miniAppTOSUrl: String? = null,
        val miniAppTOSName: String? = null,
        val webViewUrl: String? = null,
        val fullScreenImages: List<String>? = null,
        val fullScreenCaptions: List<String?> = emptyList(),
        val fullScreenStartIndex: Int = 0,
        val fullScreenVideoMessageId: Long? = null,
        val fullScreenVideoPath: String? = null,
        val fullScreenVideoCaption: String? = null,
        val isWhitelistedInAdBlock: Boolean = false,
        val isMuted: Boolean = false,
        val isSearchActive: Boolean = false,
        val searchQuery: String = "",
        val showReportDialog: Boolean = false,
        val isBot: Boolean = false,
        val botCommands: List<BotCommandModel> = emptyList(),
        val botMenuButton: BotMenuButtonModel = BotMenuButtonModel.Default,
        val showBotCommands: Boolean = false,
        val currentUser: UserModel? = null,
        val otherUser: UserModel? = null,
        val invoiceSlug: String? = null,
        val invoiceMessageId: Long? = null,
        val mentionSuggestions: List<UserModel> = emptyList(),
        val isMember: Boolean = true,
        val restrictUserId: Long? = null,
        val inlineBotResults: InlineBotResultsModel? = null,
        val currentInlineBotId: Long? = null,
        val currentInlineBotUsername: String? = null,
        val currentInlineQuery: String? = null,
        val isInlineBotLoading: Boolean = false,
        val isInstalledFromGooglePlay: Boolean = true,
        val attachMenuBots: List<AttachMenuBotModel> = emptyList(),
        val scheduledMessages: List<MessageModel> = emptyList()
    )
}
