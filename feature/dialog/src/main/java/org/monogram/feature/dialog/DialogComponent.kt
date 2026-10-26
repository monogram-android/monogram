package org.monogram.feature.dialog

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.router.slot.ChildSlot
import com.arkivanov.decompose.router.slot.SlotNavigation
import com.arkivanov.decompose.router.slot.activate
import com.arkivanov.decompose.router.slot.childSlot
import com.arkivanov.decompose.router.slot.dismiss
import com.arkivanov.decompose.value.Value
import com.arkivanov.essenty.instancekeeper.getOrCreate
import com.arkivanov.essenty.lifecycle.Lifecycle
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.arkivanov.essenty.lifecycle.doOnStart
import com.arkivanov.essenty.lifecycle.doOnStop
import com.arkivanov.mvikotlin.core.instancekeeper.getStore
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.stateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.serializer
import org.monogram.core.common.telegram.TelegramError
import org.monogram.core.database.OfflineWarmup
import org.monogram.core.database.SessionMetadataStore
import org.monogram.core.markup.KotlinMarkupParser
import org.monogram.core.markup.MarkupParser
import org.monogram.core.models.ForumIo
import org.monogram.core.models.ForumTopic
import org.monogram.core.models.Message
import org.monogram.core.models.MessageId
import org.monogram.core.models.PeerId
import org.monogram.core.models.TextEntity
import org.monogram.core.models.UploadItem
import org.monogram.core.models.canForwardFrom
import org.monogram.core.models.prepareComposerText
import org.monogram.core.models.remapTextEntities
import org.monogram.core.ui.DownloadSettings
import org.monogram.core.ui.gzipFile
import org.monogram.core.ui.webmFile
import org.monogram.feature.dialog.store.filterComposerPayload
import org.monogram.feature.dialog.ui.InstantViewController
import org.monogram.network.bridge.MtprotoClient
import org.monogram.network.http.MediaRepository

