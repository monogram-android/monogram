package org.monogram.feature.dialog

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.router.slot.ChildSlot
import com.arkivanov.decompose.router.slot.SlotNavigation
import com.arkivanov.decompose.router.slot.activate
import com.arkivanov.decompose.router.slot.childSlot
import com.arkivanov.decompose.router.slot.dismiss
import com.arkivanov.decompose.value.Value
import com.arkivanov.essenty.instancekeeper.getOrCreate
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.arkivanov.essenty.lifecycle.doOnStart
import com.arkivanov.mvikotlin.core.instancekeeper.getStore
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.stateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.serializer
import org.monogram.core.database.OfflineWarmup
import org.monogram.core.database.SessionMetadataStore
import org.monogram.core.markup.KotlinMarkupParser
import org.monogram.core.markup.MarkupParser
import org.monogram.core.models.ForumIo
import org.monogram.core.models.ForumTopic
import org.monogram.core.models.PeerId
import org.monogram.core.models.UploadItem
import org.monogram.feature.dialog.ui.InstantViewController
import org.monogram.network.bridge.MtprotoClient
import org.monogram.network.http.MediaRepository

@OptIn(ExperimentalCoroutinesApi::class)
class DialogComponent(
    componentContext: ComponentContext,
    storeFactory: StoreFactory,
    val client: MtprotoClient,
    warmup: OfflineWarmup?,
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
    private val onRequestForward: ((List<org.monogram.core.models.Message>) -> Unit)? = null,
) : ComponentContext by componentContext {

    /** Dialog identity for saveable state. */
    val stateKey: String get() = dialogStateKey(chatId.value, threadTopMsgId, jumpToMessageId)

    private val preloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mediaPreloader = DialogMediaPreloader(
        chatId = chatId,
        mediaRepository = mediaRepository,
        prefetchInstantView = { url, hash -> instantViewController(url, hash).load() },
        scope = preloadScope,
    )

    init {
        lifecycle.doOnStart { client.setDialogForeground(true) }
        lifecycle.doOnDestroy {
            client.setDialogForeground(false)
            mediaPreloader.close()
            preloadScope.cancel()
        }
    }

    class MarkdownEditorChild(componentContext: ComponentContext) : ComponentContext by componentContext

    private val editorNavigation = SlotNavigation<Unit>()
    val editorSlot: Value<ChildSlot<Unit, MarkdownEditorChild>> = childSlot(
        source = editorNavigation,
        serializer = Unit.serializer(),
        key = "markdown-editor",
        handleBackButton = true,
    ) { _, ctx -> MarkdownEditorChild(ctx) }

    fun openMarkdownEditor() = editorNavigation.activate(Unit)
    fun closeMarkdownEditor() = editorNavigation.dismiss()

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
        ).create()
    }

    val state: StateFlow<DialogStore.State> = store.stateFlow

    init {
        var started = false
        lifecycle.doOnStart {
            if (started) onRefreshPresence()
            started = true
        }
    }

    fun onRefresh() = store.accept(DialogStore.Intent.Refresh)
    fun onRefreshPresence() = store.accept(DialogStore.Intent.RefreshPresence)
    fun onLoadOlder() = store.accept(DialogStore.Intent.LoadOlder)
    fun onLoadNewer() = store.accept(DialogStore.Intent.LoadNewer)
    fun onDraftChanged(value: String) = store.accept(DialogStore.Intent.DraftChanged(value))
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
    fun onReply(message: org.monogram.core.models.Message) =
        store.accept(DialogStore.Intent.ReplyTo(message))
    fun onClearReply() = store.accept(DialogStore.Intent.ClearReply)
    fun onEdit(message: org.monogram.core.models.Message) =
        store.accept(DialogStore.Intent.Edit(message))
    fun onCancelEdit() = store.accept(DialogStore.Intent.CancelEdit)
    fun onDelete(messageId: Int, revoke: Boolean) =
        store.accept(DialogStore.Intent.Delete(messageId, revoke))
    fun onForwardPick(message: org.monogram.core.models.Message) = onForwardMessages(listOf(message))
    fun onForwardMessages(messages: List<org.monogram.core.models.Message>) {
        if (!state.value.canForward) return
        if (messages.any {
            it.id.chatId != chatId || it.id.id <= 0 || it.pending || it.noforwards || it.mediaKind == "service"
        }) return
        val selected = messages.distinctBy { it.id }.sortedBy { it.id.id }
        if (selected.isEmpty()) return
        if (onRequestForward != null) onRequestForward.invoke(selected)
        else store.accept(DialogStore.Intent.ForwardPick(selected.first()))
    }
    fun onClearForward() = store.accept(DialogStore.Intent.ClearForward)
    fun onForwardQuery(value: String) = store.accept(DialogStore.Intent.ForwardQuery(value))
    fun onForwardTo(peerId: PeerId) = store.accept(DialogStore.Intent.ForwardTo(peerId))
    fun onClearForwardHint() = store.accept(DialogStore.Intent.ClearForwardHint)
    fun onSend(text: String? = null) = store.accept(DialogStore.Intent.Send(text))
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
    fun onLoadReadReceipts(messageId: Int) = store.accept(DialogStore.Intent.LoadReadReceipts(messageId))
    fun onLoadReactionUsers(messageId: Int) =
        store.accept(DialogStore.Intent.LoadReactionUsers(messageId))
    fun onLoadPollVoters(messageId: Int) =
        store.accept(DialogStore.Intent.LoadPollVoters(messageId))
    fun onVisibleNewest(messageId: Int, atLiveEdge: Boolean = false) =
        store.accept(DialogStore.Intent.VisibleRead(messageId, atLiveEdge))
    fun onVisibleWindow(visibleIds: Set<Int>) {
        mediaPreloader.onVisible(store.state.messages, visibleIds)
        store.accept(DialogStore.Intent.VisibleWindow(visibleIds))
    }
    fun onToggleGifPicker() = store.accept(DialogStore.Intent.ToggleGifPicker)
    fun onSendSavedGif(documentId: Long) = store.accept(DialogStore.Intent.SendSavedGif(documentId))
    fun onReact(messageId: Int, emoticon: String, documentId: Long) =
        store.accept(DialogStore.Intent.React(messageId, emoticon, documentId))
    fun onOpenComments(message: org.monogram.core.models.Message) =
        store.accept(DialogStore.Intent.OpenComments(message))
    fun onOpenedChat() = store.accept(DialogStore.Intent.ClearPendingChat)
    fun openPendingChatIfAny() {
        val id = store.state.pendingChatId ?: return
        val messageId = store.state.pendingChatMessageId ?: 0
        onOpenedChat()
        onOpenChat(id, 0, messageId)
    }
    fun onBack() = onBack.invoke()
    fun onOpenProfile() = onOpenProfile.invoke(chatId)
    fun onOpenPeer(peerId: PeerId) = onOpenProfile.invoke(peerId)
    fun onLoadMoreTopics() = store.accept(DialogStore.Intent.LoadMoreTopics)
    fun onToggleChecklist(messageId: Int, itemId: Int) =
        store.accept(DialogStore.Intent.ToggleChecklist(messageId, itemId))
    fun onVotePoll(messageId: Int, options: List<ByteArray>) =
        store.accept(DialogStore.Intent.VotePoll(messageId, options))
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
