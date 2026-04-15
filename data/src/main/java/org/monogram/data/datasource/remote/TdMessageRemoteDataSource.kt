package org.monogram.data.datasource.remote

import android.os.Build
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.drinkless.tdlib.TdApi
import org.monogram.core.DispatcherProvider
import org.monogram.data.chats.ChatCache
import org.monogram.data.gateway.TdLibException
import org.monogram.data.gateway.TelegramGateway
import org.monogram.data.infra.FileDownloadQueue
import org.monogram.data.infra.FileUpdateHandler
import org.monogram.data.mapper.MessageMapper
import org.monogram.data.mapper.toApi
import org.monogram.domain.models.FileDownloadEvent
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageDeletedEvent
import org.monogram.domain.models.MessageDownloadEvent
import org.monogram.domain.models.MessageEntity
import org.monogram.domain.models.MessageEntityType
import org.monogram.domain.models.MessageIdUpdatedEvent
import org.monogram.domain.models.MessageModel
import org.monogram.domain.models.MessageSendOptions
import org.monogram.domain.models.MessageUploadProgressEvent
import org.monogram.domain.models.MessageViewerModel
import org.monogram.domain.models.PollDraft
import org.monogram.domain.models.UserModel
import org.monogram.domain.models.webapp.ThemeParams
import org.monogram.domain.models.webapp.WebAppInfoModel
import org.monogram.domain.repository.ChatListRepository
import org.monogram.domain.repository.OlderMessagesPage
import org.monogram.domain.repository.PollRepository
import org.monogram.domain.repository.ReadUpdate
import org.monogram.domain.repository.SearchChatMessagesResult
import org.monogram.domain.repository.UserRepository
import java.util.concurrent.ConcurrentHashMap

