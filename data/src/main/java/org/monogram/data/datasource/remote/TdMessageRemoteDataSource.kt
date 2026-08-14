package org.monogram.data.datasource.remote

import android.os.Build
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.drinkless.tdlib.TdApi
import org.monogram.core.DispatcherProvider
import org.monogram.core.perf.ChatOpenPerfBridge
import org.monogram.core.perf.ChatOpenPerfDebug
import org.monogram.data.BuildConfig
import org.monogram.data.chats.ChatCache
import org.monogram.data.compat.buildInputAnimation
import org.monogram.data.compat.buildInputDocument
import org.monogram.data.compat.buildInputPhoto
import org.monogram.data.compat.buildInputPollOption
import org.monogram.data.compat.buildInputPollTypeQuiz
import org.monogram.data.compat.buildInputSticker
import org.monogram.data.compat.buildInputVideo
import org.monogram.data.compat.buildInputVideoNote
import org.monogram.data.compat.buildInputVoiceNote
import org.monogram.data.compat.buildRichMessageSourceHtml
import org.monogram.data.compat.buildRichMessageSourceMarkdown
import org.monogram.data.compat.extractTextDraft
import org.monogram.data.gateway.TdLibException
import org.monogram.data.gateway.TelegramGateway
import org.monogram.data.infra.AtomicSingleFlight
import org.monogram.data.infra.FileDownloadQueue
import org.monogram.data.infra.FileUpdateHandler
import org.monogram.data.infra.OrderedEventFlow
import org.monogram.data.mapper.MessageMapper
import org.monogram.data.mapper.WebPageMapper
import org.monogram.data.repository.DraftLinkPreviewResolver
import org.monogram.domain.models.DraftLinkPreview
import org.monogram.domain.models.DraftLinkPreviewRequest
import org.monogram.domain.models.FileDownloadEvent
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageDeletedEvent
import org.monogram.domain.models.MessageDownloadEvent
import org.monogram.domain.models.MessageEntity
import org.monogram.domain.models.MessageEntityType
import org.monogram.domain.models.MessageIdUpdatedEvent
import org.monogram.domain.models.MessageModel
import org.monogram.domain.models.MessageSendAcknowledgedEvent
import org.monogram.domain.models.MessageSendFailedEvent
import org.monogram.domain.models.MessageSendOptions
import org.monogram.domain.models.MessageUploadProgressEvent
import org.monogram.domain.models.MessageViewerModel
import org.monogram.domain.models.PollDraft
import org.monogram.domain.models.TdLibLimits
import org.monogram.domain.models.UserModel
import org.monogram.domain.models.webapp.ThemeParams
import org.monogram.domain.models.webapp.WebAppInfoModel
import org.monogram.domain.repository.ChatListRepository
import org.monogram.domain.repository.ChecklistDraft
import org.monogram.domain.repository.ConversationScope
import org.monogram.domain.repository.MediaAutoDownloadPolicy
import org.monogram.domain.repository.PollRepository
import org.monogram.domain.repository.ReadUpdate
import org.monogram.domain.repository.RichTextParseMode
import org.monogram.domain.repository.SearchChatMessagesResult
import org.monogram.domain.repository.TdLibLimitsRepository
import org.monogram.domain.repository.UserRepository
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class TdMessageRemoteDataSource(
    private val gateway: TelegramGateway,
    private val messageMapper: MessageMapper,
    private val userRepository: UserRepository,
    private val chatListRepository: ChatListRepository,
    private val cache: ChatCache,
    private val pollRepository: PollRepository,
    private val fileDownloadQueue: FileDownloadQueue,
    private val fileUpdateHandler: FileUpdateHandler,
    private val webPageMapper: WebPageMapper,
    private val draftLinkPreviewResolver: DraftLinkPreviewResolver,
    private val dispatcherProvider: DispatcherProvider,
    val scope: CoroutineScope,
    private val tdLibLimitsRepository: TdLibLimitsRepository
) : MessageRemoteDataSource {

    private val chatRequests = AtomicSingleFlight<Long, TdApi.Chat?>(scope)
    private val messageRequests = AtomicSingleFlight<Pair<Long, Long>, TdApi.Message?>(scope)
    private val refreshJobs = ConcurrentHashMap<Pair<Long, Long>, Job>()
    private val openChatIds = ConcurrentHashMap.newKeySet<Long>()
    private val missingMessageCooldownUntil = ConcurrentHashMap<Pair<Long, Long>, Long>()
    private val sendQueue = Channel<suspend () -> Unit>(Channel.BUFFERED)

    // With the default arguments (replay 0, no buffer, SUSPEND), MutableSharedFlow is a
    // rendezvous channel:
    // every emit parks until *all* subscribers have taken the value, which piled emitters up
    // without bound during bursts. OrderedEventFlow.enqueue is non-suspending and lossless,
    // so the event streams go through it; progress ticks are conflatable and get an explicit
    // bounded buffer instead.
    private val newMessages = OrderedEventFlow<MessageModel>(scope)
    override val newMessageFlow = newMessages.events
    private val messageEdits = OrderedEventFlow<MessageModel>(scope)
    override val messageEditedFlow = messageEdits.events
    private val messageReads = OrderedEventFlow<ReadUpdate>(scope)
    override val messageReadFlow = messageReads.events
    override val messageUploadProgressFlow = MutableSharedFlow<MessageUploadProgressEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val fileDownloads = OrderedEventFlow<FileDownloadEvent>(scope)
    override val fileDownloadFlow = fileDownloads.events
    private val messageDownloads = OrderedEventFlow<MessageDownloadEvent>(scope)
    override val messageDownloadFlow = messageDownloads.events
    private val messageDeletes = OrderedEventFlow<MessageDeletedEvent>(scope)
    override val messageDeletedFlow = messageDeletes.events
    private val messageIdUpdates = OrderedEventFlow<MessageIdUpdatedEvent>(scope)
    override val messageIdUpdateFlow = messageIdUpdates.events
    private val messageAcknowledgements = OrderedEventFlow<MessageSendAcknowledgedEvent>(scope)
    override val messageAcknowledgedFlow = messageAcknowledgements.events
    private val messageSendFailures = OrderedEventFlow<MessageSendFailedEvent>(scope)
    override val messageSendFailedFlow = messageSendFailures.events
    override val pinnedMessageFlow = MutableSharedFlow<Long>(
        extraBufferCapacity = 10,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val mediaUpdateFlow = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 10,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    enum class DownloadType { VIDEO, GIF, STICKER, VIDEO_NOTE, DEFAULT }

    private val fileIdToMessageMap = fileDownloadQueue.registry.fileIdToMessageMap
    private val messageUpdateJobs = ConcurrentHashMap<Pair<Long, Long>, Job>()
    private val lastProgressMap = ConcurrentHashMap<Int, Int>()
    private val lastDownloadActiveMap = ConcurrentHashMap<Int, Boolean>()
    private val lastCancelledEmissionAt = ConcurrentHashMap<Int, Long>()

    init {
        fileDownloadQueue.setObserver(object : FileDownloadQueue.Observer {
            override fun onDownloadQueued(fileId: Int) {
                emitQueuedForFile(fileId)
            }

            override fun onDownloadCancelled(fileId: Int) {
                emitCancelledForFile(fileId)
            }
        })
        scope.launch {
            for (task in sendQueue) {
                try {
                    task()
                } catch (e: Exception) {
                    Log.e("TdMessageRemote", "Error in sendQueue", e)
                }
            }
        }
        scope.launch(dispatcherProvider.io) {
            fileUpdateHandler.fileUpdates.collect(::projectFileUpdate)
        }
    }

    private suspend fun <T : TdApi.Object> safeExecute(function: TdApi.Function<T>): T? {
        return try {
            gateway.execute(function)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("TdMessageRemote", "Error executing ${function.javaClass.simpleName}", e)
            if (e.isLikelyLimitViolation()) {
                scope.launch {
                    runCatching { tdLibLimitsRepository.refresh() }
                }
            }
            null
        }
    }

    private fun Throwable.isLikelyLimitViolation(): Boolean {
        val error = (this as? TdLibException)?.error ?: return false
        if (error.code !in 400..499) return false
        val message = error.message.orEmpty().lowercase()
        return "too long" in message ||
                "length" in message ||
                "limit" in message ||
                "maximum" in message ||
                "max_" in message
    }


    override suspend fun getMessage(chatId: Long, messageId: Long): TdApi.Message? {
        cache.getMessage(chatId, messageId)?.let { return it }
        val key = chatId to messageId
        val cooldownUntil = missingMessageCooldownUntil[key]
        if (cooldownUntil != null) {
            if (cooldownUntil > System.currentTimeMillis()) {
                return null
            }
            missingMessageCooldownUntil.remove(key, cooldownUntil)
        }
        return runCatching {
            messageRequests.execute(key) {
                val local = safeExecute(TdApi.GetMessageLocally(chatId, messageId))
                val result = local ?: safeExecute(TdApi.GetMessage(chatId, messageId))
                if (result != null) {
                    cache.putMessage(result)
                    missingMessageCooldownUntil.remove(key)
                } else {
                    missingMessageCooldownUntil[key] =
                        System.currentTimeMillis() + MISSING_MESSAGE_COOLDOWN_MS
                }
                result
            }
        }.getOrNull()
    }

    override suspend fun getMessagesLocally(
        chatId: Long,
        messageIds: List<Long>,
        scope: ConversationScope
    ): RemoteMessageBatch = withContext(dispatcherProvider.io) {
        val rawMessages = buildLocalMessageRequests(chatId, messageIds).mapNotNull { request ->
            val message = cache.getMessage(request.chatId, request.messageId)
                ?: safeExecute(request)
            message?.takeIf { messageMatchesScope(it.topicId, scope) }
        }
        RemoteMessageBatch(
            rawMessages = rawMessages,
            models = mapHistoryMessages(
                chatId = chatId,
                messages = rawMessages,
                options = LOCAL_HISTORY_MAP_OPTIONS,
                allowChatFetch = false
            )
        )
    }

    override suspend fun getChatSponsoredMessages(chatId: Long): TdApi.SponsoredMessages? {
        return safeExecute(TdApi.GetChatSponsoredMessages(chatId))
    }

    override suspend fun clickChatSponsoredMessage(
        chatId: Long,
        messageId: Long,
        isMediaClick: Boolean,
        fromFullscreen: Boolean
    ): TdApi.Ok? {
        return safeExecute(
            TdApi.ClickChatSponsoredMessage(
                chatId,
                messageId,
                isMediaClick,
                fromFullscreen
            )
        )
    }

    suspend fun getChat(chatId: Long): TdApi.Chat? {
        cache.getChat(chatId)?.let { return it }
        return runCatching {
            chatRequests.execute(chatId) {
                val result = safeExecute(TdApi.GetChat(chatId))
                if (result != null) cache.putChat(result)
                result
            }
        }.getOrNull()
    }

    override suspend fun getRemoteMessagesOlder(
        chatId: Long,
        fromMessageId: Long,
        limit: Int,
        scope: ConversationScope,
        fetchMode: RemoteHistoryFetchMode
    ): RemoteOlderMessagesPage {
        if (fromMessageId == 0L) {
            val batch = loadMessageBatch(
                chatId = chatId,
                fromMessageId = fromMessageId,
                offset = 0,
                limit = limit,
                conversationScope = scope,
                fetchMode = fetchMode,
                options = MessageMapOptions(
                    resolveReplyPreviewFromNetwork = false,
                    allowAutoDownload = false
                )
            )
            return RemoteOlderMessagesPage(
                rawMessages = batch.rawMessages,
                models = batch.models,
                reachedOldest = batch.models.isEmpty()
            )
        }

        val batch = loadMessageBatch(
            chatId = chatId,
            fromMessageId = fromMessageId,
            offset = 0,
            limit = limit + 1,
            conversationScope = scope,
            fetchMode = fetchMode,
            options = MessageMapOptions(
                resolveReplyPreviewFromNetwork = false,
                allowAutoDownload = false
            )
        )
        val filteredPairs = batch.rawMessages.zip(batch.models)
            .filter { (_, model) -> model.id != fromMessageId }
            .take(limit)
        return RemoteOlderMessagesPage(
            rawMessages = filteredPairs.map { it.first },
            models = filteredPairs.map { it.second },
            reachedOldest = filteredPairs.isEmpty()
        )
    }

    override suspend fun getRemoteMessagesNewer(
        chatId: Long,
        fromMessageId: Long,
        limit: Int,
        scope: ConversationScope,
        fetchMode: RemoteHistoryFetchMode
    ): RemoteMessageBatch {
        return loadMessageBatch(
            chatId = chatId,
            fromMessageId = fromMessageId,
            offset = -limit,
            limit = limit,
            conversationScope = scope,
            fetchMode = fetchMode,
            options = MessageMapOptions(
                resolveReplyPreviewFromNetwork = false,
                allowAutoDownload = false
            )
        )
    }

    override suspend fun getRemoteMessagesAround(
        chatId: Long,
        messageId: Long,
        limit: Int,
        scope: ConversationScope,
        fetchMode: RemoteHistoryFetchMode
    ): RemoteMessageBatch {
        return loadMessageBatch(
            chatId = chatId,
            fromMessageId = messageId,
            offset = -limit / 2,
            limit = limit,
            conversationScope = scope,
            fetchMode = fetchMode,
            options = MessageMapOptions(
                resolveReplyPreviewFromNetwork = false,
                allowAutoDownload = false
            )
        )
    }

    override suspend fun getChatPinnedMessage(chatId: Long): TdApi.Message? =
        safeExecute(TdApi.GetChatPinnedMessage(chatId))

    override suspend fun getPinnedMessageModel(chatId: Long, threadId: Long?): MessageModel? {
        val chat = getChat(chatId) ?: return null

        if (threadId != null) {
            if (chat.viewAsTopics) {
                val topic = safeExecute(TdApi.GetForumTopic(chatId, threadId.toInt()))
                if (topic != null) return searchPinnedMessage(chatId, threadId.toInt())
                return null
            }
        }

        val result = getChatPinnedMessage(chatId)
        return if (result != null) {
            cache.putMessage(result)
            messageMapper.mapMessageToModel(result, isChatOpen = true)
        } else null
    }

    override suspend fun getAllPinnedMessages(chatId: Long, threadId: Long?): List<MessageModel> {
        val allPinnedMessages = mutableListOf<MessageModel>()
        var offsetMessageId = 0L
        var totalCount = Int.MAX_VALUE

        while (allPinnedMessages.size < totalCount) {
            val batch = getPinnedMessagesPage(chatId, threadId, offsetMessageId)

            if (batch.totalCount == 0 || batch.messages.isEmpty()) {
                break
            }

            totalCount = batch.totalCount

            var addedCount = 0
            for (message in batch.messages) {
                if (allPinnedMessages.none { it.id == message.id }) {
                    cache.putMessage(message)
                    val model = messageMapper.mapMessageToModel(message, isChatOpen = true)
                    allPinnedMessages.add(model)
                    addedCount++
                }
            }

            if (addedCount == 0) {
                break
            }

            offsetMessageId = batch.messages.last().id
        }

        return allPinnedMessages
    }

    override suspend fun getPinnedMessageCount(chatId: Long, threadId: Long?): Int {
            val request = TdApi.SearchChatMessages().apply {
                this.chatId = chatId
                this.query = ""
                this.senderId = null
                this.fromMessageId = 0
                this.offset = 0
                this.limit = 1
                this.filter = TdApi.SearchMessagesFilterPinned()
                this.topicId = if (threadId != null) {
                    TdApi.MessageTopicForum(threadId.toInt())
                } else {
                    null
                }
            }

        val result = safeExecute(request)
        return if (result is TdApi.FoundChatMessages) {
            result.totalCount
        } else {
            0
        }
    }

    override suspend fun getScheduledMessages(chatId: Long): List<MessageModel> {
        val result = safeExecute(TdApi.GetChatScheduledMessages(chatId)) ?: return emptyList()
        if (result !is TdApi.Messages) return emptyList()

        return result.messages
            .onEach { cache.putMessage(it) }
            .map { messageMapper.mapMessageToModel(it, isChatOpen = true) }
            .sortedBy { it.date }
    }

    override suspend fun sendScheduledNow(chatId: Long, messageId: Long) {
        safeExecute(
            TdApi.EditMessageSchedulingState(
                chatId,
                messageId,
                null
            )
        )
    }

    override suspend fun getDraftLinkPreview(request: DraftLinkPreviewRequest): DraftLinkPreview? {
        val normalizedUrl = draftLinkPreviewResolver.normalizeUrl(request.sourceUrl) ?: run {
            Log.d(
                DRAFT_LINK_PREVIEW_TAG,
                "td getDraftLinkPreview source=${request.sourceUrl} normalized=null"
            )
            return null
        }
        Log.d(
            DRAFT_LINK_PREVIEW_TAG,
            "td getDraftLinkPreview source=${request.sourceUrl} normalized=$normalizedUrl"
        )
        val formattedText = TdApi.FormattedText(normalizedUrl, emptyArray())
        val previewOptions = TdApi.LinkPreviewOptions(false, normalizedUrl, false, false, false)
        Log.d(DRAFT_LINK_PREVIEW_TAG, "td request GetLinkPreview url=$normalizedUrl")
        val result = safeExecute(TdApi.GetLinkPreview(formattedText, previewOptions))
        Log.d(
            DRAFT_LINK_PREVIEW_TAG,
            "td response type=${result?.javaClass?.simpleName ?: "null"}"
        )
        if (result !is TdApi.LinkPreview) return null

        val webPage = webPageMapper.map(
            webPage = result,
            chatId = 0L,
            messageId = 0L,
            networkAutoDownload = true
        )
        Log.d(
            DRAFT_LINK_PREVIEW_TAG,
            "td mapped webPage success=${webPage != null} url=${webPage?.url}"
        )
        webPage ?: return null

        return DraftLinkPreview(
            sourceUrl = request.sourceUrl,
            resolvedUrl = webPage.url ?: normalizedUrl,
            webPage = webPage
        )
    }

    private suspend fun getPinnedMessagesPage(
        chatId: Long,
        threadId: Long?,
        fromMessageId: Long
    ): TdApi.FoundChatMessages {
        val request = TdApi.SearchChatMessages().apply {
            this.chatId = chatId
            this.query = ""
            this.senderId = null
            this.fromMessageId = fromMessageId
            this.offset = 0
            this.limit = 100
            this.filter = TdApi.SearchMessagesFilterPinned()
            this.topicId = if (threadId != null) {
                TdApi.MessageTopicForum(threadId.toInt())
            } else {
                null
            }
        }

        val result = safeExecute(request)
        return result ?: TdApi.FoundChatMessages(0, emptyArray(), 0L)
    }

    private suspend fun searchPinnedMessage(chatId: Long, threadId: Int): MessageModel? {
        val request = TdApi.SearchChatMessages().apply {
            this.chatId = chatId
            this.topicId = TdApi.MessageTopicForum(threadId)
            this.query = ""
            this.senderId = null
            this.fromMessageId = 0
            this.offset = 0
            this.limit = 1
            this.filter = TdApi.SearchMessagesFilterPinned()
        }
        val result = safeExecute(request)
        return if (result != null && result.messages.isNotEmpty()) {
            val msg = result.messages.first()
            cache.putMessage(msg)
            messageMapper.mapMessageToModel(msg, isChatOpen = true)
        } else null
    }

    override suspend fun getPollVoters(chatId: Long, messageId: Long, optionId: Int, offset: Int, limit: Int): TdApi.PollVoters? =
        safeExecute(TdApi.GetPollVoters(chatId, messageId, optionId, offset, limit))

    override suspend fun getMessageViewers(chatId: Long, messageId: Long): TdApi.MessageViewers? =
        safeExecute(TdApi.GetMessageViewers(chatId, messageId))

    override suspend fun getPollVotersModels(chatId: Long, messageId: Long, optionId: Int, offset: Int, limit: Int): List<UserModel> {
        val result = getPollVoters(chatId, messageId, optionId, offset, limit) ?: return emptyList()
        return result.voters.mapNotNull { pollVoter ->
            when (val sender = pollVoter.voterId) {
                is TdApi.MessageSenderUser -> userRepository.getUser(sender.userId)
                is TdApi.MessageSenderChat -> {
                    val cachedChat = cache.getChat(sender.chatId)
                    if (cachedChat != null) {
                        UserModel(id = cachedChat.id, firstName = cachedChat.title, lastName = "", username = null, avatarPath = cachedChat.photo?.small?.local?.path)
                    } else {
                        val chat = chatListRepository.getChatById(sender.chatId)
                        if (chat != null) UserModel(id = chat.id, firstName = chat.title, lastName = "", username = null, avatarPath = chat.avatarPath)
                        else null
                    }
                }
                else -> null
            }
        }
    }

    override suspend fun getMessageViewersModels(chatId: Long, messageId: Long): List<MessageViewerModel> {
        val result = getMessageViewers(chatId, messageId) ?: return emptyList()
        return result.viewers.mapNotNull { viewer ->
            val user = userRepository.getUser(viewer.userId) ?: return@mapNotNull null
            MessageViewerModel(
                user = user,
                viewedDate = viewer.viewDate
            )
        }
    }

    override suspend fun searchChatMessages(
        chatId: Long,
        query: String,
        fromMessageId: Long,
        limit: Int,
        filter: TdApi.SearchMessagesFilter,
        threadId: Long?,
        senderId: Long?
    ): TdApi.FoundChatMessages? {
        val request = TdApi.SearchChatMessages().apply {
            this.chatId = chatId
            this.topicId = resolveSearchTopicId(chatId, threadId)
            this.query = query
            this.senderId = senderId?.let(TdApi::MessageSenderUser)
            this.fromMessageId = fromMessageId
            this.offset = 0
            this.limit = limit
            this.filter = filter
        }
        return safeExecute(request)
    }

    override suspend fun searchMessages(
        chatId: Long,
        query: String,
        fromMessageId: Long,
        limit: Int,
        threadId: Long?,
        senderId: Long?
    ): SearchChatMessagesResult {
        val result = searchChatMessages(
            chatId = chatId,
            query = query,
            fromMessageId = fromMessageId,
            limit = limit,
            filter = TdApi.SearchMessagesFilterEmpty(),
            threadId = threadId,
            senderId = senderId
        )
        if (result != null) {
            val chat = getChat(chatId)
            val lastReadInbox = chat?.lastReadInboxMessageId ?: 0L
            val lastReadOutbox = chat?.lastReadOutboxMessageId ?: 0L
            val models = result.messages.map { msg ->
                cache.putMessage(msg)
                scope.async {
                    try {
                        withTimeout(5000) { messageMapper.mapMessageToModelSync(msg, lastReadInbox, lastReadOutbox, isChatOpen = true) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e("TdMessageRemote", "Error mapping search message ${msg.id}", e)
                        createFallbackMessage(msg)
                    }
                }
            }.awaitAll()
            val nextCursor = result.nextFromMessageId.takeIf { it != 0L }
                ?: models.lastOrNull()?.id
                ?: 0L
            return SearchChatMessagesResult(
                messages = models,
                totalCount = result.totalCount,
                nextFromMessageId = if (models.size < result.totalCount) nextCursor else 0L
            )
        } else return SearchChatMessagesResult(emptyList(), 0, 0L)
    }

    private suspend fun resolveSearchTopicId(chatId: Long, threadId: Long?): TdApi.MessageTopic? {
        if (threadId == null || threadId == 0L) return null

        val chat = getChat(chatId)
        return if (chat?.viewAsTopics == true) {
            TdApi.MessageTopicForum(threadId.toInt())
        } else {
            TdApi.MessageTopicThread(threadId)
        }
    }

    private suspend fun loadMessageBatch(
        chatId: Long,
        fromMessageId: Long,
        offset: Int,
        limit: Int,
        conversationScope: ConversationScope = ConversationScope.Main,
        fetchMode: RemoteHistoryFetchMode = RemoteHistoryFetchMode.LocalThenNetwork,
        options: MessageMapOptions = MessageMapOptions()
    ): RemoteMessageBatch = withContext(dispatcherProvider.io) {
        val threadId = when (conversationScope) {
            ConversationScope.Main -> null
            is ConversationScope.ForumTopic -> conversationScope.topicId
            is ConversationScope.MessageThread -> conversationScope.threadId
        }
        ChatOpenPerfBridge.recordHistoryRequest(chatId, threadId)
        if (BuildConfig.DEBUG) {
            Log.d(
                ChatOpenPerfDebug.TAG,
                ChatOpenPerfDebug.buildLogMessage(
                    chatId = chatId,
                    threadId = threadId,
                    event = "chat_history_request_start",
                    anchorId = fromMessageId
                )
            )
        }
        var historyResult: TdApi.Object? = null
        for (onlyLocal in historyFetchAttempts(fetchMode, conversationScope)) {
            val attempt = getChatHistoryInternal(
                chatId = chatId,
                fromMessageId = fromMessageId,
                offset = offset,
                limit = limit,
                conversationScope = conversationScope,
                onlyLocal = onlyLocal
            )
            historyResult = attempt
            if (!isEmptyHistoryResult(attempt)) {
                break
            }
        }
        if (historyResult == null && fetchMode == RemoteHistoryFetchMode.LocalOnly) {
            historyResult = TdApi.Messages(0, emptyArray())
        }
        historyResult ?: throw IllegalStateException(
            "Failed to load history for chatId=$chatId fromMessageId=$fromMessageId offset=$offset limit=$limit threadId=$threadId"
        )
        val models = mapHistoryMessages(
            chatId = chatId,
            messages = when (historyResult) {
                is TdApi.Messages -> historyResult.messages.toList()
                is TdApi.MessageThreadInfo -> historyResult.messages.toList()
                else -> emptyList()
            },
            options = options
        )
        val messages = when (historyResult) {
            is TdApi.Messages -> historyResult.messages
            is TdApi.MessageThreadInfo -> historyResult.messages
            else -> emptyArray()
        }
        RemoteMessageBatch(
            rawMessages = messages.toList(),
            models = models
        )
            .also {
                if (BuildConfig.DEBUG) {
                    Log.d(
                        ChatOpenPerfDebug.TAG,
                        ChatOpenPerfDebug.buildLogMessage(
                            chatId = chatId,
                            threadId = threadId,
                            event = "chat_history_request_end",
                            anchorId = fromMessageId
                        )
                    )
                }
            }
    }

    private suspend fun mapHistoryMessages(
        chatId: Long,
        messages: List<TdApi.Message>,
        options: MessageMapOptions,
        allowChatFetch: Boolean = true
    ): List<MessageModel> = coroutineScope {
        val chat = if (allowChatFetch) getChat(chatId) else cache.getChat(chatId)
        val lastReadInbox = chat?.lastReadInboxMessageId ?: 0L
        val lastReadOutbox = chat?.lastReadOutboxMessageId ?: 0L
        messages.map { msg ->
            cache.putMessage(msg)
            async {
                try {
                    withTimeout(5000) {
                        messageMapper.mapMessageToModelSync(
                            msg = msg,
                            inboxLimit = lastReadInbox,
                            outboxLimit = lastReadOutbox,
                            isChatOpen = true,
                            options = options
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("TdMessageRemote", "Error mapping message ${msg.id}", e)
                    createFallbackMessage(msg)
                }
            }
        }.awaitAll()
    }

    private suspend fun getChatHistoryInternal(
        chatId: Long,
        fromMessageId: Long,
        offset: Int,
        limit: Int,
        conversationScope: ConversationScope = ConversationScope.Main,
        onlyLocal: Boolean = false
    ): TdApi.Object? = safeExecute(
        buildHistoryRequest(
            chatId = chatId,
            fromMessageId = fromMessageId,
            offset = offset,
            limit = limit,
            scope = conversationScope,
            onlyLocal = onlyLocal
        )
    )

    override suspend fun getChatHistory(chatId: Long, fromMessageId: Long, offset: Int, limit: Int): TdApi.Messages? {
        val req = TdApi.GetChatHistory().apply {
            this.chatId = chatId
            this.fromMessageId = fromMessageId
            this.offset = offset
            this.limit = limit
            this.onlyLocal = false
        }
        return safeExecute(req)
    }

    override suspend fun getMessageThread(chatId: Long, messageId: Long): TdApi.MessageThreadInfo? {
        return safeExecute(TdApi.GetMessageThread(chatId, messageId))
    }

    override suspend fun getMessages(chatId: Long, fromMessageId: Long, offset: Int, limit: Int, threadId: Long?): TdApi.Messages? {
        val scope = threadId?.let(ConversationScope::MessageThread) ?: ConversationScope.Main
        return when (val result =
            getChatHistoryInternal(chatId, fromMessageId, offset, limit, scope)) {
            is TdApi.Messages -> result
            is TdApi.MessageThreadInfo -> TdApi.Messages(result.messages.size, result.messages)
            else -> null
        }
    }

    override suspend fun getChatMessageByDate(chatId: Long, dateEpochSeconds: Int): MessageModel? {
        val message =
            safeExecute(TdApi.GetChatMessageByDate(chatId, dateEpochSeconds)) ?: return null
        cache.putMessage(message)

        val chat = getChat(chatId)
        val lastReadInbox = chat?.lastReadInboxMessageId ?: 0L
        val lastReadOutbox = chat?.lastReadOutboxMessageId ?: 0L

        return try {
            withTimeout(5000) {
                messageMapper.mapMessageToModelSync(
                    message,
                    lastReadInbox,
                    lastReadOutbox,
                    isChatOpen = true
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("TdMessageRemote", "Error mapping dated message ${message.id}", e)
            createFallbackMessage(message)
        }
    }

    private fun createFallbackMessage(msg: TdApi.Message): MessageModel = MessageModel(
        id = msg.id,
        date = msg.date,
        isOutgoing = msg.isOutgoing,
        senderName = "",
        chatId = msg.chatId,
        content = MessageContent.Text(""),
        senderId = 0L,
        senderAvatar = null,
        isRead = false,
        replyToMsgId = null, replyToMsg = null, forwardInfo = null, views = null, viewCount = null, mediaAlbumId = 0L,
        editDate = 0, sendingState = null, readDate = 0, reactions = emptyList(), isSenderVerified = false,
        threadId = null, replyCount = 0, canGetMessageThread = false, replyMarkup = null
    )

    override suspend fun sendMessage(
        chatId: Long,
        text: String,
        replyToMsgId: Long?,
        entities: List<MessageEntity>,
        threadId: Long?,
        sendOptions: MessageSendOptions
    ): TdApi.Message? {
        val parsedText = TdApi.FormattedText(
            text,
            entities.toTdTextEntities(text)
        )
        val content = TdApi.InputMessageText().apply {
            this.text = parsedText
            this.linkPreviewOptions = sendOptions.toTdLinkPreviewOptions()
            this.clearDraft = true
        }
        val replyTo = if (replyToMsgId != null && replyToMsgId != 0L) TdApi.InputMessageReplyToMessage(replyToMsgId, null, 0, "") else null
        val topicId = resolveTopicId(chatId, threadId)
        var lastMessage: TdApi.Message? = null
        explodeTextContent(
            content,
            tdLibLimitsRepository.limits.value.messageTextLengthMax
                ?: TdLibLimits.DEFAULT_MESSAGE_TEXT_LENGTH_MAX
        ).forEach { messageContent ->
            val req = TdApi.SendMessage().apply {
                this.chatId = chatId
                this.topicId = topicId
                this.replyTo = replyTo
                this.inputMessageContent = messageContent
                this.options = sendOptions.toTdMessageSendOptions()
            }
            lastMessage = safeExecute(req)
        }
        return lastMessage
    }

    override suspend fun retryFailedMessage(chatId: Long, temporaryMessageId: Long) {
        gateway.execute(resendTemporaryMessageRequest(chatId, temporaryMessageId))
    }

    override suspend fun sendRichMessage(
        chatId: Long,
        markdown: String,
        replyToMsgId: Long?,
        threadId: Long?,
        sendOptions: MessageSendOptions,
        isRtl: Boolean?,
        detectAutomaticBlocks: Boolean,
        parseMode: RichTextParseMode
    ): TdApi.Message? {
        val content = buildInputMessageRichMessage(
            markdown = markdown,
            parseMode = parseMode,
            isRtl = isRtl ?: shouldRenderRtl(markdown),
            detectAutomaticBlocks = detectAutomaticBlocks,
            clearDraft = true
        )
        val replyTo = if (replyToMsgId != null && replyToMsgId != 0L) {
            TdApi.InputMessageReplyToMessage(replyToMsgId, null, 0, "")
        } else {
            null
        }
        val topicId = resolveTopicId(chatId, threadId)
        val req = TdApi.SendMessage().apply {
            this.chatId = chatId
            this.topicId = topicId
            this.replyTo = replyTo
            this.inputMessageContent = content
            this.options = sendOptions.toTdMessageSendOptions()
        }
        return safeExecute(req)
    }

    override suspend fun sendPhoto(
        chatId: Long,
        photoPath: String,
        caption: String,
        captionEntities: List<MessageEntity>,
        showCaptionAboveMedia: Boolean,
        replyToMsgId: Long?,
        threadId: Long?,
        sendOptions: MessageSendOptions
    ): TdApi.Message? {
        val content = TdApi.InputMessagePhoto(
            buildInputPhoto(TdApi.InputFileLocal(photoPath)),
            TdApi.FormattedText(caption, captionEntities.toTdTextEntities(caption)),
            showCaptionAboveMedia,
            null,
            false
        )
        val replyTo = if (replyToMsgId != null && replyToMsgId != 0L) TdApi.InputMessageReplyToMessage(replyToMsgId, null, 0, "") else null
        val topicId = resolveTopicId(chatId, threadId)
        val req = TdApi.SendMessage().apply {
            this.chatId = chatId
            this.topicId = topicId
            this.replyTo = replyTo
            this.inputMessageContent = content
            this.options = sendOptions.toTdMessageSendOptions()
        }
        val response = safeExecute(req)
        if (response?.content is TdApi.MessagePhoto) {
            val fileId = (response.content as TdApi.MessagePhoto).photo.sizes.lastOrNull()?.photo?.id
            if (fileId != null) {
                registerFileForMessage(fileId, chatId, response.id)
                waitForUpload(fileId).await()
            }
        }
        return response
    }

    override suspend fun sendVideo(
        chatId: Long,
        videoPath: String,
        caption: String,
        captionEntities: List<MessageEntity>,
        showCaptionAboveMedia: Boolean,
        replyToMsgId: Long?,
        threadId: Long?,
        sendOptions: MessageSendOptions
    ): TdApi.Message? {
        val content = TdApi.InputMessageVideo(
            buildInputVideo(TdApi.InputFileLocal(videoPath)),
            TdApi.FormattedText(caption, captionEntities.toTdTextEntities(caption)),
            showCaptionAboveMedia,
            null,
            false
        )
        val replyTo = if (replyToMsgId != null && replyToMsgId != 0L) TdApi.InputMessageReplyToMessage(replyToMsgId, null, 0, "") else null
        val topicId = resolveTopicId(chatId, threadId)
        val req = TdApi.SendMessage().apply {
            this.chatId = chatId
            this.topicId = topicId
            this.replyTo = replyTo
            this.inputMessageContent = content
            this.options = sendOptions.toTdMessageSendOptions()
        }
        val response = safeExecute(req)
        if (response?.content is TdApi.MessageVideo) {
            val fileId = (response.content as TdApi.MessageVideo).video.video.id
            registerFileForMessage(fileId, chatId, response.id)
            waitForUpload(fileId).await()
        }
        return response
    }

    override suspend fun sendDocument(
        chatId: Long,
        documentPath: String,
        caption: String,
        captionEntities: List<MessageEntity>,
        replyToMsgId: Long?,
        threadId: Long?,
        sendOptions: MessageSendOptions
    ): TdApi.Message? {
        val content = TdApi.InputMessageDocument(
            buildInputDocument(TdApi.InputFileLocal(documentPath), true),
            TdApi.FormattedText(caption, captionEntities.toTdTextEntities(caption))
        )
        val replyTo = if (replyToMsgId != null && replyToMsgId != 0L) TdApi.InputMessageReplyToMessage(replyToMsgId, null, 0, "") else null
        val topicId = resolveTopicId(chatId, threadId)
        val req = TdApi.SendMessage().apply {
            this.chatId = chatId
            this.topicId = topicId
            this.replyTo = replyTo
            this.inputMessageContent = content
            this.options = sendOptions.toTdMessageSendOptions()
        }
        val response = safeExecute(req)
        if (response?.content is TdApi.MessageDocument) {
            val fileId = (response.content as TdApi.MessageDocument).document.document.id
            registerFileForMessage(fileId, chatId, response.id)
            waitForUpload(fileId).await()
        }
        return response
    }

    override suspend fun sendPoll(
        chatId: Long,
        poll: PollDraft,
        replyToMsgId: Long?,
        threadId: Long?,
        sendOptions: MessageSendOptions
    ): TdApi.Message? {
        val formattedQuestion = TdApi.FormattedText(poll.question, emptyArray())
        val pollOptions = poll.options
            .map { option ->
                buildInputPollOption(TdApi.FormattedText(option, emptyArray()))
            }
            .toTypedArray()
        val type = if (poll.isQuiz) {
            val correctOptionIds = poll.correctOptionIds
                .map { it.coerceAtLeast(0) }
                .distinct()
                .toIntArray()
            buildInputPollTypeQuiz(
                if (correctOptionIds.isNotEmpty()) correctOptionIds else intArrayOf(0),
                TdApi.FormattedText(poll.explanation.orEmpty(), emptyArray())
            )
        } else {
            TdApi.InputPollTypeRegular()
        }
        val content = TdApi.InputMessagePoll().apply {
            this.question = formattedQuestion
            this.options = pollOptions
            this.description = poll.description
                ?.takeIf { it.isNotBlank() }
                ?.let { TdApi.FormattedText(it, emptyArray()) }
            this.isAnonymous = poll.isAnonymous
            this.allowsMultipleAnswers = poll.allowsMultipleAnswers
            this.allowsRevoting = poll.allowsRevoting
            this.shuffleOptions = poll.shuffleOptions
            this.hideResultsUntilCloses = poll.hideResultsUntilCloses
            this.type = type
            this.openPeriod = poll.openPeriod.coerceAtLeast(0)
            this.closeDate = poll.closeDate.coerceAtLeast(0)
            this.isClosed = poll.isClosed
        }
        val replyTo =
            if (replyToMsgId != null && replyToMsgId != 0L) TdApi.InputMessageReplyToMessage(
                replyToMsgId,
                null,
                0,
                ""
            ) else null
        val topicId = resolveTopicId(chatId, threadId)
        val req = TdApi.SendMessage().apply {
            this.chatId = chatId
            this.topicId = topicId
            this.replyTo = replyTo
            this.inputMessageContent = content
            this.options = sendOptions.toTdMessageSendOptions()
        }
        return safeExecute(req)
    }

    override suspend fun sendSticker(chatId: Long, stickerPath: String, replyToMsgId: Long?, threadId: Long?): TdApi.Message? {
        val content = TdApi.InputMessageSticker().apply {
            sticker = buildInputSticker(TdApi.InputFileLocal(stickerPath), 512, 512)
            emoji = ""
        }
        val replyTo = if (replyToMsgId != null && replyToMsgId != 0L) TdApi.InputMessageReplyToMessage(replyToMsgId, null, 0, "") else null
        val topicId = resolveTopicId(chatId, threadId)
        val req = TdApi.SendMessage().apply {
            this.chatId = chatId
            this.topicId = topicId
            this.replyTo = replyTo
            this.inputMessageContent = content
        }
        val response = safeExecute(req)
        if (response?.content is TdApi.MessageSticker) {
            waitForUpload((response.content as TdApi.MessageSticker).sticker.sticker.id).await()
        }
        return response
    }

    override suspend fun sendGif(
        chatId: Long,
        gifId: String,
        replyToMsgId: Long?,
        threadId: Long?,
        sendOptions: MessageSendOptions
    ): TdApi.Message? {
        val content = TdApi.InputMessageAnimation(
            buildInputAnimation(TdApi.InputFileId(gifId.toInt())),
            TdApi.FormattedText("", null),
            false,
            false
        )
        val replyTo = if (replyToMsgId != null && replyToMsgId != 0L) TdApi.InputMessageReplyToMessage(replyToMsgId, null, 0, "") else null
        val topicId = resolveTopicId(chatId, threadId)
        val req = TdApi.SendMessage().apply {
            this.chatId = chatId
            this.topicId = topicId
            this.replyTo = replyTo
            this.inputMessageContent = content
            this.options = sendOptions.toTdMessageSendOptions()
        }
        return safeExecute(req)
    }

    override suspend fun sendGifFile(
        chatId: Long,
        gifPath: String,
        caption: String,
        captionEntities: List<MessageEntity>,
        showCaptionAboveMedia: Boolean,
        replyToMsgId: Long?,
        threadId: Long?,
        sendOptions: MessageSendOptions
    ): TdApi.Message? {
        val content = TdApi.InputMessageAnimation(
            buildInputAnimation(TdApi.InputFileLocal(gifPath)),
            TdApi.FormattedText(caption, captionEntities.toTdTextEntities(caption)),
            showCaptionAboveMedia,
            false
        )
        val replyTo = if (replyToMsgId != null && replyToMsgId != 0L) TdApi.InputMessageReplyToMessage(replyToMsgId, null, 0, "") else null
        val topicId = resolveTopicId(chatId, threadId)
        val req = TdApi.SendMessage().apply {
            this.chatId = chatId
            this.topicId = topicId
            this.replyTo = replyTo
            this.inputMessageContent = content
            this.options = sendOptions.toTdMessageSendOptions()
        }
        val response = safeExecute(req)
        if (response?.content is TdApi.MessageAnimation) {
            val fileId = (response.content as TdApi.MessageAnimation).animation.animation.id
            registerFileForMessage(fileId, chatId, response.id)
            waitForUpload(fileId).await()
        }
        return response
    }

    override suspend fun sendAlbum(
        chatId: Long,
        paths: List<String>,
        caption: String,
        captionEntities: List<MessageEntity>,
        showCaptionAboveMedia: Boolean,
        replyToMsgId: Long?,
        threadId: Long?,
        sendOptions: MessageSendOptions
    ): TdApi.Messages? {
        val inputMessageContents = paths.mapIndexed { index, path ->
            val cap = if (index == 0) TdApi.FormattedText(
                caption,
                captionEntities.toTdTextEntities(caption)
            ) else null
            if (sendOptions.sendAsDocument) {
                TdApi.InputMessageDocument(
                    buildInputDocument(TdApi.InputFileLocal(path), true),
                    cap
                )
            } else {
                val isVideo = path.endsWith(".mp4", ignoreCase = true)
                if (isVideo) TdApi.InputMessageVideo(
                    buildInputVideo(TdApi.InputFileLocal(path)),
                    cap,
                    index == 0 && showCaptionAboveMedia,
                    null,
                    false
                )
                else TdApi.InputMessagePhoto(
                    buildInputPhoto(TdApi.InputFileLocal(path)),
                    cap,
                    index == 0 && showCaptionAboveMedia,
                    null,
                    false
                )
            }
        }.toTypedArray()
        val replyTo = if (replyToMsgId != null && replyToMsgId != 0L) TdApi.InputMessageReplyToMessage(replyToMsgId, null, 0, "") else null
        val topicId = resolveTopicId(chatId, threadId)
        val req = TdApi.SendMessageAlbum().apply {
            this.chatId = chatId
            this.topicId = topicId
            this.replyTo = replyTo
            this.inputMessageContents = inputMessageContents
            this.options = sendOptions.toTdMessageSendOptions()
        }
        val result = safeExecute(req)
        result?.messages?.forEach { msg ->
            val fileId = when (val c = msg.content) {
                is TdApi.MessageDocument -> c.document.document.id
                is TdApi.MessagePhoto -> c.photo.sizes.lastOrNull()?.photo?.id
                is TdApi.MessageVideo -> c.video.video.id
                else -> null
            }
            if (fileId != null) {
                registerFileForMessage(fileId, chatId, msg.id)
                waitForUpload(fileId).await()
            }
        }
        return result
    }

    override suspend fun sendVideoNote(
        chatId: Long,
        videoPath: String,
        duration: Int,
        length: Int,
        replyToMsgId: Long?,
        threadId: Long?
    ): TdApi.Message? {
        val content = TdApi.InputMessageVideoNote().apply {
            videoNote = buildInputVideoNote(TdApi.InputFileLocal(videoPath), duration, length)
        }
        val replyTo = if (replyToMsgId != null && replyToMsgId != 0L) TdApi.InputMessageReplyToMessage(replyToMsgId, null, 0, "") else null
        val topicId = resolveTopicId(chatId, threadId)
        val req = TdApi.SendMessage().apply {
            this.chatId = chatId
            this.topicId = topicId
            this.replyTo = replyTo
            this.inputMessageContent = content
        }
        val response = safeExecute(req)
        if (response?.content is TdApi.MessageVideoNote) waitForUpload((response.content as TdApi.MessageVideoNote).videoNote.video.id).await()
        return response
    }

    override suspend fun sendVoiceNote(
        chatId: Long,
        voicePath: String,
        duration: Int,
        waveform: ByteArray,
        replyToMsgId: Long?,
        threadId: Long?
    ): TdApi.Message? {
        val content = TdApi.InputMessageVoiceNote().apply {
            voiceNote = buildInputVoiceNote(TdApi.InputFileLocal(voicePath), duration, waveform)
        }
        val replyTo = if (replyToMsgId != null && replyToMsgId != 0L) TdApi.InputMessageReplyToMessage(replyToMsgId, null, 0, "") else null
        val topicId = resolveTopicId(chatId, threadId)
        val req = TdApi.SendMessage().apply {
            this.chatId = chatId
            this.topicId = topicId
            this.replyTo = replyTo
            this.inputMessageContent = content
        }
        val response = safeExecute(req)
        if (response?.content is TdApi.MessageVoiceNote) waitForUpload((response.content as TdApi.MessageVoiceNote).voiceNote.voice.id).await()
        return response
    }

    override suspend fun forwardMessages(
        toChatId: Long,
        fromChatId: Long,
        messageIds: LongArray,
        forumTopicId: Int?,
        removeCaption: Boolean,
        sendCopy: Boolean
    ): TdApi.Messages? {
        val options = TdApi.MessageSendOptions().apply {
            this.disableNotification = false
            this.fromBackground = false
        }
        val req = TdApi.ForwardMessages().apply {
            this.chatId = toChatId
            this.topicId = forumTopicId?.let { TdApi.MessageTopicForum(it) }
            this.fromChatId = fromChatId
            this.messageIds = messageIds.sortedArray()
            this.options = options
            this.removeCaption = removeCaption
            this.sendCopy = sendCopy
        }
        return safeExecute(req)
    }

    override suspend fun deleteMessages(chatId: Long, messageIds: LongArray, revoke: Boolean): TdApi.Ok? {
        val req = TdApi.DeleteMessages().apply {
            this.chatId = chatId
            this.messageIds = messageIds
            this.revoke = revoke
        }
        return safeExecute(req)
    }

    override suspend fun editMessageText(chatId: Long, messageId: Long, text: String, entities: List<MessageEntity>): TdApi.Message? {
        val parsedText = TdApi.FormattedText(
            text,
            entities.toTdTextEntities(text)
        )
        val content = TdApi.InputMessageText().apply {
            this.text = parsedText
            this.linkPreviewOptions = TdApi.LinkPreviewOptions(false, "", false, false, false)
        }
        val req = TdApi.EditMessageText().apply {
            this.chatId = chatId
            this.messageId = messageId
            this.inputMessageContent = content
        }
        return safeExecute(req)
    }

    override suspend fun editRichMessage(
        chatId: Long,
        messageId: Long,
        markdown: String,
        isRtl: Boolean?,
        detectAutomaticBlocks: Boolean,
        parseMode: RichTextParseMode
    ): TdApi.Message? {
        val content = buildInputMessageRichMessage(
            markdown = markdown,
            parseMode = parseMode,
            isRtl = isRtl ?: shouldRenderRtl(markdown),
            detectAutomaticBlocks = detectAutomaticBlocks,
            clearDraft = false
        )
        val req = TdApi.EditMessageText(
            chatId,
            messageId,
            null,
            content
        )
        return safeExecute(req)
    }

    override suspend fun getFullRichMessage(chatId: Long, messageId: Long): TdApi.RichMessage? {
        return safeExecute(TdApi.GetFullRichMessage(chatId, messageId))
    }

    override suspend fun editMessageCaption(chatId: Long, messageId: Long, caption: String, entities: List<MessageEntity>): TdApi.Message? {
        val showCaptionAboveMedia = resolveShowCaptionAboveMedia(chatId, messageId)
        val req = TdApi.EditMessageCaption().apply {
            this.chatId = chatId
            this.messageId = messageId
            this.replyMarkup = null
            this.caption = TdApi.FormattedText(
                caption,
                entities.toTdTextEntities(caption)
            )
            this.showCaptionAboveMedia = showCaptionAboveMedia
        }
        return safeExecute(req)
    }

    override suspend fun sendChecklist(
        chatId: Long,
        checklistDraft: ChecklistDraft,
        replyToMsgId: Long?,
        threadId: Long?,
        sendOptions: MessageSendOptions
    ): TdApi.Message? {
        val content = TdApi.InputMessageChecklist(buildInputChecklist(checklistDraft))
        val replyTo =
            if (replyToMsgId != null && replyToMsgId != 0L) TdApi.InputMessageReplyToMessage(
                replyToMsgId,
                null,
                0,
                ""
            ) else null
        val topicId = resolveTopicId(chatId, threadId)
        val req = TdApi.SendMessage().apply {
            this.chatId = chatId
            this.topicId = topicId
            this.replyTo = replyTo
            this.inputMessageContent = content
            this.options = sendOptions.toTdMessageSendOptions()
        }
        return safeExecute(req)
    }

    override suspend fun editChecklistMessage(
        chatId: Long,
        messageId: Long,
        checklistDraft: ChecklistDraft
    ): TdApi.Message? {
        val req =
            TdApi.EditMessageChecklist(chatId, messageId, null, buildInputChecklist(checklistDraft))
        return safeExecute(req)
    }

    private fun buildInputChecklist(checklistDraft: ChecklistDraft): TdApi.InputChecklist {
        return TdApi.InputChecklist(
            TdApi.FormattedText(
                checklistDraft.title,
                checklistDraft.titleEntities.toTdTextEntities(checklistDraft.title)
            ),
            checklistDraft.tasks.map { task ->
                TdApi.InputChecklistTask(
                    task.id,
                    TdApi.FormattedText(
                        task.text,
                        task.entities.toTdTextEntities(task.text)
                    )
                )
            }.toTypedArray(),
            checklistDraft.othersCanAddTasks,
            checklistDraft.othersCanMarkTasksAsDone
        )
    }

    private fun buildInputMessageRichMessage(
        markdown: String,
        parseMode: RichTextParseMode,
        isRtl: Boolean,
        detectAutomaticBlocks: Boolean,
        clearDraft: Boolean
    ): TdApi.InputMessageRichMessage {
        val source = when (parseMode) {
            RichTextParseMode.Markdown -> buildRichMessageSourceMarkdown(markdown)
            RichTextParseMode.Html -> buildRichMessageSourceHtml(markdown)
        }
        return TdApi.InputMessageRichMessage(
            TdApi.InputRichMessage(source, isRtl, detectAutomaticBlocks),
            clearDraft
        )
    }

    private fun shouldRenderRtl(text: String): Boolean {
        val directionalChars = text.asSequence()
            .filter { Character.isLetter(it) }
            .take(64)
            .toList()
        if (directionalChars.isEmpty()) return false
        val rtlCount = directionalChars.count { char ->
            when (Character.getDirectionality(char)) {
                Character.DIRECTIONALITY_RIGHT_TO_LEFT,
                Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC,
                Character.DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING,
                Character.DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE -> true

                else -> false
            }
        }
        return rtlCount > directionalChars.size / 2
    }

    override suspend fun markChecklistTasksAsDone(
        chatId: Long,
        messageId: Long,
        doneIds: IntArray,
        undoneIds: IntArray
    ): TdApi.Ok? {
        return safeExecute(TdApi.MarkChecklistTasksAsDone(chatId, messageId, doneIds, undoneIds))
    }

    private fun List<MessageEntity>.toTdTextEntities(text: String): Array<TdApi.TextEntity> {
        if (isEmpty()) return emptyArray()

        return this
            .mapNotNull { it.toTdTextEntity(text) }
            .sortedWith(compareBy<TdApi.TextEntity> { it.offset }.thenByDescending { it.length })
            .toTypedArray()
    }

    private fun MessageEntity.toTdTextEntity(text: String): TdApi.TextEntity? {
        val start = offset.coerceIn(0, text.length)
        val end = (offset + length).coerceIn(0, text.length)
        val safeLength = end - start
        if (safeLength <= 0) return null

        val tdType: TdApi.TextEntityType = when (val value = type) {
            is MessageEntityType.Bold -> TdApi.TextEntityTypeBold()
            is MessageEntityType.Italic -> TdApi.TextEntityTypeItalic()
            is MessageEntityType.Underline -> TdApi.TextEntityTypeUnderline()
            is MessageEntityType.Strikethrough -> TdApi.TextEntityTypeStrikethrough()
            is MessageEntityType.Spoiler -> TdApi.TextEntityTypeSpoiler()
            is MessageEntityType.Code -> TdApi.TextEntityTypeCode()
            is MessageEntityType.BlockQuote -> TdApi.TextEntityTypeBlockQuote()
            is MessageEntityType.BlockQuoteExpandable -> TdApi.TextEntityTypeExpandableBlockQuote()
            is MessageEntityType.Pre -> {
                if (value.language.isBlank()) TdApi.TextEntityTypePre()
                else TdApi.TextEntityTypePreCode(value.language)
            }

            is MessageEntityType.TextUrl -> TdApi.TextEntityTypeTextUrl(value.url)
            is MessageEntityType.Mention -> TdApi.TextEntityTypeMention()
            is MessageEntityType.TextMention -> TdApi.TextEntityTypeMentionName(value.userId)
            is MessageEntityType.Hashtag -> TdApi.TextEntityTypeHashtag()
            is MessageEntityType.Cashtag -> TdApi.TextEntityTypeCashtag()
            is MessageEntityType.BotCommand -> TdApi.TextEntityTypeBotCommand()
            is MessageEntityType.Url -> TdApi.TextEntityTypeUrl()
            is MessageEntityType.Email -> TdApi.TextEntityTypeEmailAddress()
            is MessageEntityType.PhoneNumber -> TdApi.TextEntityTypePhoneNumber()
            is MessageEntityType.BankCardNumber -> TdApi.TextEntityTypeBankCardNumber()
            is MessageEntityType.DateTime -> TdApi.TextEntityTypeDateTime(value.unixTime, null)
            is MessageEntityType.MediaTimestamp -> TdApi.TextEntityTypeMediaTimestamp(value.mediaTimestampSeconds)
            is MessageEntityType.CustomEmoji -> TdApi.TextEntityTypeCustomEmoji(value.emojiId)
            is MessageEntityType.Other -> return null
        }

        return TdApi.TextEntity(start, safeLength, tdType)
    }

    private fun explodeTextContent(
        content: TdApi.InputMessageText,
        maxCodePointCount: Int
    ): List<TdApi.InputMessageText> {
        val formattedText = content.text ?: return listOf(content)
        if (formattedText.text.codePointCount(0, formattedText.text.length) <= maxCodePointCount) {
            return listOf(content)
        }

        val chunks = mutableListOf<TdApi.InputMessageText>()
        val text = formattedText.text
        val textLength = text.length
        var start = 0
        var end = 0
        var currentCodePointCount = 0

        while (start < textLength) {
            val codePoint = text.codePointAt(end)
            currentCodePointCount++
            end += Character.charCount(codePoint)
            if (currentCodePointCount == maxCodePointCount || end == textLength) {
                var chunkEnd = end
                if (chunkEnd < textLength) {
                    chunkEnd = findChunkBoundary(text, start, chunkEnd)
                    if (chunkEnd <= start) {
                        chunkEnd = end
                    }
                }
                val chunkText = formattedText.substring(start, chunkEnd)
                val isFirstChunk = chunks.isEmpty()
                chunks += TdApi.InputMessageText().apply {
                    this.text = chunkText
                    this.linkPreviewOptions = content.linkPreviewOptions
                    this.clearDraft = isFirstChunk && content.clearDraft
                }
                start = chunkEnd
                end = chunkEnd
                currentCodePointCount = 0
            }
        }

        return chunks
    }

    private fun TdApi.FormattedText.substring(start: Int, end: Int): TdApi.FormattedText {
        val chunkText = text.substring(start, end)
        val chunkEntities = entities.orEmpty()
            .mapNotNull { entity ->
                val entityStart = maxOf(entity.offset, start)
                val entityEnd = minOf(entity.offset + entity.length, end)
                if (entityEnd <= entityStart) return@mapNotNull null
                TdApi.TextEntity(
                    entityStart - start,
                    entityEnd - entityStart,
                    entity.type
                )
            }
            .sortedWith(compareBy<TdApi.TextEntity> { it.offset }.thenByDescending { it.length })
            .toTypedArray()
        return TdApi.FormattedText(chunkText, chunkEntities)
    }

    private fun findChunkBoundary(text: String, start: Int, candidateEnd: Int): Int {
        val searchStart = candidateEnd - (candidateEnd - start) / 3
        var lastWhitespace = -1
        var lastSplitter = -1
        for (index in (candidateEnd - 1) downTo searchStart) {
            val char = text[index]
            if (char == '\n' && index > start) return index
            if (lastWhitespace == -1 && char.isWhitespace()) {
                lastWhitespace = index
            }
            if (lastSplitter == -1 && isChunkSplitter(char)) {
                lastSplitter = index
            }
        }
        return when {
            lastWhitespace > start -> lastWhitespace
            lastSplitter > start -> lastSplitter
            else -> candidateEnd
        }
    }

    private fun isChunkSplitter(char: Char): Boolean {
        return char in charArrayOf('.', ',', '!', '?', ';', ':', ')', ']', '}', '/', '\\', '-', '>')
    }

    private fun resolveShowCaptionAboveMedia(chatId: Long, messageId: Long): Boolean {
        val content = cache.getMessage(chatId, messageId)?.content ?: return false
        return when (content) {
            is TdApi.MessagePhoto -> content.showCaptionAboveMedia
            is TdApi.MessageVideo -> content.showCaptionAboveMedia
            is TdApi.MessageAnimation -> content.showCaptionAboveMedia
            else -> false
        }
    }

    private fun applyReactionDelta(
        existing: TdApi.MessageReactions?,
        actorId: TdApi.MessageSender?,
        oldReactionTypes: Array<out TdApi.ReactionType>,
        newReactionTypes: Array<out TdApi.ReactionType>
    ): TdApi.MessageReactions {
        val mutableReactions = existing?.reactions.orEmpty()
            .map { reaction ->
                TdApi.MessageReaction().apply {
                    type = reaction.type
                    totalCount = reaction.totalCount
                    isChosen = reaction.isChosen
                    usedSenderId = reaction.usedSenderId
                    recentSenderIds = reaction.recentSenderIds?.clone() ?: emptyArray()
                }
            }
            .associateByTo(linkedMapOf(), ::reactionKey)

        oldReactionTypes.forEach { reactionType ->
            val key = reactionKey(reactionType)
            val current = mutableReactions[key] ?: return@forEach
            current.totalCount = (current.totalCount - 1).coerceAtLeast(0)
            if (actorId != null) {
                current.recentSenderIds = current.recentSenderIds.orEmpty()
                    .filterNot { sameSender(it, actorId) }
                    .toTypedArray()
            }
            if (current.totalCount == 0) {
                mutableReactions.remove(key)
            }
        }

        newReactionTypes.forEach { reactionType ->
            val key = reactionKey(reactionType)
            val current = mutableReactions.getOrPut(key) {
                TdApi.MessageReaction().apply {
                    type = reactionType
                    totalCount = 0
                    isChosen = false
                    usedSenderId = null
                    recentSenderIds = emptyArray()
                }
            }
            current.totalCount += 1
            if (actorId != null) {
                current.recentSenderIds = (
                        listOf(actorId) + current.recentSenderIds.orEmpty()
                            .filterNot { sameSender(it, actorId) }
                            .take(3)
                        ).toTypedArray()
            }
        }

        return TdApi.MessageReactions().apply {
            reactions = mutableReactions.values.toTypedArray()
            areTags = existing?.areTags ?: false
            paidReactors = existing?.paidReactors ?: emptyArray()
            canGetAddedReactions = existing?.canGetAddedReactions ?: false
        }
    }

    private fun reactionKey(reaction: TdApi.MessageReaction): String = reactionKey(reaction.type)

    private fun reactionKey(reaction: TdApi.ReactionType): String = when (reaction) {
        is TdApi.ReactionTypeEmoji -> "emoji:${reaction.emoji}"
        is TdApi.ReactionTypeCustomEmoji -> "custom:${reaction.customEmojiId}"
        is TdApi.ReactionTypePaid -> "paid"
        else -> reaction.javaClass.simpleName
    }

    private fun sameSender(first: TdApi.MessageSender, second: TdApi.MessageSender): Boolean {
        return when {
            first is TdApi.MessageSenderUser && second is TdApi.MessageSenderUser -> first.userId == second.userId
            first is TdApi.MessageSenderChat && second is TdApi.MessageSenderChat -> first.chatId == second.chatId
            else -> false
        }
    }

    private suspend fun resolveTopicId(chatId: Long, threadId: Long?): TdApi.MessageTopic? {
        if (threadId == null || threadId == 0L) return null
        val chat = cache.getChat(chatId) ?: getChat(chatId)
        return if (chat?.viewAsTopics == true) {
            TdApi.MessageTopicForum(threadId.toInt())
        } else {
            TdApi.MessageTopicThread(threadId)
        }
    }

    private fun MessageSendOptions.toTdMessageSendOptions(): TdApi.MessageSendOptions {
        return TdApi.MessageSendOptions().apply {
            this.disableNotification = silent
            this.fromBackground = false
            this.schedulingState = scheduleDate
                ?.takeIf { it > 0 }
                ?.let { TdApi.MessageSchedulingStateSendAtDate(it, 0) }
        }
    }

    private fun MessageSendOptions.toTdLinkPreviewOptions(): TdApi.LinkPreviewOptions? {
        if (!disableLinkPreview && linkPreviewUrl.isNullOrBlank()) return null
        return TdApi.LinkPreviewOptions(
            disableLinkPreview,
            linkPreviewUrl.orEmpty(),
            false,
            false,
            false
        )
    }

    override suspend fun viewMessages(chatId: Long, messageIds: LongArray, forceRead: Boolean): TdApi.Ok? {
        val req = TdApi.ViewMessages().apply {
            this.chatId = chatId
            this.messageIds = messageIds
            this.forceRead = forceRead
        }
        return safeExecute(req)
    }

    override suspend fun readAllChatMentions(chatId: Long): TdApi.Ok? = safeExecute(TdApi.ReadAllChatMentions(chatId))
    override suspend fun readAllChatReactions(chatId: Long): TdApi.Ok? = safeExecute(TdApi.ReadAllChatReactions(chatId))
    override suspend fun setChatDraftMessage(chatId: Long, messageThreadId: Long, draftMessage: TdApi.DraftMessage?): TdApi.Ok? {
        val req = TdApi.SetChatDraftMessage().apply {
            this.chatId = chatId
            this.draftMessage = draftMessage
        }
        return safeExecute(req)
    }

    override suspend fun pinChatMessage(chatId: Long, messageId: Long, disableNotification: Boolean, onlyForSelf: Boolean): TdApi.Ok? {
        val req = TdApi.PinChatMessage().apply {
            this.chatId = chatId
            this.messageId = messageId
            this.disableNotification = disableNotification
            this.onlyForSelf = onlyForSelf
        }
        return safeExecute(req)
    }

    override suspend fun unpinChatMessage(chatId: Long, messageId: Long): TdApi.Ok? {
        val req = TdApi.UnpinChatMessage().apply {
            this.chatId = chatId
            this.messageId = messageId
        }
        return safeExecute(req)
    }

    override suspend fun addMessageReaction(chatId: Long, messageId: Long, reaction: String): TdApi.Ok? {
        val reactionType = if (reaction.all { it.isDigit() }) {
            TdApi.ReactionTypeCustomEmoji(reaction.toLong())
        } else {
            TdApi.ReactionTypeEmoji(reaction)
        }

        val request = TdApi.AddMessageReaction().apply {
            this.chatId = chatId
            this.messageId = messageId
            this.reactionType = reactionType
            this.isBig = false
            this.updateRecentReactions = true
        }
        return safeExecute(request)
    }

    override suspend fun removeMessageReaction(chatId: Long, messageId: Long, reaction: String): TdApi.Ok? {
        val reactionType = if (reaction.all { it.isDigit() }) {
            TdApi.ReactionTypeCustomEmoji(reaction.toLong())
        } else {
            TdApi.ReactionTypeEmoji(reaction)
        }

        val request = TdApi.RemoveMessageReaction().apply {
            this.chatId = chatId
            this.messageId = messageId
            this.reactionType = reactionType
        }
        return safeExecute(request)
    }

    override suspend fun setPollAnswer(chatId: Long, messageId: Long, optionIds: IntArray): TdApi.Ok? {
        val req = TdApi.SetPollAnswer().apply {
            this.chatId = chatId
            this.messageId = messageId
            this.optionIds = optionIds
        }
        return safeExecute(req)
    }

    override suspend fun stopPoll(chatId: Long, messageId: Long): TdApi.Poll? {
        val req = TdApi.StopPoll().apply {
            this.chatId = chatId
            this.messageId = messageId
        }
        return safeExecute(req) as TdApi.Poll?
    }

    override suspend fun sendChatAction(chatId: Long, messageThreadId: Long, action: TdApi.ChatAction): TdApi.Ok? {
        val req = TdApi.SendChatAction().apply {
            this.chatId = chatId
            this.action = action
            this.topicId = resolveTopicId(chatId, messageThreadId.takeIf { it != 0L })
        }
        return safeExecute(req)
    }

    override suspend fun getCallbackQueryAnswer(chatId: Long, messageId: Long, payload: TdApi.CallbackQueryPayload): TdApi.CallbackQueryAnswer? {
        val req = TdApi.GetCallbackQueryAnswer().apply {
            this.chatId = chatId
            this.messageId = messageId
            this.payload = payload
        }
        return safeExecute(req)
    }

    override suspend fun openWebApp(
        chatId: Long,
        botUserId: Long,
        url: String,
        theme: ThemeParams?
    ): WebAppInfoModel? {
        val parameters = buildDefaultWebAppOpenParameters(theme)

        val isMenuUrl = url.startsWith("menu://")
        val normalizedUrl = if (isMenuUrl) url.removePrefix("menu://") else url
        val botPrivateChatId = if (isMenuUrl) {
            try {
                gateway.execute(TdApi.CreatePrivateChat(botUserId, false))?.id
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }

        val attempts = linkedSetOf<Pair<Long, String>>().apply {
            add(chatId to url)
            if (normalizedUrl != url) add(chatId to normalizedUrl)
            if (botPrivateChatId != null && botPrivateChatId != chatId) {
                add(botPrivateChatId to url)
                if (normalizedUrl != url) add(botPrivateChatId to normalizedUrl)
            }
        }

        var lastError: Throwable? = null

        for ((targetChatId, targetUrl) in attempts) {
            try {
                val resolvedUrl = targetUrl.removePrefix("menu://")
                val result = gateway.execute(
                    TdApi.OpenWebApp(targetChatId, botUserId, resolvedUrl, null, null, parameters)
                )
                if (result is TdApi.WebAppInfo) {
                    return WebAppInfoModel(result.launchId, result.url.url)
                }
            } catch (e: TdLibException) {
                lastError = e
            } catch (e: Exception) {
                lastError = e
            }
        }

        if (lastError != null) {
            Log.e("TdMessageRemote", "Error executing OpenWebApp", lastError)
        }

        return null
    }

    override suspend fun onCallbackQueryBuy(chatId: Long, messageId: Long) {
        val request = TdApi.GetPaymentForm().apply {
            this.inputInvoice = TdApi.InputInvoiceMessage(chatId, messageId)
        }
        val result = safeExecute(request)
        Log.d("MessageActionApi", "GetPaymentForm result: $result")
    }

    override suspend fun onCallbackQuery(chatId: Long, messageId: Long, data: ByteArray) {
        val request = TdApi.GetCallbackQueryAnswer().apply {
            this.chatId = chatId
            this.messageId = messageId
            this.payload = TdApi.CallbackQueryPayloadData(data)
        }
        safeExecute(request)
    }

    override suspend fun sendWebAppResult(botUserId: Long, data: String, buttonText: String) {
        val request = TdApi.SendWebAppData(botUserId, buttonText, data)
        safeExecute(request)
    }

    override suspend fun closeWebApp(webAppLaunchId: Long): TdApi.Ok? {
        val req = TdApi.CloseWebApp().apply {
            this.webAppLaunchId = webAppLaunchId
        }
        return safeExecute(req)
    }

    override suspend fun getPaymentForm(inputInvoice: TdApi.InputInvoice): TdApi.PaymentForm? {
        val req = TdApi.GetPaymentForm().apply {
            this.inputInvoice = inputInvoice
            this.theme = TdApi.ThemeParameters()
        }
        return safeExecute(req)
    }

    override suspend fun sendWebAppData(botUserId: Long, buttonText: String, data: String): TdApi.Ok? {
        val req = TdApi.SendWebAppData().apply {
            this.botUserId = botUserId
            this.buttonText = buttonText
            this.data = data
        }
        return safeExecute(req)
    }

    override suspend fun handleUpdate(update: TdApi.Update) {
        try {
            processUpdate(update)
        } catch (e: Exception) {
            Log.e("TdMessageRemote", "CRASH in handleUpdate: ${e.message}", e)
        }
    }

    override suspend fun markMessagesAsRead(chatId: Long, messageIds: LongArray, threadId: Long?) {
        val distinctMessageIds = messageIds.distinct().toLongArray()
        if (distinctMessageIds.isEmpty()) return
        val source = when {
            threadId == null -> TdApi.MessageSourceChatHistory()
            else -> TdApi.MessageSourceMessageThreadHistory()
        }
        safeExecute(
            TdApi.ViewMessages(
                chatId,
                distinctMessageIds,
                source,
                false
            )
        )
    }

    private fun isEmptyHistoryResult(result: TdApi.Object?): Boolean = when (result) {
        null -> true
        is TdApi.Messages -> result.messages.isEmpty()
        is TdApi.MessageThreadInfo -> result.messages.isEmpty()
        else -> false
    }

    override suspend fun markAllMentionsAsRead(chatId: Long) {
        safeExecute(TdApi.ReadAllChatMentions(chatId))
    }

    override suspend fun markAllReactionsAsRead(chatId: Long) {
        safeExecute(TdApi.ReadAllChatReactions(chatId))
    }

    override suspend fun pinMessage(chatId: Long, messageId: Long, disableNotification: Boolean) {
        val request = TdApi.PinChatMessage().apply {
            this.chatId = chatId
            this.messageId = messageId
            this.disableNotification = disableNotification
            this.onlyForSelf = false
        }
        safeExecute(request)
    }

    override suspend fun unpinMessage(chatId: Long, messageId: Long) {
        val request = TdApi.UnpinChatMessage().apply {
            this.chatId = chatId
            this.messageId = messageId
        }
        safeExecute(request)
    }

    override suspend fun saveChatDraft(chatId: Long, draft: TdApi.DraftMessage?, replyToMsgId: Long?, threadId: Long?) {
        val request = TdApi.SetChatDraftMessage().apply {
            this.chatId = chatId
            this.draftMessage = draft
            this.topicId = resolveTopicId(chatId, threadId)
        }
        safeExecute(request)
    }

    override suspend fun getChatDraft(chatId: Long, threadId: Long?): String? {
        if (threadId != null && threadId != 0L) {
            val result = safeExecute(TdApi.GetForumTopic(chatId, threadId.toInt()))
            if (result is TdApi.ForumTopic) {
                val draft = result.draftMessage
                if (draft != null) {
                    return draft.extractTextDraft()
                } else {
                    return null
                }
            } else {
                return null
            }
        } else {
            val cachedChat = cache.getChat(chatId)
            if (cachedChat != null) {
                val draft = cachedChat.draftMessage
                if (draft != null) {
                    return draft.extractTextDraft()
                }
            }

            val result = safeExecute(TdApi.GetChat(chatId))
            if (result is TdApi.Chat) {
                cache.putChat(result)
                val draft = result.draftMessage
                if (draft != null) {
                    return draft.extractTextDraft()
                } else {
                    return null
                }
            } else {
                return null
            }
        }
    }


    private suspend fun processUpdate(update: TdApi.Update) {
        when (update) {
            is TdApi.UpdateNewMessage -> {
                val message = update.message
                cache.putMessage(message)
                if (message.content is TdApi.MessagePoll) {
                    val poll = (message.content as TdApi.MessagePoll).poll
                    pollRepository.mapPollIdToMessage(poll.id, message.chatId, message.id)
                }
                if (isChatOpen(message.chatId)) {
                    try {
                        newMessages.enqueue(mapLiveMessageToModel(message))
                    } catch (e: Exception) {
                        Log.e("TdMessageRemote", "Error mapping NewMessage", e)
                    }
                }
            }
            is TdApi.UpdateMessageSendSucceeded -> {
                val message = update.message
                cache.removeMessage(message.chatId, update.oldMessageId)
                cache.putMessage(message)
                updateMessageIdInCache(message.chatId, update.oldMessageId, message.id)
                if (message.content is TdApi.MessagePoll) {
                    val poll = (message.content as TdApi.MessagePoll).poll
                    pollRepository.mapPollIdToMessage(poll.id, message.chatId, message.id)
                }
                if (isChatOpen(message.chatId)) {
                    try {
                        val model = mapLiveMessageToModel(message)
                        messageIdUpdates.enqueue(
                            MessageIdUpdatedEvent(
                                chatId = message.chatId,
                                oldMessageId = update.oldMessageId,
                                message = model
                            )
                        )
                    } catch (e: Exception) {
                        Log.e("TdMessageRemote", "Error handling SendSucceeded", e)
                    }
                }
            }

            is TdApi.UpdateMessageSendAcknowledged -> {
                messageAcknowledgements.enqueue(
                    MessageSendAcknowledgedEvent(
                        chatId = update.chatId,
                        temporaryMessageId = update.messageId
                    )
                )
            }
            is TdApi.UpdateMessageSendFailed -> {
                cache.putMessage(update.message)
                if (isChatOpen(update.message.chatId)) {
                    val model = mapLiveMessageToModel(update.message)
                    messageSendFailures.enqueue(
                        MessageSendFailedEvent(
                            chatId = update.message.chatId,
                            temporaryMessageId = update.oldMessageId,
                            message = model,
                            errorCode = update.error?.code ?: 0
                        )
                    )
                    messageEdits.enqueue(model)
                }
            }
            is TdApi.UpdateMessageContent -> {
                if (update.newContent is TdApi.MessagePoll) {
                    val poll = (update.newContent as TdApi.MessagePoll).poll
                    pollRepository.mapPollIdToMessage(poll.id, update.chatId, update.messageId)
                }
                cache.updateMessage(update.chatId, update.messageId) { message ->
                    message.content = update.newContent
                }
                refreshMessageDebounced(update.chatId, update.messageId)
            }
            is TdApi.UpdateMessageEdited -> {
                cache.updateMessage(update.chatId, update.messageId) { message ->
                    message.editDate = update.editDate
                    message.replyMarkup = update.replyMarkup
                }
                refreshMessageDebounced(update.chatId, update.messageId)
            }
            is TdApi.UpdateMessageInteractionInfo -> {
                cache.updateMessage(update.chatId, update.messageId) { message ->
                    message.interactionInfo = update.interactionInfo
                }
                refreshMessageDebounced(update.chatId, update.messageId)
            }
            is TdApi.UpdateMessageReaction -> {
                cache.updateMessage(update.chatId, update.messageId) { message ->
                    val interactionInfo = message.interactionInfo ?: TdApi.MessageInteractionInfo()
                    interactionInfo.reactions = applyReactionDelta(
                        existing = interactionInfo.reactions,
                        actorId = update.actorId,
                        oldReactionTypes = update.oldReactionTypes.orEmpty(),
                        newReactionTypes = update.newReactionTypes.orEmpty()
                    )
                    message.interactionInfo = interactionInfo
                }
                refreshMessageDebounced(update.chatId, update.messageId)
            }
            is TdApi.UpdateMessageReactions -> {
                cache.updateMessage(update.chatId, update.messageId) { message ->
                    val interactionInfo = message.interactionInfo ?: TdApi.MessageInteractionInfo()
                    val existingReactions = interactionInfo.reactions
                    interactionInfo.reactions = TdApi.MessageReactions().apply {
                        reactions = update.reactions
                        areTags = existingReactions?.areTags ?: false
                        paidReactors = existingReactions?.paidReactors ?: emptyArray()
                        canGetAddedReactions = existingReactions?.canGetAddedReactions ?: false
                    }
                    message.interactionInfo = interactionInfo
                }
                refreshMessageDebounced(update.chatId, update.messageId)
            }
            is TdApi.UpdateMessageMentionRead -> {
                cache.updateMessage(update.chatId, update.messageId) { message ->
                    message.containsUnreadMention = false
                }
                cache.updateChat(update.chatId) { it.unreadMentionCount = update.unreadMentionCount }
                refreshMessageDebounced(update.chatId, update.messageId)
            }

            is TdApi.UpdateMessageUnreadReactions -> {
                cache.updateMessage(update.chatId, update.messageId) { message ->
                    message.unreadReactions = update.unreadReactions
                }
                cache.updateChat(update.chatId) {
                    it.unreadReactionCount = update.unreadReactionCount
                }
                refreshMessageDebounced(update.chatId, update.messageId)
            }

            is TdApi.UpdateMessageFactCheck -> {
                cache.updateMessage(update.chatId, update.messageId) { message ->
                    message.factCheck = update.factCheck
                }
                refreshMessageDebounced(update.chatId, update.messageId)
            }

            is TdApi.UpdateMessageSuggestedPostInfo -> {
                cache.updateMessage(update.chatId, update.messageId) { message ->
                    message.suggestedPostInfo = update.suggestedPostInfo
                }
                refreshMessageDebounced(update.chatId, update.messageId)
            }

            is TdApi.UpdateMessageIsPinned -> {
                cache.updateMessage(update.chatId, update.messageId) { message ->
                    message.isPinned = update.isPinned
                }
                pinnedMessageFlow.emit(update.chatId)
            }

            is TdApi.UpdateMessageContainsUnreadPollVotes -> {
                cache.updateMessage(update.chatId, update.messageId) { message ->
                    message.containsUnreadPollVotes = update.containsUnreadPollVotes
                }
                refreshMessageDebounced(update.chatId, update.messageId)
            }
            // FileUpdateHandler owns the single UpdateFile consumer and queue completion path.
            is TdApi.UpdateFile -> Unit
            is TdApi.UpdatePollAnswer -> {
                pollRepository.getMessageIdByPollId(update.pollId)?.let { (chatId, messageId) ->
                    if (isChatOpen(chatId)) {
                        cache.removeMessage(chatId, messageId)
                        safeExecute(TdApi.GetMessageLocally(chatId, messageId))?.let { message ->
                            cache.putMessage(message)
                            messageEdits.enqueue(mapLiveMessageToModel(message))
                        }
                    }
                }
            }
            is TdApi.UpdateChatReadOutbox -> {
                cache.updateChat(update.chatId) { it.lastReadOutboxMessageId = update.lastReadOutboxMessageId }
                if (isChatOpen(update.chatId)) {
                    messageReads.enqueue(
                        ReadUpdate.Outbox(
                            update.chatId,
                            update.lastReadOutboxMessageId
                        )
                    )
                    refreshAndEmitMessage(update.chatId, update.lastReadOutboxMessageId)
                }
            }
            is TdApi.UpdateChatReadInbox -> {
                cache.updateChat(update.chatId) { it.lastReadInboxMessageId = update.lastReadInboxMessageId }
                if (isChatOpen(update.chatId)) {
                    messageReads.enqueue(
                        ReadUpdate.Inbox(
                            update.chatId,
                            update.lastReadInboxMessageId
                        )
                    )
                }
            }
            is TdApi.UpdateDeleteMessages -> {
                val messageIds = update.messageIds.toList()
                cache.removeMessages(update.chatId, messageIds)
                removeMessagesFromCache(update.chatId, messageIds)
                if (!update.fromCache && isChatOpen(update.chatId)) {
                        messageDeletes.enqueue(
                            MessageDeletedEvent(
                                chatId = update.chatId,
                                messageIds = messageIds
                            )
                        )
                }
            }
            is TdApi.UpdateChatUnreadMentionCount -> {
                cache.updateChat(update.chatId) { it.unreadMentionCount = update.unreadMentionCount }
            }

            is TdApi.UpdateChatUnreadReactionCount -> {
                cache.updateChat(update.chatId) { it.unreadReactionCount = update.unreadReactionCount }
            }
            else -> {}
        }
    }

    private fun refreshMessageDebounced(chatId: Long, messageId: Long) {
        if (messageId == 0L || !isChatOpen(chatId)) return
        val key = chatId to messageId
        refreshJobs[key]?.cancel()
        val job = scope.launch(dispatcherProvider.io) {
            delay(200)
            try { refreshAndEmitMessage(chatId, messageId) }
            finally {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) refreshJobs.remove(key, coroutineContext[Job])
                else refreshJobs.remove(key)
            }
        }
        refreshJobs[key] = job
    }

    private suspend fun refreshAndEmitMessage(chatId: Long, messageId: Long) {
        if (messageId == 0L || !isChatOpen(chatId)) return
        val msg = cache.getMessage(chatId, messageId) ?: return
        val model = mapLiveMessageToModel(msg)
        messageEdits.enqueue(model)
    }

    private suspend fun mapLiveMessageToModel(message: TdApi.Message): MessageModel {
        val chat = cache.getChat(message.chatId)
        val options = LIVE_MESSAGE_MAP_OPTIONS
        val model = if (chat != null) {
            messageMapper.mapMessageToModelSync(
                message,
                chat.lastReadInboxMessageId,
                chat.lastReadOutboxMessageId,
                isChatOpen = true,
                options = options
            )
        } else {
            messageMapper.mapMessageToModel(
                message,
                isChatOpen = true,
                options = options
            )
        }
        return model
    }

    override fun updateVisibleRange(
        chatId: Long,
        visibleIds: List<Long>,
        nearbyIds: List<Long>,
        policy: MediaAutoDownloadPolicy
    ) {
        fileDownloadQueue.updateVisibleRange(chatId, visibleIds, nearbyIds, policy)
    }

    override fun setChatOpened(chatId: Long) {
        openChatIds.add(chatId)
        fileDownloadQueue.setChatOpened(chatId)
    }
    override fun setChatClosed(chatId: Long) {
        openChatIds.remove(chatId)
        refreshJobs.keys.filter { it.first == chatId }.forEach { key ->
            refreshJobs.remove(key)?.cancel()
        }
        messageUpdateJobs.keys.filter { it.first == chatId }.forEach { key ->
            messageUpdateJobs.remove(key)?.cancel()
        }
        fileDownloadQueue.setChatClosed(chatId)
    }

    private fun isChatOpen(chatId: Long): Boolean = openChatIds.contains(chatId)

    override fun enqueueDownload(fileId: Int, priority: Int, type: DownloadType, offset: Long, limit: Long, synchronous: Boolean) {
        fileDownloadQueue.enqueue(
            fileId,
            priority,
            when (type) {
                DownloadType.VIDEO -> FileDownloadQueue.DownloadType.VIDEO
                DownloadType.GIF -> FileDownloadQueue.DownloadType.GIF
                DownloadType.STICKER -> FileDownloadQueue.DownloadType.STICKER
                DownloadType.VIDEO_NOTE -> FileDownloadQueue.DownloadType.VIDEO_NOTE
                DownloadType.DEFAULT -> FileDownloadQueue.DownloadType.DEFAULT
            },
            offset,
            limit,
            synchronous
        )
    }

    override suspend fun forwardMessage(
        toChatId: Long,
        fromChatId: Long,
        messageId: Long,
        sendCopy: Boolean
    ) {
        val request = TdApi.ForwardMessages().apply {
            this.chatId = toChatId
            this.fromChatId = fromChatId
            this.messageIds = longArrayOf(messageId)
            this.removeCaption = false
            this.sendCopy = sendCopy
        }
        safeExecute(request)
    }

    fun waitForUpload(fileId: Int): CompletableDeferred<Unit> = fileDownloadQueue.waitForUpload(fileId)

    private fun projectFileUpdate(file: TdApi.File) {
        val isDC = file.local?.isDownloadingCompleted == true
        val isD = file.local?.isDownloadingActive == true
        val wasDownloading = lastDownloadActiveMap[file.id] == true
        if (isD) {
            lastDownloadActiveMap[file.id] = true
        } else {
            lastDownloadActiveMap.remove(file.id)
        }
        val isCancelled = wasDownloading && !isD && !isDC
        val isUC = file.remote?.isUploadingCompleted == true
        val isU = file.remote?.isUploadingActive == true

        if (isD || isDC || isCancelled) {
            Log.d(
                "DownloadDebug",
                "td.updateFile: fileId=${file.id} isD=$isD isDC=$isDC isCancelled=$isCancelled downloaded=${file.local?.downloadedSize ?: 0}/${file.size} pathEmpty=${file.local?.path.isNullOrEmpty()}"
            )
        }

        if (isDC) {
            lastProgressMap.remove(file.id)
            scope.launch {
                fileDownloads.enqueue(
                    FileDownloadEvent.Completed(
                        fileId = file.id,
                        path = file.local?.path ?: ""
                    )
                )
                fileDownloads.enqueue(
                    FileDownloadEvent.Progress(
                        fileId = file.id,
                        progress = 1.0f
                    )
                )
            }
            fileUpdateHandler.fileIdToCustomEmojiId[file.id]?.let { customEmojiId ->
                fileUpdateHandler.customEmojiPaths[customEmojiId] = file.local?.path ?: ""
            }

            val entries = fileIdToMessageMap[file.id]
            if (!entries.isNullOrEmpty()) {
                scope.launch {
                    entries.forEach { (chatId, messageId) ->
                        messageDownloads.enqueue(
                            MessageDownloadEvent.Completed(
                                chatId = chatId,
                                messageId = messageId,
                                fileId = file.id,
                                path = file.local?.path ?: ""
                            )
                        )
                        messageDownloads.enqueue(
                            MessageDownloadEvent.Progress(
                                chatId = chatId,
                                messageId = messageId,
                                fileId = file.id,
                                progress = 1.0f
                            )
                        )
                    }
                }
            } else if (fileDownloadQueue.registry.standaloneFileIds.contains(file.id)) {
                fileDownloadQueue.registry.standaloneFileIds.remove(file.id)
            }
            updateMessageWithFile(file.id)
        } else if (isD) {
            val p =
                if (file.size > 0 && file.local != null) file.local.downloadedSize.toFloat() / file.size.toFloat() else 0f
            val pInt = (p * 100).toInt()
            if (lastProgressMap[file.id] != pInt) {
                lastProgressMap[file.id] = pInt
                scope.launch {
                    fileDownloads.enqueue(
                        FileDownloadEvent.Progress(
                            fileId = file.id,
                            progress = p
                        )
                    )
                }
                val entries = fileIdToMessageMap[file.id]
                if (!entries.isNullOrEmpty()) {
                    scope.launch {
                        entries.forEach { (chatId, messageId) ->
                            messageDownloads.enqueue(
                                MessageDownloadEvent.Progress(
                                    chatId = chatId,
                                    messageId = messageId,
                                    fileId = file.id,
                                    progress = p
                                )
                            )
                        }
                    }
                }
            }
        } else if (isCancelled) {
            lastProgressMap.remove(file.id)
            Log.d("DownloadDebug", "td.downloadCancelled.emit: fileId=${file.id}")
            emitCancelledForFile(file.id)
        }

        if (isUC) {
            lastProgressMap.remove(file.id xor 0x55555555)
            val entries = fileIdToMessageMap[file.id]
            if (!entries.isNullOrEmpty()) {
                scope.launch {
                    entries.forEach { (chatId, messageId) ->
                        messageUploadProgressFlow.emit(
                            MessageUploadProgressEvent(
                                chatId = chatId,
                                messageId = messageId,
                                fileId = file.id,
                                progress = 1.0f
                            )
                        )
                    }
                }
            }
            updateMessageWithFile(file.id)
        } else if (isU) {
            val p =
                if (file.size > 0 && file.remote != null) file.remote.uploadedSize.toFloat() / file.size.toFloat() else 0f
            val pInt = (p * 100).toInt()
            if (lastProgressMap[file.id xor 0x55555555] != pInt) {
                lastProgressMap[file.id xor 0x55555555] = pInt
                val entries = fileIdToMessageMap[file.id]
                if (!entries.isNullOrEmpty()) {
                    scope.launch {
                        entries.forEach { (chatId, messageId) ->
                            messageUploadProgressFlow.emit(
                                MessageUploadProgressEvent(
                                    chatId = chatId,
                                    messageId = messageId,
                                    fileId = file.id,
                                    progress = p
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    override fun registerFileForMessage(fileId: Int, chatId: Long, messageId: Long) {
        fileDownloadQueue.registry.register(fileId, chatId, messageId)
    }

    private fun removeMessagesFromCache(chatId: Long, messageIds: List<Long>) {
        fileDownloadQueue.registry.removeMessages(chatId, messageIds)
    }

    private fun updateMessageIdInCache(chatId: Long, oldMId: Long, newMId: Long) {
        fileDownloadQueue.registry.updateMessageId(chatId, oldMId, newMId)
    }

    override fun isFileQueued(fileId: Int): Boolean = fileDownloadQueue.isFileQueued(fileId)

    private fun updateMessageWithFile(fileId: Int) {
        val entries = fileIdToMessageMap[fileId] ?: return
        entries.toList().forEach { (chatId, messageId) ->
            if (!isChatOpen(chatId)) return@forEach
            val key = chatId to messageId
            messageUpdateJobs[key]?.cancel()
            val job = scope.launch {
                delay(150)
                val msg = cache.getMessage(chatId, messageId) ?: return@launch
                try {
                    messageEdits.enqueue(mapLiveMessageToModel(msg))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("TdMessageRemote", "Error emitting edited message", e)
                }
            }
            job.invokeOnCompletion { messageUpdateJobs.remove(key, job) }
            messageUpdateJobs[key] = job
        }
    }

    fun clear() {
        refreshJobs.values.forEach { it.cancel() }; refreshJobs.clear()
        messageUpdateJobs.values.forEach { it.cancel() }; messageUpdateJobs.clear()
        lastProgressMap.clear()
        missingMessageCooldownUntil.clear()
        openChatIds.clear()
        fileDownloadQueue.setObserver(null)
    }

    private fun emitCancelledForFile(fileId: Int) {
        if (fileId == 0) return
        lastProgressMap.remove(fileId)
        val now = System.currentTimeMillis()
        val previous = lastCancelledEmissionAt[fileId]
        if (previous != null && now - previous < 250L) return
        lastCancelledEmissionAt[fileId] = now

        scope.launch {
            fileDownloads.enqueue(FileDownloadEvent.Cancelled(fileId))
        }

        val entries = fileIdToMessageMap[fileId]
        if (!entries.isNullOrEmpty()) {
            scope.launch {
                entries.forEach { (chatId, messageId) ->
                    messageDownloads.enqueue(
                        MessageDownloadEvent.Cancelled(
                            chatId = chatId,
                            messageId = messageId,
                            fileId = fileId
                        )
                    )
                }
            }
        }
    }

    private fun emitQueuedForFile(fileId: Int) {
        if (fileId == 0) return

        scope.launch {
            fileDownloads.enqueue(
                FileDownloadEvent.Progress(
                    fileId = fileId,
                    progress = 0f
                )
            )
        }

        val entries = fileIdToMessageMap[fileId]
        if (!entries.isNullOrEmpty()) {
            scope.launch {
                entries.forEach { (chatId, messageId) ->
                    messageDownloads.enqueue(
                        MessageDownloadEvent.Progress(
                            chatId = chatId,
                            messageId = messageId,
                            fileId = fileId,
                            progress = 0f
                        )
                    )
                }
            }
        }
    }

    companion object {
        internal val LIVE_MESSAGE_MAP_OPTIONS = MessageMapOptions(
            resolveReplyPreviewFromNetwork = false,
            allowAutoDownload = false,
            resolveEnrichmentFromNetwork = false
        )
        private val LOCAL_HISTORY_MAP_OPTIONS = MessageMapOptions(
            resolveReplyPreviewFromNetwork = false,
            allowAutoDownload = false,
            resolveEnrichmentFromNetwork = false
        )
        private val MISSING_MESSAGE_COOLDOWN_MS = TimeUnit.MINUTES.toMillis(2)
        private const val DRAFT_LINK_PREVIEW_TAG = "DraftLinkPreview"
    }
}