@OptIn(ExperimentalCoroutinesApi::class)
class DialogComponent(
    componentContext: ComponentContext,
    storeFactory: StoreFactory,
    val client: MtprotoClient,
    private val warmup: OfflineWarmup?,
    sessionStore: SessionMetadataStore?,
    val mediaRepository: MediaRepository?,
    val chatId: PeerId,
    private val jumpToMessageId: Int = 0,
    private val threadTopMsgId: Int = 0,
    private val isForum: Boolean? = null,
    val markup: MarkupParser = KotlinMarkupParser(),
    private val isPremium: () -> Boolean = { false },
    private val onBack: () -> Unit,
    private val onOpenProfile: (PeerId) -> Unit = {},
    private val onOpenChat: (PeerId, Int, Int) -> Unit = { _, _, _ -> },
    private val onRequestForward: ((List<Message>) -> Unit)? = null,
    private val onOpenScheduledQueue: () -> Unit = {},
    val showBackButton: Boolean = true,
    private val onResumeLiveLocation: (suspend (MessageId) -> Boolean)? = null,
    private val onStopLiveLocation: (suspend (MessageId) -> Boolean)? = null,
    val liveLocationErrors: Flow<Map<MessageId, TelegramError>> = flowOf(
        emptyMap()
    ),
    val liveLocationFailure: Flow<TelegramError?> = flowOf(
        null
    ),
) : ComponentContext by componentContext {

    private val liveLocationEpoch = warmup?.outgoingAccountEpoch()
    internal val liveLocationControlKey get() = "$liveLocationEpoch:$stateKey"
    private fun liveLocationAccountCurrent() =
        liveLocationEpoch == null || warmup?.outgoingAccountIsCurrent(liveLocationEpoch) == true

    val liveLocationControlsAvailable get() = onResumeLiveLocation != null && onStopLiveLocation != null
    val liveLocationSessions = warmup?.observeLiveLocations()
        ?.map { rows -> if (liveLocationAccountCurrent()) rows.filter { it.messageId.chatId == chatId } else emptyList() }
        ?: flowOf(emptyList())

    suspend fun resumeLiveLocation(messageId: Int): Boolean =
        messageId > 0 && liveLocationAccountCurrent() &&
                onResumeLiveLocation?.invoke(
                    MessageId(
                        chatId,
                        messageId
                    )
                ) == true

    suspend fun stopLiveLocation(messageId: Int): Boolean =
        messageId > 0 && liveLocationAccountCurrent() &&
                onStopLiveLocation?.invoke(
                    MessageId(
                        chatId,
                        messageId
                    )
                ) == true

    private val mutableLiveBusy = MutableStateFlow<Set<Int>>(emptySet())
    val liveLocationBusy: StateFlow<Set<Int>> = mutableLiveBusy
    private val mutableLiveFailed = MutableStateFlow<Set<Int>>(emptySet())
    val liveLocationActionFailed: StateFlow<Set<Int>> = mutableLiveFailed

    fun onResumeLiveLocation(messageId: Int) = changeLiveLocation(messageId, true)
    fun onStopLiveLocation(messageId: Int) = changeLiveLocation(messageId, false)

    private fun changeLiveLocation(messageId: Int, resume: Boolean) {
        if (messageId <= 0 || !liveLocationControlsAvailable || !liveLocationAccountCurrent()) return
        while (true) {
            val current = mutableLiveBusy.value
            if (messageId in current) return
            if (mutableLiveBusy.compareAndSet(current, current + messageId)) break
        }
        preloadScope.launch {
            val epoch = liveLocationEpoch
            try {
                mutableLiveFailed.update { it - messageId }
                val changed =
                    if (resume) resumeLiveLocation(messageId) else stopLiveLocation(messageId)
                if ((epoch == null || warmup?.outgoingAccountIsCurrent(epoch) == true) && !changed)
                    mutableLiveFailed.update { it + messageId }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (epoch == null || warmup?.outgoingAccountIsCurrent(epoch) == true)
                    mutableLiveFailed.update { it + messageId }
            } finally {
                mutableLiveBusy.update { it - messageId }
            }
        }
    }

    val hasScheduledMessages = warmup?.observeScheduledMessages(chatId)?.map { it.isNotEmpty() }
        ?: flowOf(false)

    /** Dialog identity for saveable state. */
    val stateKey: String get() = dialogStateKey(chatId.value, threadTopMsgId, jumpToMessageId)

    private val preloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastVisibleIds: Set<Int> = emptySet()
    private val mediaPreloader = DialogMediaPreloader(
        chatId = chatId,
        mediaRepository = mediaRepository,
        prefetchInstantView = { url, hash -> instantViewController(url, hash).load() },
        scope = preloadScope,
    )
    private val pickerPreloader = PickerMediaPreloader(
        mediaRepository = mediaRepository,
        scope = preloadScope,
    )

    init {
        warmup?.setOpenChat(chatId.value)
        if (warmup != null) preloadScope.launch {
            val epoch = warmup.outgoingAccountEpoch()
            try {
                val revision = warmup.scheduledQueueRevision(chatId, epoch)
                val result = client.getScheduledHistory(chatId)
                if (result is org.monogram.core.common.Outcome.Ok)
                    warmup.replaceScheduledMessagesIfCurrent(chatId, revision, result.value, epoch)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
            }
        }
        lifecycle.doOnStart { client.setDialogForeground(true) }
        lifecycle.doOnStop { client.setDialogForeground(false) }
        lifecycle.doOnDestroy {
            warmup?.clearOpenChat(chatId.value)
            client.setDialogForeground(false)
            mediaPreloader.close()
            pickerPreloader.close()
            preloadScope.cancel()
        }
    }

    class MarkdownEditorChild(componentContext: ComponentContext) :
        ComponentContext by componentContext

    private val editorNavigation = SlotNavigation<Unit>()
    val editorSlot: Value<ChildSlot<Unit, MarkdownEditorChild>> = childSlot(
        source = editorNavigation,
        serializer = Unit.serializer(),
        key = "markdown-editor",
        handleBackButton = true,
    ) { _, ctx -> MarkdownEditorChild(ctx) }

    val markdownAvailable: Boolean get() = isPremium()

    fun openMarkdownEditor() = editorNavigation.activate(Unit)
    fun closeMarkdownEditor() = editorNavigation.dismiss()

    internal suspend fun prepareEditorPayload(
        text: String,
        entities: List<TextEntity>,
        markdown: Boolean
    ): org.monogram.core.models.StyledText {
        val current = state.value
        val mentions = current.draftMentions.map {
            TextEntity("mention_name", it.start, it.length, it.peerId.value.toString())
        }
        val mappedMentions = remapTextEntities(current.draft, text, mentions)
        val parsed = withContext(Dispatchers.Default) {
            prepareComposerText(text, markdown && isPremium(), entities + mappedMentions)
        }
        return filterComposerPayload(parsed, client, isPremium())
    }

    private val restoredChecklistEdit =
        ChecklistEditState.decode(stateKeeper.consume("checklist-edit", String.serializer()))

    private val store = instanceKeeper.getStore {
        DialogStoreFactory(
            storeFactory,
            client,
            warmup,
            sessionStore,
            chatId,
            jumpToMessageId,
            threadTopMsgId,
            isForum,
            markup = markup,
            isPremium = isPremium,
            initialChecklistEdit = restoredChecklistEdit,
        ).create()
    }

    val state: StateFlow<DialogStore.State> = store.stateFlow

    init {
        stateKeeper.register(
            "checklist-edit",
            String.serializer()
        ) { ChecklistEditState.from(store.state)?.encode() }
        lifecycle.doOnDestroy { stateKeeper.unregister("checklist-edit") }
    }

    init {
        var started = false
        lifecycle.doOnStart {
            store.accept(DialogStore.Intent.ReadReceipts(true))
            if (started) {
                store.accept(DialogStore.Intent.Resume)
                onRefreshPresence()
                mediaPreloader.onVisible(store.state.messages, lastVisibleIds)
            }
            started = true
        }
        lifecycle.doOnStop {
            store.accept(DialogStore.Intent.ReadReceipts(false))
            store.accept(DialogStore.Intent.Leave)
            mediaPreloader.close()
            pickerPreloader.close()
        }
        lifecycle.doOnDestroy { store.accept(DialogStore.Intent.ReadReceipts(false)) }
        preloadScope.launch {
            DownloadSettings.state.drop(1).collect {
                mediaPreloader.onVisible(store.state.messages, lastVisibleIds)
            }
        }
        preloadScope.launch(Dispatchers.Default) {
            SystemEmojiCatalog.warm()
        }
    }

    fun onPickerDocumentsVisible(
        documentIds: List<Long>,
        visibleIds: Set<Long>,
        idle: Boolean,
    ) {
        preloadScope.launch {
            val items = PickerMediaPreload.documents(documentIds).map { item ->
                item.copy(kind = pickerDocKind(mediaRepository?.cachedFile(item.cacheKey)))
            }
            pickerPreloader.onPlan(PickerMediaPreload.plan(items, visibleIds, idle = idle))
        }
    }

    fun onPickerGifsVisible(
        gifs: List<org.monogram.core.models.SavedGif>,
        visibleIds: Set<Long>,
        idle: Boolean,
    ) {
        pickerPreloader.onPlan(
            PickerMediaPreload.plan(PickerMediaPreload.gifs(gifs), visibleIds, idle = idle),
        )
    }

    fun onPickerClosed() = pickerPreloader.close()

    private fun pickerDocKind(file: java.io.File?): PickerMediaPreload.DocKind {
        if (file == null || !file.isFile || file.length() <= 0L) return PickerMediaPreload.DocKind.Unknown
        return when {
            webmFile(file) -> PickerMediaPreload.DocKind.Webm
            gzipFile(file) -> PickerMediaPreload.DocKind.Tgs
            else -> PickerMediaPreload.DocKind.Webp
        }
    }

    fun onRefresh() = store.accept(DialogStore.Intent.Refresh)
    fun onRefreshPresence() = store.accept(DialogStore.Intent.RefreshPresence)
    fun onLoadOlder() = store.accept(DialogStore.Intent.LoadOlder)
    fun onLoadNewer() = store.accept(DialogStore.Intent.LoadNewer)
    fun onDraftFormatting(enabled: Boolean) =
        store.accept(DialogStore.Intent.SetDraftFormatting(enabled))

    fun onDraftEntities(entities: List<TextEntity>) =
        store.accept(DialogStore.Intent.SetDraftEntities(entities))

    fun onDraftChanged(value: String) = store.accept(DialogStore.Intent.DraftChanged(value))
    fun onFixLinkPreview() = store.accept(DialogStore.Intent.FixLinkPreview)
    fun onDismissLinkPreview() = store.accept(DialogStore.Intent.DismissLinkPreview)
    fun onRestoreLinkPreview() = store.accept(DialogStore.Intent.RestoreLinkPreview)
    fun onSelectLinkPreview(url: String) = store.accept(DialogStore.Intent.SelectLinkPreview(url))
    fun onAttachPhoto(path: String) = store.accept(DialogStore.Intent.AttachPhoto(path))
    fun onAttachMedia(items: List<UploadItem>) = store.accept(DialogStore.Intent.AttachMedia(items))
    fun onAppendMedia(items: List<UploadItem>) = store.accept(DialogStore.Intent.AppendMedia(items))
    fun onClearAttach() = store.accept(DialogStore.Intent.ClearAttach)
    fun onToggleAttachSheet() = store.accept(DialogStore.Intent.ToggleAttachSheet)
    fun onCloseAttachSheet() = store.accept(DialogStore.Intent.CloseAttachSheet)
    fun onToggleEmojiPanel() = store.accept(DialogStore.Intent.ToggleEmojiPanel)
    fun onSetEmojiTab(tab: String) = store.accept(DialogStore.Intent.SetEmojiTab(tab))
    fun onReloadPickerContent() = store.accept(DialogStore.Intent.ReloadPickerContent)
    fun onInsertEmoji(glyph: String) = store.accept(DialogStore.Intent.InsertEmoji(glyph))
    fun onSendUpload(
        path: String,
        kind: String,
        fileName: String = "",
        mimeType: String = "",
        duration: Int = 0,
        width: Int = 0,
        height: Int = 0,
    ) = store.accept(
        DialogStore.Intent.SendUpload(path, kind, fileName, mimeType, duration, width, height),
    )

    fun onSendAlbum(items: List<UploadItem>) =
        store.accept(DialogStore.Intent.SendAlbum(items))

    fun onOpenStickerPack(setId: Long, accessHash: Long) =
        store.accept(DialogStore.Intent.OpenStickerPack(setId, accessHash))

    fun onReply(message: Message, focusComposer: Boolean = false) =
        store.accept(DialogStore.Intent.ReplyTo(message, focusComposer = focusComposer))

    fun onRetryDraftSync() = store.accept(DialogStore.Intent.RetryDraftSync)
    fun onDiscardDraft() = store.accept(DialogStore.Intent.DiscardDraft)
    fun onClearReply() = store.accept(DialogStore.Intent.ClearReply)
    fun onEdit(message: Message) =
        store.accept(DialogStore.Intent.Edit(message))

    fun onReplaceAttachment(
        messageId: Int,
        expectedKey: String,
        item: UploadItem
    ) =
        store.accept(DialogStore.Intent.ReplaceAttachment(messageId, expectedKey, item))

    fun onCancelEdit() = store.accept(DialogStore.Intent.CancelEdit)
    fun onDelete(messageId: Int, revoke: Boolean) =
        store.accept(DialogStore.Intent.Delete(messageId, revoke))

    fun onForwardPick(message: Message) = onForwardMessages(listOf(message))
    fun onForwardMessages(messages: List<Message>) {
        val chatCanForward = state.value.canForward
        val selected = messages
            .filter { it.id.chatId == chatId && it.canForwardFrom(chatCanForward) }
            .distinctBy { it.id }
            .sortedBy { it.id.id }
        if (selected.isEmpty()) return
        if (onRequestForward != null) onRequestForward.invoke(selected)
        else store.accept(DialogStore.Intent.ForwardPick(selected.first()))
    }

    fun onClearForward() = store.accept(DialogStore.Intent.ClearForward)
    fun onForwardQuery(value: String) = store.accept(DialogStore.Intent.ForwardQuery(value))
    fun onForwardTo(peerId: PeerId) = store.accept(DialogStore.Intent.ForwardTo(peerId))
    fun onClearForwardHint() = store.accept(DialogStore.Intent.ClearForwardHint)
    fun onSendWithOptions(text: String?, options: org.monogram.core.models.MessageSendOptions) =
        store.accept(DialogStore.Intent.SendWithOptions(text, options))

    fun onRetryMediaSend(batchId: Long) = store.accept(DialogStore.Intent.RetryMediaSend(batchId))
    fun onRetryTextSend(randomId: Long) = store.accept(DialogStore.Intent.RetryTextSend(randomId))
    fun onSend(text: String? = null) = store.accept(DialogStore.Intent.Send(text))
    fun onReadOnlyBar() = store.accept(DialogStore.Intent.ReadOnlyBar)
    fun onRetryFailed() = store.accept(DialogStore.Intent.RetryFailed)
    fun onSearch(query: String) = store.accept(DialogStore.Intent.Search(query))
    fun onClearSearch() = store.accept(DialogStore.Intent.ClearSearch)
    fun onJumpToDate(epochSeconds: Int) = store.accept(DialogStore.Intent.JumpToDate(epochSeconds))
    fun onSaveScroll(messageId: Int) = store.accept(DialogStore.Intent.SaveScroll(messageId))
    fun onNextPinned() = store.accept(DialogStore.Intent.NextPinned)
    fun onOpenPinnedList() = store.accept(DialogStore.Intent.OpenPinnedList)
    fun onClosePinnedList() = store.accept(DialogStore.Intent.ClosePinnedList)
    fun onJumpToPinned(messageId: Int) = store.accept(DialogStore.Intent.JumpToPinned(messageId))
    fun onJumpToMessage(messageId: Int) = store.accept(DialogStore.Intent.JumpToMessage(messageId))
    fun onJumpUnread() = store.accept(DialogStore.Intent.JumpUnread)
    fun onJumpMention() = store.accept(DialogStore.Intent.JumpMention)
    fun onJumpUnreadReaction() = store.accept(DialogStore.Intent.JumpUnreadReaction)
    fun onJumpLatest() = store.accept(DialogStore.Intent.JumpLatest)
    fun onMarkRead() = store.accept(DialogStore.Intent.MarkRead)
    fun onLoadReadReceipts(messageId: Int) =
        store.accept(DialogStore.Intent.LoadReadReceipts(messageId))

    fun onLoadReactionUsers(messageId: Int) =
        store.accept(DialogStore.Intent.LoadReactionUsers(messageId))

    fun onLoadPollVoters(messageId: Int) =
        store.accept(DialogStore.Intent.LoadPollVoters(messageId))

    fun onVisibleNewest(messageId: Int, atLiveEdge: Boolean = false) {
        if (lifecycle.state != Lifecycle.State.STARTED && lifecycle.state != Lifecycle.State.RESUMED) return
        store.accept(DialogStore.Intent.VisibleRead(messageId, atLiveEdge))
    }

    fun onVisibleWindow(visibleIds: Set<Int>) {
        lastVisibleIds = visibleIds
        mediaPreloader.onVisible(store.state.messages, visibleIds)
        store.accept(DialogStore.Intent.VisibleWindow(visibleIds))
    }

    fun onToggleGifPicker() = store.accept(DialogStore.Intent.ToggleGifPicker)
    fun onSendSavedGif(documentId: Long) = store.accept(DialogStore.Intent.SendSavedGif(documentId))
    fun onReact(messageId: Int, emoticon: String, documentId: Long) =
        store.accept(DialogStore.Intent.React(messageId, emoticon, documentId))

    fun onOpenComments(message: Message) =
        store.accept(DialogStore.Intent.OpenComments(message))

    fun onOpenedChat() = store.accept(DialogStore.Intent.ClearPendingChat)
    fun openPendingChatIfAny() {
        val id = store.state.pendingChatId ?: return
        val messageId = store.state.pendingChatMessageId ?: 0
        onOpenedChat()
        onOpenChat(id, 0, messageId)
    }

    fun onBack() = onBack.invoke()
    fun onOpenScheduledQueue() = onOpenScheduledQueue.invoke()
    fun onOpenProfile() = onOpenProfile.invoke(chatId)
    fun onOpenPeer(peerId: PeerId) = onOpenProfile.invoke(peerId)
    fun onLoadMoreTopics() = store.accept(DialogStore.Intent.LoadMoreTopics)
    fun onToggleTopicHidden(topicId: Int, hidden: Boolean) =
        store.accept(DialogStore.Intent.ToggleTopicHidden(topicId, hidden))

    fun onRemoveChecklistItem(message: Message, itemId: Int) =
        store.accept(DialogStore.Intent.RemoveChecklistItem(message, itemId))

    fun onEditChecklistItem(message: Message, itemId: Int) =
        store.accept(DialogStore.Intent.Edit(message, itemId))

    fun onToggleChecklist(messageId: Int, itemId: Int) =
        store.accept(DialogStore.Intent.ToggleChecklist(messageId, itemId))

    fun onClosePoll(messageId: Int) = store.accept(DialogStore.Intent.ClosePoll(messageId))

    fun onVotePoll(messageId: Int, options: List<ByteArray>) =
        store.accept(DialogStore.Intent.VotePoll(messageId, options))

    fun onLoadContactImport(spec: org.monogram.core.models.ContactSendSpec) =
        store.accept(DialogStore.Intent.LoadContactImport(spec))

    fun onImportContact(spec: org.monogram.core.models.ContactSendSpec, retry: Boolean) =
        store.accept(DialogStore.Intent.ImportContact(spec, retry))

    fun onSendContact(
        spec: org.monogram.core.models.ContactSendSpec,
        options: org.monogram.core.models.MessageSendOptions = org.monogram.core.models.MessageSendOptions()
    ) =
        store.accept(DialogStore.Intent.SendContact(spec, options))

    fun onSendChecklist(
        spec: org.monogram.core.models.ChecklistSendSpec,
        options: org.monogram.core.models.MessageSendOptions = org.monogram.core.models.MessageSendOptions()
    ) =
        store.accept(DialogStore.Intent.SendChecklist(spec, options))

    fun onSendPoll(
        spec: org.monogram.core.models.PollSendSpec,
        options: org.monogram.core.models.MessageSendOptions = org.monogram.core.models.MessageSendOptions()
    ) =
        store.accept(DialogStore.Intent.SendPoll(spec, options))

    fun onAppendChecklistItems(messageId: Int, firstId: Int, titles: List<String>) =
        store.accept(DialogStore.Intent.AppendChecklistItems(messageId, firstId, titles))

    /** 0 sends a static pin; a positive period sends a live location for that many seconds. */
    fun onSendLocation(latitude: Double, longitude: Double, livePeriodSeconds: Int = 0) =
        store.accept(DialogStore.Intent.SendLocation(latitude, longitude, livePeriodSeconds))

    fun onBotButton(
        messageId: Int,
        button: org.monogram.core.models.ReplyButton,
        fromKeyboard: Boolean,
    ) = store.accept(DialogStore.Intent.BotButton(messageId, button, fromKeyboard))

    fun onClearBotNotice() = store.accept(DialogStore.Intent.ClearBotNotice)
    fun onDismissBotAlert() = store.accept(DialogStore.Intent.DismissBotAlert)
    fun onClearBotUrl() = store.accept(DialogStore.Intent.ClearBotUrl)
    fun onClearCopyText() = store.accept(DialogStore.Intent.ClearCopyText)
    fun onSendInlineResult(resultId: String) =
        store.accept(DialogStore.Intent.SendInlineResult(resultId))

    fun onLoadMoreInlineResults() =
        store.accept(DialogStore.Intent.LoadMoreInlineResults)

    fun onRetryInlineResults() =
        store.accept(DialogStore.Intent.RetryInlineResults)

    fun onSelectMention(candidate: MentionCandidate) =
        store.accept(DialogStore.Intent.SelectMention(candidate))

    fun onLoadMoreMentions() =
        store.accept(DialogStore.Intent.LoadMoreMentions)

    internal fun instantViewController(url: String, hash: Int): InstantViewController =
        instanceKeeper.getOrCreate("iv:$url:$hash") {
            InstantViewController(client, url, hash)
        }

    fun onOpenTopic(topic: ForumTopic) {
        onOpenChat(chatId, 0, ForumIo.dialogThreadId(topic.id))
    }
}