class TdMessageRemoteDataSource(
    private val gateway: TelegramGateway,
    private val messageMapper: MessageMapper,
    private val userRepository: UserRepository,
    private val chatListRepository: ChatListRepository,
    private val cache: ChatCache,
    private val pollRepository: PollRepository,
    private val fileDownloadQueue: FileDownloadQueue,
    private val fileUpdateHandler: FileUpdateHandler,
    private val dispatcherProvider: DispatcherProvider,
    val scope: CoroutineScope
) : MessageRemoteDataSource {

    private val chatRequests = ConcurrentHashMap<Long, Deferred<TdApi.Chat?>>()
    private val messageRequests = ConcurrentHashMap<Pair<Long, Long>, Deferred<TdApi.Message?>>()
    private val refreshJobs = ConcurrentHashMap<Pair<Long, Long>, Job>()
    private val sendQueue = Channel<suspend () -> Unit>(Channel.BUFFERED)
    override val newMessageFlow = MutableSharedFlow<MessageModel>()
    override val messageEditedFlow = MutableSharedFlow<MessageModel>()
    override val messageReadFlow = MutableSharedFlow<ReadUpdate>(
        replay = 1,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val messageUploadProgressFlow = MutableSharedFlow<MessageUploadProgressEvent>()
    override val fileDownloadFlow = MutableSharedFlow<FileDownloadEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val messageDownloadFlow = MutableSharedFlow<MessageDownloadEvent>()
    override val messageDeletedFlow = MutableSharedFlow<MessageDeletedEvent>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val messageIdUpdateFlow = MutableSharedFlow<MessageIdUpdatedEvent>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
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

    init {
        scope.launch {
            for (task in sendQueue) {
                try {
                    task()
                } catch (e: Exception) {
                    Log.e("TdMessageRemote", "Error in sendQueue", e)
                }
            }
        }
    }

    private suspend fun <T : TdApi.Object> safeExecute(function: TdApi.Function<T>): T? {
        return try {
            gateway.execute(function)
        } catch (e: Exception) {
            Log.e("TdMessageRemote", "Error executing ${function.javaClass.simpleName}", e)
            null
        }
    }


    override suspend fun getMessage(chatId: Long, messageId: Long): TdApi.Message? {
        cache.getMessage(chatId, messageId)?.let { return it }
        val key = chatId to messageId
        val deferred = messageRequests.getOrPut(key) {
            scope.async {
                val result = safeExecute(TdApi.GetMessage(chatId, messageId))
                if (result != null) cache.putMessage(result)
                result
            }
        }
        return try {
            deferred.await()
        } catch (e: Exception) {
            null
        } finally {
            messageRequests.remove(key)
        }
    }

    suspend fun getChat(chatId: Long): TdApi.Chat? {
        cache.getChat(chatId)?.let { return it }
        val deferred = chatRequests.getOrPut(chatId) {
            scope.async {
                val result = safeExecute(TdApi.GetChat(chatId))
                if (result != null) cache.putChat(result)
                result
            }
        }
        return try {
            deferred.await()
        } catch (e: Exception) {
            null
        } finally {
            chatRequests.remove(chatId)
        }
    }

    override suspend fun getMessagesOlder(chatId: Long, fromMessageId: Long, limit: Int, threadId: Long?): OlderMessagesPage {
        if (fromMessageId == 0L) {
            val messages = loadMessages(chatId, fromMessageId, 0, limit, threadId)
            val page = OlderMessagesPage(
                messages = messages,
                reachedOldest = messages.isEmpty(),
                isRemote = true
            )
            return page
        }

        val messages = loadMessages(chatId, fromMessageId, 0, limit + 1, threadId)
        val filtered = messages.filter { it.id != fromMessageId }.take(limit)
        val page = OlderMessagesPage(
            messages = filtered,
            reachedOldest = filtered.isEmpty(),
            isRemote = true
        )
        return page
    }

    override suspend fun getMessagesNewer(chatId: Long, fromMessageId: Long, limit: Int, threadId: Long?): List<MessageModel> {
        return loadMessages(chatId, fromMessageId, -limit, limit, threadId)
    }

    override suspend fun getMessagesAround(chatId: Long, messageId: Long, limit: Int, threadId: Long?): List<MessageModel> {
        return loadMessages(chatId, messageId, -limit / 2, limit, threadId)
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

    override suspend fun searchChatMessages(chatId: Long, query: String, fromMessageId: Long, limit: Int, filter: TdApi.SearchMessagesFilter, threadId: Long?): TdApi.FoundChatMessages? {
        val request = TdApi.SearchChatMessages().apply {
            this.chatId = chatId
            this.topicId = if (threadId != null) TdApi.MessageTopicForum(threadId.toInt()) else null
            this.query = query
            this.senderId = null
            this.fromMessageId = fromMessageId
            this.offset = 0
            this.limit = limit
            this.filter = filter
        }
        return safeExecute(request)
    }

    override suspend fun searchMessages(chatId: Long, query: String, fromMessageId: Long, limit: Int, threadId: Long?): SearchChatMessagesResult {
        val result = searchChatMessages(chatId, query, fromMessageId, limit, TdApi.SearchMessagesFilterEmpty(), threadId)
        if (result != null) {
            val chat = getChat(chatId)
            val lastReadInbox = chat?.lastReadInboxMessageId ?: 0L
            val lastReadOutbox = chat?.lastReadOutboxMessageId ?: 0L
            val models = result.messages.map { msg ->
                cache.putMessage(msg)
                scope.async {
                    try {
                        withTimeout(5000) { messageMapper.mapMessageToModelSync(msg, lastReadInbox, lastReadOutbox, isChatOpen = true) }
                    } catch (e: Exception) {
                        Log.e("TdMessageRemote", "Error mapping search message ${msg.id}", e)
                        createFallbackMessage(msg)
                    }
                }
            }.awaitAll()
            return SearchChatMessagesResult(models, result.totalCount, result.nextFromMessageId)
        } else return SearchChatMessagesResult(emptyList(), 0, 0L)
    }

    private suspend fun loadMessages(chatId: Long, fromMessageId: Long, offset: Int, limit: Int, threadId: Long? = null): List<MessageModel> = withContext(dispatcherProvider.io) {
        val historyResult = getChatHistoryInternal(chatId, fromMessageId, offset, limit, threadId)
            ?: throw IllegalStateException(
                "Failed to load history for chatId=$chatId fromMessageId=$fromMessageId offset=$offset limit=$limit threadId=$threadId"
            )
        val chat = getChat(chatId)
        val lastReadInbox = chat?.lastReadInboxMessageId ?: 0L
        val lastReadOutbox = chat?.lastReadOutboxMessageId ?: 0L
        val messages = when (historyResult) {
            is TdApi.Messages -> historyResult.messages
            is TdApi.MessageThreadInfo -> historyResult.messages
            else -> emptyArray()
        }
        messages.map { msg ->
            cache.putMessage(msg)
            async {
                try {
                    withTimeout(5000) { messageMapper.mapMessageToModelSync(msg, lastReadInbox, lastReadOutbox, isChatOpen = true) }
                } catch (e: Exception) {
                    Log.e("TdMessageRemote", "Error mapping message ${msg.id}", e)
                    createFallbackMessage(msg)
                }
            }
        }.awaitAll()
    }

    private suspend fun getChatHistoryInternal(chatId: Long, fromMessageId: Long, offset: Int, limit: Int, threadId: Long? = null): TdApi.Object? {
        return if (threadId != null) {
            val chat = getChat(chatId)
            if (chat != null) {
                if (chat.viewAsTopics) {
                    val req = TdApi.GetForumTopicHistory().apply {
                        this.chatId = chatId
                        this.forumTopicId = threadId.toInt()
                        this.fromMessageId = fromMessageId
                        this.offset = offset
                        this.limit = limit
                    }
                    safeExecute(req)
                } else {
                    val req = TdApi.GetMessageThreadHistory().apply {
                        this.chatId = chatId
                        this.messageId = threadId
                        this.fromMessageId = fromMessageId
                        this.offset = offset
                        this.limit = limit
                    }
                    safeExecute(req)
                }
            } else null
        } else {
            val req = TdApi.GetChatHistory().apply {
                this.chatId = chatId
                this.fromMessageId = fromMessageId
                this.offset = offset
                this.limit = limit
                this.onlyLocal = false
            }
            safeExecute(req)
        }
    }

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

    override suspend fun getMessages(chatId: Long, fromMessageId: Long, offset: Int, limit: Int, threadId: Long?): TdApi.Messages? {
        return when (val result = getChatHistoryInternal(chatId, fromMessageId, offset, limit, threadId)) {
            is TdApi.Messages -> result
            is TdApi.MessageThreadInfo -> TdApi.Messages(result.messages.size, result.messages)
            else -> null
        }
    }

    private fun createFallbackMessage(msg: TdApi.Message): MessageModel = MessageModel(
        id = msg.id, date = msg.date, isOutgoing = msg.isOutgoing, senderName = "Unknown", chatId = msg.chatId,
        content = MessageContent.Text("Error loading message"), senderId = 0L, senderAvatar = null, isRead = false,
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
            this.clearDraft = true
        }
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

    override suspend fun sendPhoto(
        chatId: Long,
        photoPath: String,
        caption: String,
        captionEntities: List<MessageEntity>,
        replyToMsgId: Long?,
        threadId: Long?,
        sendOptions: MessageSendOptions
    ): TdApi.Message? {
        val content = TdApi.InputMessagePhoto().apply {
            this.photo = TdApi.InputFileLocal(photoPath)
            this.caption = TdApi.FormattedText(caption, captionEntities.toTdTextEntities(caption))
        }
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
        replyToMsgId: Long?,
        threadId: Long?,
        sendOptions: MessageSendOptions
    ): TdApi.Message? {
        val content = TdApi.InputMessageVideo().apply {
            this.video = TdApi.InputFileLocal(videoPath)
            this.caption = TdApi.FormattedText(caption, captionEntities.toTdTextEntities(caption))
        }
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
        val content = TdApi.InputMessageDocument().apply {
            this.document = TdApi.InputFileLocal(documentPath)
            this.caption = TdApi.FormattedText(caption, captionEntities.toTdTextEntities(caption))
        }
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
        val pollOptions = poll.options.map { option ->
            TdApi.InputPollOption(TdApi.FormattedText(option, emptyArray()))
        }.toTypedArray()
        val type = if (poll.isQuiz) {
            val correctOptionIds = poll.correctOptionIds
                .map { it.coerceAtLeast(0) }
                .distinct()
                .toIntArray()
            TdApi.InputPollTypeQuiz(
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
            this.sticker = TdApi.InputFileLocal(stickerPath)
            this.width = 512
            this.height = 512
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
        val content = TdApi.InputMessageAnimation().apply {
            this.animation = TdApi.InputFileId(gifId.toInt())
        }
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
        replyToMsgId: Long?,
        threadId: Long?,
        sendOptions: MessageSendOptions
    ): TdApi.Message? {
        val content = TdApi.InputMessageAnimation().apply {
            this.animation = TdApi.InputFileLocal(gifPath)
            this.caption = TdApi.FormattedText(caption, captionEntities.toTdTextEntities(caption))
        }
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
        replyToMsgId: Long?,
        threadId: Long?,
        sendOptions: MessageSendOptions
    ): TdApi.Messages? {
        val inputMessageContents = paths.mapIndexed { index, path ->
            val isVideo = path.endsWith(".mp4", ignoreCase = true)
            val cap = if (index == 0) TdApi.FormattedText(caption, captionEntities.toTdTextEntities(caption)) else null
            if (isVideo) TdApi.InputMessageVideo().apply {
                this.video = TdApi.InputFileLocal(path)
                this.caption = cap
            }
            else TdApi.InputMessagePhoto().apply {
                this.photo = TdApi.InputFileLocal(path)
                this.caption = cap
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

    override suspend fun sendVideoNote(chatId: Long, videoPath: String, duration: Int, length: Int): TdApi.Message? {
        val content = TdApi.InputMessageVideoNote().apply {
            this.videoNote = TdApi.InputFileLocal(videoPath)
            this.duration = duration
            this.length = length
        }
        val req = TdApi.SendMessage().apply {
            this.chatId = chatId
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
        waveform: ByteArray
    ): TdApi.Message? {
        val content = TdApi.InputMessageVoiceNote().apply {
            this.voiceNote = TdApi.InputFileLocal(voicePath)
            this.duration = duration
            this.waveform = waveform
        }
        val req = TdApi.SendMessage().apply {
            this.chatId = chatId
            this.inputMessageContent = content
        }
        val response = safeExecute(req)
        if (response?.content is TdApi.MessageVoiceNote) waitForUpload((response.content as TdApi.MessageVoiceNote).voiceNote.voice.id).await()
        return response
    }

    override suspend fun forwardMessages(toChatId: Long, fromChatId: Long, messageIds: LongArray, removeCaption: Boolean, sendCopy: Boolean): TdApi.Messages? {
        val options = TdApi.MessageSendOptions().apply {
            this.disableNotification = false
            this.fromBackground = false
        }
        val req = TdApi.ForwardMessages().apply {
            this.chatId = toChatId
            this.fromChatId = fromChatId
            this.messageIds = messageIds
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
        }
        val req = TdApi.EditMessageText().apply {
            this.chatId = chatId
            this.messageId = messageId
            this.inputMessageContent = content
        }
        return safeExecute(req)
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
            is MessageEntityType.BotCommand -> TdApi.TextEntityTypeBotCommand()
            is MessageEntityType.Url -> TdApi.TextEntityTypeUrl()
            is MessageEntityType.Email -> TdApi.TextEntityTypeEmailAddress()
            is MessageEntityType.PhoneNumber -> TdApi.TextEntityTypePhoneNumber()
            is MessageEntityType.BankCardNumber -> TdApi.TextEntityTypeBankCardNumber()
            is MessageEntityType.CustomEmoji -> TdApi.TextEntityTypeCustomEmoji(value.emojiId)
            is MessageEntityType.Other -> return null
        }

        return TdApi.TextEntity(start, safeLength, tdType)
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
        val parameters = TdApi.WebAppOpenParameters().apply {
            this.applicationName = "android"
            this.mode = TdApi.WebAppOpenModeFullSize()
            this.theme = theme?.toApi()
        }

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
                val result = gateway.execute(
                    TdApi.OpenWebApp(targetChatId, botUserId, targetUrl, null, null, parameters)
                )
                if (result is TdApi.WebAppInfo) {
                    return WebAppInfoModel(result.launchId, result.url)
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

    override suspend fun markAsRead(chatId: Long, messageId: Long) {
        safeExecute(TdApi.ViewMessages(chatId, longArrayOf(messageId), null, true))
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
                    val content = draft.inputMessageText
                    return if (content is TdApi.InputMessageText) {
                        content.text.text
                    } else {
                        null
                    }
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
                    val content = draft.inputMessageText
                    if (content is TdApi.InputMessageText) {
                        return content.text.text
                    }
                }
            }

            val result = safeExecute(TdApi.GetChat(chatId))
            if (result is TdApi.Chat) {
                cache.putChat(result)
                val draft = result.draftMessage
                if (draft != null) {
                    val content = draft.inputMessageText
                    return if (content is TdApi.InputMessageText) {
                        content.text.text
                    } else {
                        null
                    }
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
                scope.launch(dispatcherProvider.io) {
                    try {
                        val model = mapMessageToModel(message)
                        newMessageFlow.emit(model)
                    } catch (e: Exception) { Log.e("TdMessageRemote", "Error mapping NewMessage", e) }
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
                scope.launch(dispatcherProvider.io) {
                    try {
                        val model = mapMessageToModel(message)
                        messageIdUpdateFlow.emit(
                            MessageIdUpdatedEvent(
                                chatId = message.chatId,
                                oldMessageId = update.oldMessageId,
                                message = model
                            )
                        )
                    } catch (e: Exception) { Log.e("TdMessageRemote", "Error handling SendSucceeded", e) }
                }
            }
            is TdApi.UpdateMessageSendFailed -> {
                cache.putMessage(update.message)
                scope.launch(dispatcherProvider.io) {
                    val model = mapMessageToModel(update.message)
                    messageEditedFlow.emit(model)
                }
            }
            is TdApi.UpdateMessageContent -> {
                if (update.newContent is TdApi.MessagePoll) {
                    val poll = (update.newContent as TdApi.MessagePoll).poll
                    pollRepository.mapPollIdToMessage(poll.id, update.chatId, update.messageId)
                }
                cache.removeMessage(update.chatId, update.messageId)
                refreshMessageDebounced(update.chatId, update.messageId)
            }
            is TdApi.UpdateMessageEdited -> {
                cache.removeMessage(update.chatId, update.messageId)
                refreshMessageDebounced(update.chatId, update.messageId)
            }
            is TdApi.UpdateMessageInteractionInfo -> {
                cache.removeMessage(update.chatId, update.messageId)
                refreshMessageDebounced(update.chatId, update.messageId)
            }
            is TdApi.UpdateMessageReaction -> {
                cache.removeMessage(update.chatId, update.messageId)
            }
            is TdApi.UpdateMessageReactions -> {
                cache.removeMessage(update.chatId, update.messageId)
                refreshMessageDebounced(update.chatId, update.messageId)
            }
            is TdApi.UpdateMessageMentionRead -> {
                cache.removeMessage(update.chatId, update.messageId)
                cache.updateChat(update.chatId) { it.unreadMentionCount = update.unreadMentionCount }
                refreshMessageDebounced(update.chatId, update.messageId)
            }
            is TdApi.UpdateFile -> {
                scope.launch(dispatcherProvider.io) { handleFileUpdate(update.file) }
            }
            is TdApi.UpdatePollAnswer -> {
                scope.launch(dispatcherProvider.io) {
                    pollRepository.getMessageIdByPollId(update.pollId)?.let { (chatId, messageId) ->
                        cache.removeMessage(chatId, messageId)
                        refreshAndEmitMessage(chatId, messageId)
                    }
                }
            }
            is TdApi.UpdateChatReadOutbox -> {
                cache.updateChat(update.chatId) { it.lastReadOutboxMessageId = update.lastReadOutboxMessageId }
                scope.launch(dispatcherProvider.io) {
                    messageReadFlow.emit(ReadUpdate.Outbox(update.chatId, update.lastReadOutboxMessageId))
                    refreshAndEmitMessage(update.chatId, update.lastReadOutboxMessageId)
                }
            }
            is TdApi.UpdateChatReadInbox -> {
                cache.updateChat(update.chatId) { it.lastReadInboxMessageId = update.lastReadInboxMessageId }
                scope.launch(dispatcherProvider.io) { messageReadFlow.emit(ReadUpdate.Inbox(update.chatId, update.lastReadInboxMessageId)) }
            }
            is TdApi.UpdateDeleteMessages -> {
                if (!update.fromCache) {
                    val messageIds = update.messageIds.toList()
                    scope.launch(dispatcherProvider.io) {
                        messageIds.forEach { cache.removeMessage(update.chatId, it) }
                        removeMessagesFromCache(update.chatId, messageIds)
                        messageDeletedFlow.emit(
                            MessageDeletedEvent(
                                chatId = update.chatId,
                                messageIds = messageIds
                            )
                        )
                    }
                }
            }
            is TdApi.UpdateMessageIsPinned -> { scope.launch(dispatcherProvider.io) { pinnedMessageFlow.emit(update.chatId) } }
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
        if (messageId == 0L) return
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
        if (messageId == 0L) return
        val msg = getMessage(chatId, messageId) ?: return
        val model = mapMessageToModel(msg)
        messageEditedFlow.emit(model)
    }

    private suspend fun mapMessageToModel(message: TdApi.Message): MessageModel {
        val chat = getChat(message.chatId)
        val model = if (chat != null) {
            messageMapper.mapMessageToModelSync(message, chat.lastReadInboxMessageId, chat.lastReadOutboxMessageId, isChatOpen = true)
        } else {
            messageMapper.mapMessageToModel(message, isChatOpen = true)
        }
        val readDate = if (message.isOutgoing && model.isRead) messageMapper.getMessageReadDate(message.chatId, message.id, message.date) else 0
        return model.copy(readDate = readDate)
    }

    override fun updateVisibleRange(chatId: Long, visibleIds: List<Long>, nearbyIds: List<Long>) {
        fileDownloadQueue.updateVisibleRange(chatId, visibleIds, nearbyIds)
    }

    override fun setChatOpened(chatId: Long) { fileDownloadQueue.setChatOpened(chatId) }
    override fun setChatClosed(chatId: Long) {
        fileDownloadQueue.setChatClosed(chatId)
    }

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
        messageId: Long
    ) {
        val request = TdApi.ForwardMessages().apply {
            this.chatId = toChatId
            this.fromChatId = fromChatId
            this.messageIds = longArrayOf(messageId)
            this.removeCaption = false
            this.sendCopy = false
        }
        safeExecute(request)
    }

    fun waitForUpload(fileId: Int): CompletableDeferred<Unit> = fileDownloadQueue.waitForUpload(fileId)

    fun handleFileUpdate(file: TdApi.File) {
        fileDownloadQueue.updateFileCache(file)
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
            fileDownloadQueue.notifyDownloadComplete(file.id)
            lastProgressMap.remove(file.id)
            scope.launch {
                fileDownloadFlow.emit(
                    FileDownloadEvent.Completed(
                        fileId = file.id,
                        path = file.local?.path ?: ""
                    )
                )
                fileDownloadFlow.emit(
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
                        messageDownloadFlow.emit(
                            MessageDownloadEvent.Completed(
                                chatId = chatId,
                                messageId = messageId,
                                fileId = file.id,
                                path = file.local?.path ?: ""
                            )
                        )
                        messageDownloadFlow.emit(
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
                    fileDownloadFlow.emit(
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
                            messageDownloadFlow.emit(
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
            val entries = fileIdToMessageMap[file.id]
            if (!entries.isNullOrEmpty()) {
                scope.launch {
                    entries.forEach { (chatId, messageId) ->
                        messageDownloadFlow.emit(
                            MessageDownloadEvent.Cancelled(
                                chatId = chatId,
                                messageId = messageId,
                                fileId = file.id
                            )
                        )
                    }
                }
            }
        }

        if (isUC) {
            fileDownloadQueue.notifyUploadComplete(file.id)
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
            val key = chatId to messageId
            messageUpdateJobs[key]?.cancel()
            val job = scope.launch {
                delay(150)
                val msg = getMessage(chatId, messageId) ?: return@launch
                try {
                    messageEditedFlow.emit(mapMessageToModel(msg))
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
    }
}
