package org.monogram.presentation.features.chats.conversation

import android.util.Log
import androidx.compose.ui.platform.Clipboard
import com.arkivanov.essenty.lifecycle.doOnResume
import com.arkivanov.essenty.lifecycle.doOnStart
import com.arkivanov.essenty.lifecycle.doOnStop
import org.monogram.domain.repository.ConversationPipelineMode
import org.monogram.domain.repository.MediaAutoDownloadPolicy
import com.arkivanov.mvikotlin.extensions.coroutines.labels
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.monogram.core.DispatcherProvider
import org.monogram.core.perf.ChatOpenPerfBridge
import org.monogram.domain.managers.DistrManager
import org.monogram.domain.models.BotMenuButtonModel
import org.monogram.domain.models.ChatPermissionsModel
import org.monogram.domain.models.ChatViewportCacheEntry
import org.monogram.domain.models.ForwardInfo
import org.monogram.domain.models.ForwardOriginType
import org.monogram.domain.models.GifModel
import org.monogram.domain.models.InlineKeyboardButtonModel
import org.monogram.domain.models.KeyboardButtonModel
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageEntity
import org.monogram.domain.models.MessageModel
import org.monogram.domain.models.MessageSendOptions
import org.monogram.domain.models.MessageViewerModel
import org.monogram.domain.models.TdLibLimits
import org.monogram.domain.models.PollDraft
import org.monogram.domain.models.UserModel
import org.monogram.domain.models.WallpaperModel
import org.monogram.domain.repository.BotPreferencesProvider
import org.monogram.domain.repository.BotRepository
import org.monogram.domain.repository.CacheProvider
import org.monogram.domain.repository.ChatInfoRepository
import org.monogram.domain.repository.ChatListRepository
import org.monogram.domain.repository.ChatMembersFilter
import org.monogram.domain.repository.ChatOperationsRepository
import org.monogram.domain.repository.ChecklistDraft
import org.monogram.domain.repository.ForumTopicsRepository
import org.monogram.domain.repository.GifRepository
import org.monogram.domain.repository.InlineBotRepository
import org.monogram.domain.repository.MessageDisplayer
import org.monogram.domain.repository.MessageRepository
import org.monogram.domain.repository.MtProtoTextMessageRepository
import org.monogram.domain.repository.MtProtoReadHistoryRepository
import org.monogram.domain.repository.MtProtoMessageDeletionRepository
import org.monogram.domain.repository.PaymentRepository
import org.monogram.domain.repository.PinnedMessageVisibilityRepository
import org.monogram.domain.repository.PrivacyRepository
import org.monogram.domain.repository.RichTextParseMode
import org.monogram.domain.repository.StickerRepository
import org.monogram.domain.repository.TelegramBackendMode
import org.monogram.domain.repository.TelegramBackendModeRepository
import org.monogram.domain.repository.MessageHistorySnapshotRepository
import org.monogram.domain.repository.TelegramLinkRepository
import org.monogram.domain.repository.UserRepository
import org.monogram.domain.repository.WallpaperRepository
import org.monogram.presentation.core.ui.ScreenSwipeBackState
import org.monogram.presentation.core.util.AppPreferences
import org.monogram.presentation.core.util.IDownloadUtils
import org.monogram.presentation.core.util.componentScope
import org.monogram.presentation.features.chats.conversation.logic.PendingAttachmentSendRegistry
import org.monogram.presentation.features.chats.conversation.logic.activeThreadChatId
import org.monogram.presentation.features.chats.conversation.logic.buildChatInitialLoadKey
import org.monogram.presentation.features.chats.conversation.logic.effectiveThreadId
import org.monogram.presentation.features.chats.conversation.logic.historyConversationKey
import org.monogram.presentation.features.chats.conversation.logic.handleSendPendingAttachments
import org.monogram.presentation.features.chats.conversation.logic.loadChatInfo
import org.monogram.presentation.features.chats.conversation.logic.loadDraft
import org.monogram.presentation.features.chats.conversation.logic.loadMessages
import org.monogram.presentation.features.chats.conversation.logic.loadPinnedMessage
import org.monogram.presentation.features.chats.conversation.logic.loadScheduledMessages
import org.monogram.presentation.features.chats.conversation.logic.loadWallpapers
import org.monogram.presentation.features.chats.conversation.logic.observePreferences
import org.monogram.presentation.features.chats.conversation.logic.observeSponsoredMessagePolicy
import org.monogram.presentation.features.chats.conversation.logic.observeUserUpdates
import org.monogram.presentation.features.chats.conversation.logic.perfTargetName
import org.monogram.presentation.features.chats.conversation.logic.pushViewportReturnTarget
import org.monogram.presentation.features.chats.conversation.logic.refreshDraftLinkPreviewOnPhotoDownloadIfNeeded
import org.monogram.presentation.features.chats.conversation.logic.refreshSponsoredMessageAfterMediaDownload
import org.monogram.presentation.features.chats.conversation.logic.resolveInitialChatScrollTarget
import org.monogram.presentation.features.chats.conversation.logic.setupMessageCollectors
import org.monogram.presentation.features.chats.conversation.logic.setupPinnedMessageCollector
import org.monogram.presentation.features.chats.conversation.logic.shouldStartInitialLoad
import org.monogram.presentation.features.chats.conversation.logic.withUnreadSessionFromChat
import org.monogram.presentation.features.share.IncomingShareRequest
import org.monogram.presentation.features.share.PendingAttachment
import org.monogram.presentation.root.AppComponentContext
import org.monogram.presentation.settings.storage.CacheController
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

private const val VIEWPORT_PERSIST_DEBOUNCE_MS = 750L
private const val VIEWPORT_OFFSET_DELTA_THRESHOLD_PX = 8
private const val MAX_RETURN_TO_MESSAGE_IDS = 16
private const val RICH_PREFETCH_PARALLELISM = 3

internal fun shouldRepairUnexpectedChatReset(
    previousState: ChatComponent.State?,
    nextState: ChatComponent.State
): Boolean {
    return previousState != null &&
            previousState.messages.isNotEmpty() &&
            previousState.currentTopicId == null &&
            previousState.currentMessageThreadId == null &&
            previousState.rootMessage == null &&
            nextState.viewportPhase == ChatViewportPhase.Initializing &&
            nextState.isLoading &&
            nextState.messages.isEmpty() &&
            nextState.currentTopicId == null &&
            nextState.currentMessageThreadId == null &&
            nextState.rootMessage == null &&
            nextState.isAtBottom
}

internal fun repairUnexpectedChatReset(
    previousState: ChatComponent.State,
    resetState: ChatComponent.State
): ChatComponent.State {
    return previousState.copy(
        isLoading = resetState.isLoading,
        isOldestLoaded = resetState.isOldestLoaded,
        isLatestLoaded = resetState.isLatestLoaded,
        pendingScrollCommand = resetState.pendingScrollCommand,
        lastSavedViewport = resetState.lastSavedViewport
    )
}

internal data class RichMessageKey(val chatId: Long, val messageId: Long)

internal fun mergeRichContent(
    current: MessageContent.RichMessage,
    incoming: MessageContent.RichMessage
): MessageContent.RichMessage {
    if (current.isFull && !incoming.isFull && incoming.blocks.isEmpty()) return current
    if (incoming.isFull) return incoming
    if (incoming.blocks.isNotEmpty()) return incoming
    return current
}

internal fun mergeRichMessageIntoModel(
    current: MessageModel,
    incoming: MessageContent.RichMessage
): MessageModel {
    val currentContent = current.content as? MessageContent.RichMessage ?: return current
    val merged = mergeRichContent(currentContent, incoming)
    return if (merged == currentContent) current else current.copy(content = merged)
}

internal fun DefaultChatComponent.applyResolvedRichMessage(
    messageId: Long,
    richMessage: MessageContent.RichMessage
) {
    scope.launch {
        messageMutex.withLock {
            val targetMessageId = remappedMessageIds[messageId] ?: messageId
            _state.update { currentState ->
                val currentMessages = currentState.messages.toMutableList()
                val index = currentMessages.indexOfFirst { it.id == targetMessageId }
                if (index == -1) return@update currentState
                val currentMessage = currentMessages[index]
                val updatedMessage = mergeRichMessageIntoModel(currentMessage, richMessage)
                if (updatedMessage == currentMessage) {
                    currentState
                } else {
                    currentMessages[index] = updatedMessage
                    currentState.copy(messages = currentMessages)
                }
            }
        }
    }
}

internal data class RichViewportPrefetchDecision(
    val fetchNow: List<RichMessageKey>,
    val visibleCandidates: Set<RichMessageKey>,
    val nearbyCandidates: Set<RichMessageKey>
)

internal fun decideRichViewportPrefetch(
    visibleMessages: List<MessageModel>,
    nearbyMessages: List<MessageModel>,
    resolvedMessageIds: Set<RichMessageKey>,
    inFlightMessageIds: Set<RichMessageKey>,
    maxNearbyFetches: Int = RICH_PREFETCH_PARALLELISM
): RichViewportPrefetchDecision {
    fun MessageModel.partialRichKeyOrNull(): RichMessageKey? {
        val content = content as? MessageContent.RichMessage ?: return null
        if (content.isFull) return null
        return RichMessageKey(chatId = chatId, messageId = id)
    }

    val visibleCandidates =
        visibleMessages.mapNotNullTo(linkedSetOf(), MessageModel::partialRichKeyOrNull)
    val nearbyCandidates =
        nearbyMessages.mapNotNullTo(linkedSetOf(), MessageModel::partialRichKeyOrNull)
    val fetchVisible =
        visibleCandidates.filterNot { it in resolvedMessageIds || it in inFlightMessageIds }
    val fetchNearby = nearbyCandidates
        .filterNot { it in visibleCandidates || it in resolvedMessageIds || it in inFlightMessageIds }
        .take(maxNearbyFetches.coerceAtLeast(0))
    return RichViewportPrefetchDecision(
        fetchNow = fetchVisible + fetchNearby,
        visibleCandidates = visibleCandidates,
        nearbyCandidates = nearbyCandidates
    )
}

internal class RichMessageCoordinator(
    private val component: DefaultChatComponent
) {
    private val inFlightMessageIds = ConcurrentHashMap.newKeySet<RichMessageKey>()
    private val resolvedMessageIds = ConcurrentHashMap.newKeySet<RichMessageKey>()

    fun onViewportChanged(visibleMessageIds: Set<Long>, nearbyMessageIds: Set<Long>) {
        if (component._state.value.viewportPhase != ChatViewportPhase.Settled) return
        val messages = component._state.value.messages
        if (messages.isEmpty()) return
        val visibleMessages = messages.filter { it.id in visibleMessageIds }
        val nearbyMessages = messages.filter { it.id in nearbyMessageIds }
        val decision = decideRichViewportPrefetch(
            visibleMessages = visibleMessages,
            nearbyMessages = nearbyMessages,
            resolvedMessageIds = resolvedMessageIds,
            inFlightMessageIds = inFlightMessageIds
        )
        decision.fetchNow.take(RICH_PREFETCH_PARALLELISM).forEach(::request)
    }

    fun onMessageEdited(message: MessageModel) {
        val content = message.content as? MessageContent.RichMessage ?: return
        val key = RichMessageKey(chatId = message.chatId, messageId = message.id)
        if (content.isFull) {
            resolvedMessageIds += key
            component.scope.launch {
                component.applyResolvedRichMessage(message.id, content)
            }
            return
        }
        resolvedMessageIds.remove(key)
        if (component._state.value.viewportPhase == ChatViewportPhase.Settled) {
            request(key)
        }
    }

    private fun request(key: RichMessageKey) {
        if (component.backendModeRepository.backendMode.value == TelegramBackendMode.KOTLIN_MTPROTO) return
        if (!inFlightMessageIds.add(key)) return
        component.scope.launch(component.dispatcherProvider.io) {
            ChatConversationLog.logViewport(
                chatId = key.chatId,
                threadId = component._state.value.currentTopicId,
                event = "rich_fetch_requested",
                componentInstanceId = component.componentInstanceId,
                extra = "messageId=${key.messageId}"
            )
            runCatching {
                component.repositoryMessage.getFullRichMessage(key.chatId, key.messageId)
            }.onSuccess { richMessage ->
                if (richMessage != null) {
                    resolvedMessageIds += key
                    component.applyResolvedRichMessage(key.messageId, richMessage)
                }
            }.also {
                inFlightMessageIds.remove(key)
            }
        }
    }
}

class DefaultChatComponent(
    context: AppComponentContext,
    val chatId: Long,
    private val toProfiles: (Long) -> Unit,
    private val navigateToChatMessage: (Long, Long?) -> Unit,
    private val onBack: () -> Unit,
    private val onProfileClick: () -> Unit,
    private val onForward: (Long, List<Long>) -> Unit,
    private val onLink: (String) -> Unit,
    private val initialMessageId: Long? = null,
    private val initialTopicId: Long? = null,
    private val initialShare: IncomingShareRequest? = null,
    private val onInitialShareConsumed: (Long) -> Unit = {},
    private val onShareToStoryRequested: (String, String?, String?) -> Unit = { _, _, _ -> }
) : ChatComponent, AppComponentContext by context {

    internal val componentInstanceId: String = ChatConversationLog.nextComponentInstanceId(chatId)

    internal val wallpaperRepository: WallpaperRepository = container.repositories.wallpaperRepository
    override val downloadUtils: IDownloadUtils = container.utils.downloadUtils()
    internal val userRepository: UserRepository by lazy { container.repositories.userRepository }
    internal val chatInfoRepository: ChatInfoRepository by lazy { container.repositories.chatInfoRepository }
    internal val botRepository: BotRepository = container.repositories.botRepository
    override val stickerRepository: StickerRepository = container.repositories.stickerRepository
    internal val gifRepository: GifRepository = container.repositories.gifRepository
    internal val privacyRepository: PrivacyRepository = container.repositories.privacyRepository
    internal val telegramLinkRepository: TelegramLinkRepository =
        container.repositories.telegramLinkRepository
    internal val botPreferences: BotPreferencesProvider = container.preferences.botPreferencesProvider
    internal val toastMessageDisplayer: MessageDisplayer = container.utils.messageDisplayer()
    internal val chatListRepository: ChatListRepository = container.repositories.chatListRepository
    internal val chatOperationsRepository: ChatOperationsRepository by lazy { container.repositories.chatOperationsRepository }
    internal val forumTopicsRepository: ForumTopicsRepository by lazy { container.repositories.forumTopicsRepository }
    internal val backendModeRepository: TelegramBackendModeRepository = container.repositories.telegramBackendModeRepository
    internal val messageHistorySnapshotRepository: MessageHistorySnapshotRepository =
        container.repositories.messageHistorySnapshotRepository
    internal val userProfileSnapshotRepository = container.repositories.userProfileSnapshotRepository
    override val repositoryMessage: MessageRepository by lazy { container.repositories.messageRepository }
    internal val mtProtoTextMessageRepository: MtProtoTextMessageRepository by lazy {
        container.repositories.mtProtoTextMessageRepository
    }
    internal val mtProtoReadHistoryRepository: MtProtoReadHistoryRepository by lazy {
        container.repositories.mtProtoReadHistoryRepository
    }
    internal val mtProtoMessageDeletionRepository: MtProtoMessageDeletionRepository by lazy {
        container.repositories.mtProtoMessageDeletionRepository
    }
    internal val pinnedMessageVisibilityRepository: PinnedMessageVisibilityRepository =
        container.repositories.pinnedMessageVisibilityRepository
    internal val inlineBotRepository: InlineBotRepository = container.repositories.inlineBotRepository
    internal val paymentRepository: PaymentRepository = container.repositories.paymentRepository
    internal val tdLibLimitsRepository by lazy { container.repositories.tdLibLimitsRepository }
    override val appPreferences: AppPreferences = container.preferences.appPreferences
    internal val conversationPipelineMode: ConversationPipelineMode =
        ConversationPipelineFallbackGate.modeFor(appPreferences.conversationPipelineMode.value)
    internal val cacheProvider: CacheProvider = container.cacheProvider
    internal val cacheController: CacheController = container.utils.cacheController
    internal val distrManager: DistrManager = container.utils.distrManager()
    internal val dispatcherProvider: DispatcherProvider = container.utils.dispatcherProvider

    val scope = componentScope
    val messageMutex = Mutex()
    var messageLoadingJob: Job? = null
    var loadMoreJob: Job? = null
    var loadNewerJob: Job? = null

    /** Monotonic guard for async history jobs; cancellation alone does not guard late commits. */
    internal var loadingGeneration: Long = 0L
    var inlineBotJob: Job? = null
    var draftSaveJob: Job? = null
    var draftLinkPreviewJob: Job? = null
    var draftLinkPreviewDebounceJob: Job? = null
    private var mentionJob: Job? = null
    private var viewportPersistenceJob: Job? = null
    private var pendingViewportToPersist: ChatViewportCacheEntry? = null
    private var pendingViewportThreadId: Long? = null
    private var lastPersistedViewport: ChatViewportCacheEntry? = null
    private var lastPersistedViewportThreadId: Long? = null
    internal var searchJob: Job? = null
    internal val reactionUpdateSuppressedUntil = ConcurrentHashMap<Long, Long>()
    internal val remappedMessageIds = ConcurrentHashMap<Long, Long>()
    internal val pendingSenderRefreshes = ConcurrentHashMap.newKeySet<Long>()
    internal val senderRefreshRequestedAtMs = ConcurrentHashMap<Long, Long>()
    internal val pendingAttachmentSendRegistry = PendingAttachmentSendRegistry()
    internal var chatInfoObserversStarted: Boolean = false
    internal var sponsoredMessageLoadingJob: Job? = null
    internal var unreadBackfillJob: Job? = null
    internal var lastStartedLoadKey: ChatInitialLoadKey? = null
    internal var lastMtProtoTypingAtMillis: Long = 0L
    internal var hasStartedInitialLoadForContext: Boolean = false
    internal var activeLoadSession: ConversationLoadSession? = null
    internal val conversationSession = ConversationSession(
        scope = scope,
        historyLoader = { request -> repositoryMessage.getHistoryPage(request) }
    )

    internal fun isLoadingGenerationCurrent(generation: Long): Boolean =
        loadingGeneration == generation
    internal val richMessageCoordinator = RichMessageCoordinator(this)

    internal var lastLoadedOlderId: Long = 0L
    internal var lastLoadedNewerId: Long = 0L
    internal var inFlightOlderAnchorId: Long = 0L
    internal var inFlightNewerAnchorId: Long = 0L

    internal val _state = MutableStateFlow(
        ChatComponent.State(
            chatId = chatId,
            fontSize = appPreferences.fontSize.value,
            letterSpacing = appPreferences.letterSpacing.value,
            bubbleRadius = appPreferences.bubbleRadius.value,
            stickerSize = appPreferences.stickerSize.value,
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
            showLinkPreviews = appPreferences.showLinkPreviews.value,
            fixLinkPreviews = appPreferences.fixLinkPreviews.value,
            isWhitelistedInAdBlock = appPreferences.adBlockWhitelistedChannels.value.contains(chatId),
            scrollToMessageId = initialMessageId,
            currentTopicId = initialTopicId,
            tdLibLimits = if (backendModeRepository.backendMode.value == org.monogram.domain.repository.TelegramBackendMode.KOTLIN_MTPROTO) {
                TdLibLimits()
            } else {
                tdLibLimitsRepository.limits.value
            },
            initialShare = initialShare,
            lastScrollPosition = cacheProvider.getChatScrollPosition(chatId),
            lastSavedViewport = cacheProvider.getChatViewport(chatId, null),
            isInstalledFromGooglePlay = distrManager.isInstalledFromGooglePlay(),
            lastReadInboxMessageId = 0L,
            unreadSeparatorLastReadInboxMessageId = 0L
        )
    )

    private val store = ChatStoreFactory(
        storeFactory = DefaultStoreFactory(),
        component = this
    ).create()

    override val state: StateFlow<ChatComponent.State> = _state
    private val _swipeBackState = MutableStateFlow(resolveChatSwipeBackState(_state.value))
    override val swipeBackState: StateFlow<ScreenSwipeBackState> = _swipeBackState

    private var availableWallpapers: List<WallpaperModel> = emptyList()
    internal var allMembers: List<UserModel> = emptyList()

    init {
        ChatConversationLog.logViewport(
            chatId = chatId,
            threadId = initialTopicId,
            event = "component_init",
            componentInstanceId = componentInstanceId
        )
        setupLifecycle()
        setupCollectors()
        initialLoad()
    }

    private fun setupLifecycle() {
        lifecycle.doOnStart {
            ChatConversationLog.logViewportState(
                event = "lifecycle_start",
                state = _state.value,
                componentInstanceId = componentInstanceId
            )
            requestInitialLoad(source = "lifecycle_start")
        }

        lifecycle.doOnStop {
            ChatConversationLog.logViewportState(
                event = "lifecycle_stop",
                state = _state.value,
                componentInstanceId = componentInstanceId
            )
            unreadBackfillJob?.cancel()
            flushViewportPersistence()
        }

        lifecycle.doOnResume {
            ChatConversationLog.logViewportState(
                event = "lifecycle_resume",
                state = _state.value,
                componentInstanceId = componentInstanceId
            )
            loadChatInfo()
            handleResume()
        }

        scope.launch {
            try {
                awaitCancellation()
            } finally {
                ChatConversationLog.logViewportState(
                    event = "component_dispose",
                    state = _state.value,
                    componentInstanceId = componentInstanceId
                )
                conversationSession.close(loadingGeneration + 1L)
                if (backendModeRepository.backendMode.value != TelegramBackendMode.KOTLIN_MTPROTO) {
                    repositoryMessage.closeChat(chatId, ownerTag = componentInstanceId)
                }
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
        if (backendModeRepository.backendMode.value != org.monogram.domain.repository.TelegramBackendMode.KOTLIN_MTPROTO) {
            tdLibLimitsRepository.limits
                .onEach { limits -> _state.update { it.copy(tdLibLimits = limits) } }
                .launchIn(scope)
        }
        setupMessageCollectors()
        setupPinnedMessageCollector()
        observeUserUpdates()
        observeCurrentUser()
        observeSponsoredMessagePolicy()
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
            .drop(1)
            .onEach { channels ->
                _state.update { it.copy(isWhitelistedInAdBlock = channels.contains(chatId)) }
            }
            .launchIn(scope)

        pinnedMessageVisibilityRepository.observeHidden(chatId)
            .onEach { hidden ->
                _state.update { it.copy(isPinnedMessageHidden = hidden) }
            }
            .launchIn(scope)

        loadWallpapers { wallpapers ->
            availableWallpapers = wallpapers
            observePreferences(availableWallpapers)
        }

        _state.onEach {
            _swipeBackState.value = resolveChatSwipeBackState(it)
        }.launchIn(scope)

        var repairingUnexpectedReset = false
        var previousTracedState: ChatComponent.State? = null
        _state
            .distinctUntilChanged { old, new ->
                old.viewportPhase == new.viewportPhase &&
                        old.pendingScrollCommand == new.pendingScrollCommand &&
                        old.isLoading == new.isLoading &&
                        old.isLoadingOlder == new.isLoadingOlder &&
                        old.isLoadingNewer == new.isLoadingNewer &&
                        old.messages.size == new.messages.size &&
                        old.topics.size == new.topics.size &&
                        old.isAtBottom == new.isAtBottom &&
                        old.isLatestLoaded == new.isLatestLoaded &&
                        old.isOldestLoaded == new.isOldestLoaded &&
                        old.currentTopicId == new.currentTopicId &&
                        old.currentMessageThreadId == new.currentMessageThreadId
            }
            .onEach { state ->
                val previousState = previousTracedState
                if (!repairingUnexpectedReset && shouldRepairUnexpectedChatReset(
                        previousState,
                        state
                    )
                ) {
                    val nonNullPreviousState = requireNotNull(previousState)
                    ChatConversationLog.logViewportState(
                        event = "unexpected_reset_repair",
                        state = state,
                        componentInstanceId = componentInstanceId,
                        extra = "restoringMessages=${nonNullPreviousState.messages.size} restoringViewport=${nonNullPreviousState.viewportPhase}"
                    )
                    repairingUnexpectedReset = true
                    _state.update { repairUnexpectedChatReset(nonNullPreviousState, state) }
                    repairingUnexpectedReset = false
                    return@onEach
                }
                ChatConversationLog.logViewportState(
                    event = "state",
                    state = state,
                    componentInstanceId = componentInstanceId
                )
                previousTracedState = state
            }
            .launchIn(scope)

        if (backendModeRepository.backendMode.value != TelegramBackendMode.KOTLIN_MTPROTO) {
            repositoryMessage.fileDownloadFlow
                .filterIsInstance<org.monogram.domain.models.FileDownloadEvent.Completed>()
                .onEach { event ->
                    refreshDraftLinkPreviewOnPhotoDownloadIfNeeded(event.fileId)
                }
                .launchIn(scope)

            repositoryMessage.messageDownloadFlow
                .filterIsInstance<org.monogram.domain.models.MessageDownloadEvent.Completed>()
                .onEach { event ->
                    if (event.chatId != chatId) return@onEach
                    refreshSponsoredMessageAfterMediaDownload(
                        messageId = event.messageId,
                        fileId = event.fileId,
                        path = event.path
                    )
                }
                .launchIn(scope)
        }
    }

    private fun initialLoad() {
        scope.launch {
            ChatConversationLog.logViewport(
                chatId = chatId,
                threadId = _state.value.currentMessageThreadId ?: _state.value.currentTopicId,
                event = "initial_load_start",
                componentInstanceId = componentInstanceId
            )
            chatListRepository.getChatById(chatId)?.let { chat ->
                if (chat.unreadCount > 0) {
                    _state.update {
                        it.withUnreadSessionFromChat(
                            chatUnreadCount = chat.unreadCount,
                            chatLastReadInboxMessageId = chat.lastReadInboxMessageId
                        )
                    }
                }
            }
            if (backendModeRepository.backendMode.value != org.monogram.domain.repository.TelegramBackendMode.KOTLIN_MTPROTO) {
                repositoryMessage.openChat(chatId, ownerTag = componentInstanceId)
            }
            ChatConversationLog.logViewport(
                chatId = chatId,
                threadId = _state.value.currentMessageThreadId ?: _state.value.currentTopicId,
                event = "open_chat_called",
                componentInstanceId = componentInstanceId
            )
            withContext(Dispatchers.Main) {
                loadChatInfo()
                if (backendModeRepository.backendMode.value != org.monogram.domain.repository.TelegramBackendMode.KOTLIN_MTPROTO) {
                    loadDraft()
                    loadPinnedMessage()
                    loadScheduledMessages()
                }
                loadMembers()
            }
        }
    }

    private fun handleResume() {
        val currentState = _state.value
        if (currentState.isLoading || currentState.isLoadingOlder || currentState.isLoadingNewer) return

        if (currentState.messages.isEmpty() &&
            (!currentState.viewAsTopics || currentState.currentTopicId == null)
        ) {
            requestInitialLoad(source = "lifecycle_resume")
        }
    }

    internal fun requestInitialLoad(source: String) {
        val currentState = _state.value
        if (currentState.isLoading || currentState.isLoadingOlder || currentState.isLoadingNewer) return

        val savedViewport = cacheProvider.getChatViewport(chatId, currentState.effectiveThreadId())
        val predictedTarget = resolveInitialChatScrollTarget(
            chatId = chatId,
            explicitMessageId = initialMessageId,
            savedViewport = savedViewport,
            firstUnreadMessageId = currentState.unreadSeparatorLastReadInboxMessageId.takeIf {
                currentState.unreadSeparatorCount > 0
            },
            unreadCount = currentState.unreadSeparatorCount,
            isComments = currentState.rootMessage != null
        )
        val loadKey = buildChatInitialLoadKey(
            chatId = chatId,
            effectiveThreadId = currentState.effectiveThreadId(),
            explicitMessageId = initialMessageId,
            savedViewport = savedViewport,
            firstUnreadMessageId = currentState.unreadSeparatorLastReadInboxMessageId.takeIf {
                currentState.unreadSeparatorCount > 0
            },
            unreadCount = currentState.unreadSeparatorCount,
            rootMessageId = currentState.rootMessage?.id
        )
        if (!shouldStartInitialLoad(lastStartedLoadKey, loadKey, hasStartedInitialLoadForContext)) {
            return
        }
        lastStartedLoadKey = loadKey
        hasStartedInitialLoadForContext = true
        ChatConversationLog.logViewportState(
            event = "request_initial_load",
            state = currentState,
            componentInstanceId = componentInstanceId,
            extra = "source=$source target=${predictedTarget.perfTargetName()} targetAnchor=${predictedTarget.anchorMessageId ?: 0L} savedViewportAnchor=${savedViewport?.anchorMessageId ?: 0L}"
        )
        ChatConversationLog.logPerf(
            component = this,
            phase = "initial_load_trigger",
            source = source,
            target = predictedTarget.perfTargetName(),
            anchorId = predictedTarget.anchorMessageId
        )
        loadMessages(loadSource = source)
    }

    private fun loadMembers() {
        if (backendModeRepository.backendMode.value == org.monogram.domain.repository.TelegramBackendMode.KOTLIN_MTPROTO) return
        scope.launch {
            val currentState = _state.value
            if (currentState.isGroup || currentState.isChannel) {
                if (currentState.isChannel && !currentState.isAdmin) return@launch

                try {
                    allMembers = chatInfoRepository.getChatMembers(chatId, 0, 200, ChatMembersFilter.Recent)
                        .map { it.user }
                    _state.update { it.copy(searchAvailableSenders = allMembers) }
                } catch (e: Exception) {
                    Log.e("DefaultChatComponent", "Failed to load members", e)
                }
            }
        }
    }

    private fun observeCurrentUser() {
        if (backendModeRepository.backendMode.value == org.monogram.domain.repository.TelegramBackendMode.KOTLIN_MTPROTO) return
        userRepository.currentUserFlow
            .onEach { user ->
                _state.update { it.copy(currentUser = user) }
            }
            .launchIn(scope)
    }

    override fun onSendMessage(
        text: String,
        entities: List<MessageEntity>,
        sendOptions: MessageSendOptions,
        parseMode: RichTextParseMode?
    ) = store.accept(ChatStore.Intent.SendMessage(text, entities, sendOptions, parseMode))

    override fun onSendSticker(stickerPath: String) = store.accept(ChatStore.Intent.SendSticker(stickerPath))
    override fun onSendPhoto(
        photoPath: String,
        caption: String,
        captionEntities: List<MessageEntity>,
        sendOptions: MessageSendOptions
    ) = store.accept(ChatStore.Intent.SendPhoto(photoPath, caption, captionEntities, sendOptions))

    override fun onSendVideo(
        videoPath: String,
        caption: String,
        captionEntities: List<MessageEntity>,
        sendOptions: MessageSendOptions
    ) = store.accept(ChatStore.Intent.SendVideo(videoPath, caption, captionEntities, sendOptions))

    override fun onSendGif(gif: GifModel) = store.accept(ChatStore.Intent.SendGif(gif))
    override fun onSendDocument(
        documentPath: String,
        caption: String,
        captionEntities: List<MessageEntity>,
        sendOptions: MessageSendOptions
    ) = store.accept(
        ChatStore.Intent.SendDocument(
            documentPath,
            caption,
            captionEntities,
            sendOptions
        )
    )

    override fun onSendPoll(
        poll: PollDraft,
        sendOptions: MessageSendOptions
    ) = store.accept(ChatStore.Intent.SendPoll(poll, sendOptions))

    override fun onSendGifFile(
        path: String,
        caption: String,
        captionEntities: List<MessageEntity>,
        sendOptions: MessageSendOptions
    ) = store.accept(ChatStore.Intent.SendGifFile(path, caption, captionEntities, sendOptions))

    override fun onSendAlbum(
        paths: List<String>,
        caption: String,
        captionEntities: List<MessageEntity>,
        sendOptions: MessageSendOptions
    ) = store.accept(ChatStore.Intent.SendAlbum(paths, caption, captionEntities, sendOptions))

    override fun onSendVoice(path: String, duration: Int, waveform: ByteArray) =
        store.accept(ChatStore.Intent.SendVoice(path, duration, waveform))

    override fun onRefreshScheduledMessages() =
        store.accept(ChatStore.Intent.RefreshScheduledMessages)

    override fun onSendScheduledNow(message: MessageModel) =
        store.accept(ChatStore.Intent.SendScheduledNow(message))

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

    override fun onRepeatMessage(message: MessageModel) = store.accept(ChatStore.Intent.RepeatMessage(message))

    override fun onDeleteMessage(message: MessageModel, revoke: Boolean) =
        store.accept(ChatStore.Intent.DeleteMessage(message, revoke))

    override fun onDeleteSelectedMessages(revoke: Boolean) =
        store.accept(ChatStore.Intent.DeleteSelectedMessages(revoke))

    override fun onEditMessage(message: MessageModel) = store.accept(ChatStore.Intent.EditMessage(message))

    override fun onSaveEditedMessage(
        text: String,
        entities: List<MessageEntity>,
        parseMode: RichTextParseMode?
    ) = store.accept(ChatStore.Intent.SaveEditedMessage(text, entities, parseMode))

    override fun onOpenChecklistEditor(message: MessageModel?) =
        run {
            Log.d("ChecklistFlow", "intent_open_editor messageId=${message?.id}")
            store.accept(ChatStore.Intent.OpenChecklistEditor(message))
        }

    override fun onSaveChecklistDraft(draft: ChecklistDraft) =
        run {
            Log.d(
                "ChecklistFlow",
                "intent_save_draft title=${draft.title} tasks=${draft.tasks.size}"
            )
            store.accept(ChatStore.Intent.SaveChecklistDraft(draft))
        }

    override fun onToggleChecklistTask(messageId: Long, taskId: Int, isDone: Boolean) =
        run {
            Log.d(
                "ChecklistFlow",
                "intent_toggle_task messageId=$messageId taskId=$taskId isDone=$isDone"
            )
            store.accept(ChatStore.Intent.ToggleChecklistTask(messageId, taskId, isDone))
        }

    override fun onCancelChecklistEditor() = store.accept(ChatStore.Intent.CancelChecklistEditor)

    override fun onDraftChange(text: String) = store.accept(ChatStore.Intent.DraftChange(text))
    override fun onSelectDraftLinkPreview(url: String) =
        store.accept(ChatStore.Intent.SelectDraftLinkPreview(url))

    override fun onDismissDraftLinkPreview() =
        store.accept(ChatStore.Intent.DismissDraftLinkPreview)

    override fun onRestoreDraftLinkPreview() =
        store.accept(ChatStore.Intent.RestoreDraftLinkPreview)

    override fun onPinMessage(message: MessageModel) = store.accept(ChatStore.Intent.PinMessage(message))

    override fun onUnpinMessage(message: MessageModel) = store.accept(ChatStore.Intent.UnpinMessage(message))

    override fun onHidePinnedMessage() = store.accept(ChatStore.Intent.HidePinnedMessage)

    override fun onShowPinnedMessage() = store.accept(ChatStore.Intent.ShowPinnedMessage)

    override fun onPinnedMessageClick(message: MessageModel?) =
        store.accept(ChatStore.Intent.PinnedMessageClick(message))

    override fun onShowAllPinnedMessages() = store.accept(ChatStore.Intent.ShowAllPinnedMessages)

    override fun onDismissPinnedMessages() = store.accept(ChatStore.Intent.DismissPinnedMessages)

    override fun onScrollToMessageConsumed() = store.accept(ChatStore.Intent.ScrollToMessageConsumed)

    override fun onScrollCommandConsumed() = store.accept(ChatStore.Intent.ScrollCommandConsumed)

    override fun onViewportSettled() {
        val before = _state.value.viewportPhase
        val currentThreadId = _state.value.effectiveThreadId()
        val sessionId = activeLoadSession?.sessionId
        ChatConversationLog.logViewportState(
            event = "on_viewport_settled_before",
            state = _state.value,
            componentInstanceId = componentInstanceId
        )
        _state.update(ConversationViewportReducer::settle)
        ChatOpenPerfBridge.markSettled(chatId, currentThreadId)
        ChatConversationLog.logViewportState(
            event = "on_viewport_settled_after",
            state = _state.value,
            componentInstanceId = componentInstanceId,
            extra = "before=$before sessionId=${sessionId ?: "none"}"
        )
        if (before != ChatViewportPhase.Settled) {
            ChatConversationLog.logPerf(component = this, phase = "viewport_settled")
            ChatOpenPerfBridge.clearSession(chatId, currentThreadId, sessionId)
            activeLoadSession = null
        }
    }

    override fun onMessageViewportChanged(
        visibleMessageIds: Set<Long>,
        nearbyMessageIds: Set<Long>
    ) {
        richMessageCoordinator.onViewportChanged(
            visibleMessageIds = visibleMessageIds,
            nearbyMessageIds = nearbyMessageIds
        )
        if (backendModeRepository.backendMode.value == TelegramBackendMode.KOTLIN_MTPROTO) return
        val state = _state.value
        val networkEnabled = when {
            downloadUtils.isRoaming() -> state.autoDownloadRoaming
            downloadUtils.isWifiConnected() -> state.autoDownloadWifi
            else -> state.autoDownloadMobile
        }
        repositoryMessage.updateVisibleRange(
            chatId = chatId,
            visibleMessageIds = visibleMessageIds.toList(),
            nearbyMessageIds = nearbyMessageIds.filterNot(visibleMessageIds::contains),
            policy = MediaAutoDownloadPolicy(
                enabled = networkEnabled,
                allowFiles = networkEnabled && state.autoDownloadFiles
            )
        )
    }

    override fun onScrollToBottom() = store.accept(ChatStore.Intent.ScrollToBottom)
    override fun onJumpToLatest() = store.accept(ChatStore.Intent.JumpToLatest)
    override fun onScrollToNextUnreadMention() =
        store.accept(ChatStore.Intent.ScrollToNextUnreadMention)

    override fun onClearUnreadMentions() = store.accept(ChatStore.Intent.ClearUnreadMentions)

    override fun onScrollToNextUnreadReaction() =
        store.accept(ChatStore.Intent.ScrollToNextUnreadReaction)

    override fun onClearUnreadReactions() = store.accept(ChatStore.Intent.ClearUnreadReactions)

    override fun onDownloadFile(fileId: Int, userInitiated: Boolean) {
        AutoDownloadSuppression.clear(fileId)
        store.accept(ChatStore.Intent.DownloadFile(fileId, userInitiated))
    }

    override fun onDownloadHighRes(messageId: Long) = store.accept(ChatStore.Intent.DownloadHighRes(messageId))

    override fun onCancelDownloadFile(fileId: Int) {
        AutoDownloadSuppression.suppress(fileId)
        store.accept(ChatStore.Intent.CancelDownloadFile(fileId))
    }

    override fun updateScrollPosition(messageId: Long) {
        updateViewport(
            ChatViewportCacheEntry(
                anchorMessageId = messageId,
                anchorOffsetPx = 0,
                atBottom = messageId == 0L,
                readFully = messageId == 0L
            )
        )
    }

    override fun updateViewport(viewport: ChatViewportCacheEntry) {
        val threadId = _state.value.currentMessageThreadId ?: _state.value.currentTopicId
        _state.update {
            if (it.lastSavedViewport == viewport && it.lastScrollPosition == (viewport.anchorMessageId
                    ?: 0L)
            ) {
                it
            } else {
                it.copy(
                    lastSavedViewport = viewport,
                    lastScrollPosition = viewport.anchorMessageId ?: 0L
                )
            }
        }
        scheduleViewportPersistence(threadId, viewport)
    }

    override fun onBottomReached(isAtBottom: Boolean) = store.accept(ChatStore.Intent.BottomReached(isAtBottom))

    override fun onHighlightConsumed() = store.accept(ChatStore.Intent.HighlightConsumed)

    override fun onTyping() = store.accept(ChatStore.Intent.Typing)

    override fun onSendReaction(messageId: Long, reaction: String) =
        store.accept(ChatStore.Intent.SendReaction(messageId, reaction))

    override suspend fun getMessageReadDate(chatId: Long, messageId: Long, messageDate: Int): Int {
        check(backendModeRepository.backendMode.value != TelegramBackendMode.KOTLIN_MTPROTO) {
            "MTProto message read-date diagnostics are not available"
        }
        return repositoryMessage.getMessageReadDate(chatId, messageId, messageDate)
    }

    override suspend fun getMessageViewers(chatId: Long, messageId: Long): List<MessageViewerModel> =
        repositoryMessage.getMessageViewers(chatId, messageId)

    override suspend fun getRawMessageJson(chatId: Long, messageId: Long): String? {
        check(backendModeRepository.backendMode.value != TelegramBackendMode.KOTLIN_MTPROTO) {
            "MTProto raw message diagnostics are not available"
        }
        return repositoryMessage.getRawMessageJson(chatId, messageId)
    }

    override fun toProfile(id: Long) = toProfiles(id)
    override fun onForwardOriginClick(forwardInfo: ForwardInfo) {
        when (forwardInfo.originType) {
            ForwardOriginType.USER -> {
                if (forwardInfo.fromId != 0L) {
                    toProfiles(forwardInfo.fromId)
                }
            }

            ForwardOriginType.CHANNEL -> {
                val originChatId = forwardInfo.originChatId
                val originMessageId = forwardInfo.originMessageId
                if (originChatId != null && originMessageId != null) {
                    navigateToChatMessage(originChatId, originMessageId)
                }
            }

            else -> Unit
        }
    }
    override fun onToggleMessageSelection(messageId: Long) =
        store.accept(ChatStore.Intent.ToggleMessageSelection(messageId))

    override fun onClearSelection() = store.accept(ChatStore.Intent.ClearSelection)
    override fun onClearMessages() = store.accept(ChatStore.Intent.ClearMessages)

    override fun onCopySelectedMessages(localClipboard: Clipboard) =
        store.accept(ChatStore.Intent.CopySelectedMessages(localClipboard))

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
    override fun onShareToStory(mediaUrl: String, text: String?, widgetLink: String?) {
        onShareToStoryRequested(mediaUrl, text, widgetLink)
    }
    override fun onAcceptMiniAppTOS() = store.accept(ChatStore.Intent.AcceptMiniAppTOS)
    override fun onDismissMiniAppTOS() = store.accept(ChatStore.Intent.DismissMiniAppTOS)

    override fun onOpenWebView(url: String) = store.accept(ChatStore.Intent.OpenWebView(url))

    override fun onDismissWebView() = store.accept(ChatStore.Intent.DismissWebView)

    override fun onOpenImages(
        images: List<String>,
        captions: List<String?>,
        startIndex: Int,
        messageId: Long?,
        messageIds: List<Long>
    ) =
        store.accept(ChatStore.Intent.OpenImages(images, captions, startIndex, messageId, messageIds))

    override fun onDismissImages() = store.accept(ChatStore.Intent.DismissImages)

    override fun onOpenVideo(path: String?, messageId: Long?, caption: String?) =
        store.accept(ChatStore.Intent.OpenVideo(path, messageId, caption))

    override fun onDismissVideo() = store.accept(ChatStore.Intent.DismissVideo)

    override fun onAddToAdBlockWhitelist() = store.accept(ChatStore.Intent.AddToAdBlockWhitelist)

    override fun onRemoveFromAdBlockWhitelist() = store.accept(ChatStore.Intent.RemoveFromAdBlockWhitelist)

    override fun onToggleMute() = store.accept(ChatStore.Intent.ToggleMute)

    override fun onSearchToggle() = store.accept(ChatStore.Intent.SearchToggle)

    override fun onSearchQueryChange(query: String) = store.accept(ChatStore.Intent.SearchQueryChange(query))
    override fun onSearchNextResult() = store.accept(ChatStore.Intent.SearchNextResult)
    override fun onSearchPreviousResult() = store.accept(ChatStore.Intent.SearchPreviousResult)
    override fun onSearchResultClick(index: Int) =
        store.accept(ChatStore.Intent.SearchResultClick(index))

    override fun onLoadMoreSearchResults() = store.accept(ChatStore.Intent.LoadMoreSearchResults)
    override fun onSearchSenderChange(user: UserModel?) =
        store.accept(ChatStore.Intent.SearchSenderChange(user))

    override fun onSearchDateRangeChange(fromEpochSeconds: Int?, toEpochSeconds: Int?) =
        store.accept(ChatStore.Intent.SearchDateRangeChange(fromEpochSeconds, toEpochSeconds))

    override fun onClearHistory() = store.accept(ChatStore.Intent.ClearHistory)
    override fun onLeaveChat() = store.accept(ChatStore.Intent.LeaveChat)
    override fun onDeleteChat() = store.accept(ChatStore.Intent.DeleteChat)

    override fun onReport() = store.accept(ChatStore.Intent.Report)

    override fun onReportMessage(message: MessageModel) = store.accept(ChatStore.Intent.ReportMessage(message))

    override fun onReportReasonSelected(reason: String) = store.accept(ChatStore.Intent.ReportReasonSelected(reason))

    override fun onDismissReportDialog() = store.accept(ChatStore.Intent.DismissReportDialog)

    override fun onCopyLink(localClipboard: Clipboard) =
        store.accept(ChatStore.Intent.CopyLink(localClipboard))

    override fun scrollToMessage(messageId: Long) {
        rememberReturnTargetBeforeJump(messageId)
        store.accept(ChatStore.Intent.ScrollToMessage(messageId))
    }
    override fun onBotCommandClick(command: String) = store.accept(ChatStore.Intent.BotCommandClick(command))
    override fun onShowBotCommands() = store.accept(ChatStore.Intent.ShowBotCommands)
    override fun onDismissBotCommands() = store.accept(ChatStore.Intent.DismissBotCommands)

    override fun onCommentsClick(messageId: Long) = store.accept(ChatStore.Intent.CommentsClick(messageId))

    override fun onReplyMarkupButtonClick(messageId: Long, button: InlineKeyboardButtonModel, botUserId: Long) =
        store.accept(ChatStore.Intent.ReplyMarkupButtonClick(messageId, button, botUserId))

    override fun onReplyMarkupButtonClick(messageId: Long, button: KeyboardButtonModel, botUserId: Long) =
        store.accept(ChatStore.Intent.KeyboardButtonClick(messageId, button, botUserId))

    override fun onLinkClick(url: String) = store.accept(ChatStore.Intent.LinkClick(url))

    override fun onChannelSponsoredMessageClick(
        messageId: Long,
        url: String,
        isMediaClick: Boolean
    ) {
        scope.launch {
            runCatching {
                repositoryMessage.clickChannelSponsoredMessage(
                    chatId = chatId,
                    messageId = messageId,
                    isMediaClick = isMediaClick,
                    fromFullscreen = false
                )
            }
            onLink(url)
        }
    }

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
            val botInfo = botRepository.getBotInfo(botUserId)
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

    override fun onStageAttachments(attachments: List<PendingAttachment>) {
        _state.update { it.copy(stagedAttachments = attachments) }
    }

    override fun onClearPendingAttachments() {
        cleanupTempAttachments(_state.value.stagedAttachments)
        _state.update { it.copy(stagedAttachments = emptyList()) }
    }

    override fun onConsumeInitialShare(requestId: Long) {
        val current = _state.value.initialShare
        if (current?.requestId != requestId) return
        _state.update {
            it.copy(
                initialShareConsumed = true,
                initialShare = null
            )
        }
        onInitialShareConsumed(requestId)
    }

    override fun onRemovePendingAttachment(path: String) {
        val current = _state.value.stagedAttachments
        val removed = current.firstOrNull { it.localPath == path } ?: return
        cleanupTempAttachments(listOf(removed))
        _state.update {
            it.copy(stagedAttachments = current.filterNot { attachment -> attachment.localPath == path })
        }
    }

    override fun onReplacePendingAttachment(oldPath: String, attachment: PendingAttachment) {
        val current = _state.value.stagedAttachments.toMutableList()
        val index = current.indexOfFirst { it.localPath == oldPath }
        if (index == -1) return
        val previous = current[index]
        current[index] = attachment
        cleanupTempAttachments(listOf(previous), excludePaths = setOf(attachment.localPath))
        _state.update { it.copy(stagedAttachments = current) }
    }

    override fun onSendPendingAttachments(
        attachments: List<PendingAttachment>,
        caption: String,
        captionEntities: List<MessageEntity>,
        sendOptions: MessageSendOptions
    ) {
        handleSendPendingAttachments(
            attachments = attachments,
            caption = caption,
            captionEntities = captionEntities,
            sendOptions = sendOptions
        )
    }

    override fun onClosePoll(messageId: Long) = store.accept(ChatStore.Intent.ClosePoll(messageId))

    internal fun cleanupTempAttachments(
        attachments: List<PendingAttachment>,
        excludePaths: Set<String> = emptySet()
    ) {
        attachments.forEach { attachment ->
            if (!attachment.deleteAfterUse || attachment.localPath in excludePaths) return@forEach
            runCatching { File(attachment.localPath).deleteRecursively() }
        }
    }

    internal fun overrideViewportToBottomNow(threadId: Long?) {
        val viewport = ChatViewportCacheEntry(atBottom = true, readFully = true)
        viewportPersistenceJob?.cancel()
        viewportPersistenceJob = null
        pendingViewportToPersist = null
        pendingViewportThreadId = null

        _state.update {
            it.copy(
                isAtBottom = true,
                lastSavedViewport = viewport,
                lastScrollPosition = 0L
            )
        }

        cacheProvider.saveChatViewport(chatId, threadId, viewport)
        repositoryMessage.updateCachedViewportAnchor(
            historyConversationKey(activeThreadChatId(), threadId),
            null
        )
        if (threadId == null) {
            cacheProvider.saveChatScrollPosition(chatId, 0L)
        }

        lastPersistedViewportThreadId = threadId
        lastPersistedViewport = viewport
    }

    private fun scheduleViewportPersistence(threadId: Long?, viewport: ChatViewportCacheEntry) {
        val previous = when {
            pendingViewportToPersist != null && pendingViewportThreadId == threadId -> pendingViewportToPersist
            lastPersistedViewportThreadId == threadId -> lastPersistedViewport
            else -> null
        }
        if (!isMeaningfulViewportChange(previous, viewport)) return

        pendingViewportThreadId = threadId
        pendingViewportToPersist = viewport
        viewportPersistenceJob?.cancel()
        viewportPersistenceJob = scope.launch {
            delay(VIEWPORT_PERSIST_DEBOUNCE_MS)
            flushViewportPersistence()
        }
    }

    private fun flushViewportPersistence() {
        viewportPersistenceJob?.cancel()
        viewportPersistenceJob = null

        val hasPendingViewport = pendingViewportToPersist != null
        val viewport = pendingViewportToPersist ?: _state.value.lastSavedViewport ?: return
        val threadId = if (hasPendingViewport) {
            pendingViewportThreadId
        } else {
            _state.value.currentMessageThreadId ?: _state.value.currentTopicId
        }

        cacheProvider.saveChatViewport(chatId, threadId, viewport)
        repositoryMessage.updateCachedViewportAnchor(
            key = historyConversationKey(activeThreadChatId(), threadId),
            messageId = viewport.anchorMessageId?.takeUnless { viewport.atBottom }
        )
        if (threadId == null) {
            cacheProvider.saveChatScrollPosition(chatId, viewport.anchorMessageId ?: 0L)
        }

        lastPersistedViewportThreadId = threadId
        lastPersistedViewport = viewport
        pendingViewportThreadId = null
        pendingViewportToPersist = null
    }

    private fun isMeaningfulViewportChange(
        previous: ChatViewportCacheEntry?,
        next: ChatViewportCacheEntry
    ): Boolean {
        previous ?: return true
        return previous.anchorMessageId != next.anchorMessageId ||
                previous.anchorAliasIds != next.anchorAliasIds ||
                previous.atBottom != next.atBottom ||
                previous.readFully != next.readFully ||
                previous.topEndMessageId != next.topEndMessageId ||
                previous.returnToMessageIds != next.returnToMessageIds ||
                previous.anchorChatId != next.anchorChatId ||
                abs(previous.anchorOffsetPx - next.anchorOffsetPx) > VIEWPORT_OFFSET_DELTA_THRESHOLD_PX
    }

    private fun rememberReturnTargetBeforeJump(targetMessageId: Long) {
        val currentViewport = _state.value.lastSavedViewport ?: return
        val returnTarget = currentViewport.anchorMessageId
            ?: currentViewport.anchorAliasIds.firstOrNull()
            ?: return
        if (returnTarget == targetMessageId) return

        val updatedViewport = pushViewportReturnTarget(
            viewport = currentViewport,
            returnTargetMessageId = returnTarget,
            maxSize = MAX_RETURN_TO_MESSAGE_IDS
        ) ?: return
        updateViewport(updatedViewport)
    }
}
