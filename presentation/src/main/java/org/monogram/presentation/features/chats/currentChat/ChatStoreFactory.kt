package org.monogram.presentation.features.chats.currentChat

import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import kotlinx.coroutines.flow.update
import org.monogram.presentation.features.chats.currentChat.ChatStore.Intent
import org.monogram.presentation.features.chats.currentChat.ChatStore.Label
import org.monogram.presentation.features.chats.currentChat.impl.*

class ChatStoreFactory(
    private val storeFactory: StoreFactory,
    private val component: DefaultChatComponent
) {

    fun create(): ChatStore =
        object : ChatStore, Store<Intent, ChatComponent.State, Label> by storeFactory.create(
            name = "ChatStore",
            initialState = component._state.value,
            executorFactory = ::ExecutorImpl,
            reducer = ReducerImpl
        ) {}

    private inner class ExecutorImpl : CoroutineExecutor<Intent, Nothing, ChatComponent.State, Message, Label>() {
        override fun executeIntent(intent: Intent) {
            when (intent) {
                is Intent.UpdateState -> dispatch(Message.UpdateState(intent.state))
                is Intent.SendMessage -> component.handleSendMessage(intent.text, intent.entities)
                is Intent.SendSticker -> component.handleSendSticker(intent.stickerPath)
                is Intent.SendPhoto -> component.handleSendPhoto(intent.photoPath, intent.caption)
                is Intent.SendVideo -> component.handleSendVideo(intent.videoPath, intent.caption)
                is Intent.SendGif -> component.handleSendGif(intent.gif)
                is Intent.SendGifFile -> component.handleSendGifFile(intent.path, intent.caption)
                is Intent.SendAlbum -> component.handleSendAlbum(intent.paths, intent.caption)
                is Intent.SendVoice -> component.handleSendVoice(intent.path, intent.duration, intent.waveform)
                is Intent.LoadMore -> component.loadMoreMessages()
                is Intent.LoadNewer -> component.loadNewerMessages()
                is Intent.BackClicked -> publish(Label.Back)
                is Intent.ProfileClicked -> component.chatId.let { publish(Label.Profile(it)) }
                is Intent.MessageClicked -> { /* Handle message click if needed */
                }

                is Intent.MessageVisible -> component.handleMessageVisible(intent.messageId)
                is Intent.ReplyMessage -> component._state.update {
                    it.copy(
                        replyMessage = intent.message,
                        editingMessage = null
                    )
                }

                is Intent.CancelReply -> component._state.update { it.copy(replyMessage = null) }
                is Intent.VideoRecorded -> component.handleVideoRecorded(intent.file)
                is Intent.ForwardMessage -> publish(Label.Forward(component.chatId, listOf(intent.message.id)))
                is Intent.ForwardSelectedMessages -> {
                    val selectedIds = component._state.value.selectedMessageIds.toList()
                    if (selectedIds.isNotEmpty()) {
                        publish(Label.Forward(component.chatId, selectedIds))
                        component.handleClearSelection()
                    }
                }

                is Intent.DeleteMessage -> component.handleDeleteMessage(intent.message, intent.revoke)
                is Intent.EditMessage -> component._state.update {
                    it.copy(
                        editingMessage = intent.message,
                        replyMessage = null,
                        draftText = ""
                    )
                }

                is Intent.CancelEdit -> component._state.update { it.copy(editingMessage = null) }
                is Intent.SaveEditedMessage -> component.handleSaveEditedMessage(intent.text, intent.entities)
                is Intent.DraftChange -> component.handleDraftChange(intent.text)
                is Intent.PinMessage -> { /* Handle pin */
                }

                is Intent.UnpinMessage -> { /* Handle unpin */
                }

                is Intent.PinnedMessageClick -> component.handlePinnedMessageClick(intent.message)
                is Intent.ShowAllPinnedMessages -> component._state.update { it.copy(showPinnedMessagesList = true) }
                is Intent.DismissPinnedMessages -> component._state.update { it.copy(showPinnedMessagesList = false) }
                is Intent.ScrollToMessageConsumed -> component._state.update { it.copy(scrollToMessageId = null) }
                is Intent.ScrollToBottom -> component.scrollToBottomInternal()
                is Intent.DownloadFile -> component.handleDownloadFile(intent.fileId)
                is Intent.DownloadHighRes -> component.handleDownloadHighRes(intent.messageId)

                is Intent.CancelDownloadFile -> component.handleCancelDownloadFile(intent.fileId)

                is Intent.UpdateScrollPosition -> component._state.update { it.copy(currentScrollMessageId = intent.messageId) }
                is Intent.BottomReached -> component._state.update { it.copy(isAtBottom = intent.isAtBottom) }
                is Intent.HighlightConsumed -> component._state.update { it.copy(highlightedMessageId = null) }
                is Intent.Typing -> { /* Handle typing */
                }

                is Intent.SendReaction -> component.handleSendReaction(intent.messageId, intent.reaction)
                is Intent.ToggleMessageSelection -> component.handleToggleMessageSelection(intent.messageId)
                is Intent.ClearSelection -> component.handleClearSelection()
                is Intent.ClearMessages -> { /* Handle clear messages */
                }

                is Intent.DeleteSelectedMessages -> component.handleDeleteSelectedMessages(intent.revoke)
                is Intent.CopySelectedMessages -> component.handleCopySelectedMessages(intent.clipboardManager)
                is Intent.StickerClick -> component.handleStickerClick(intent.setId)
                is Intent.DismissStickerSet -> component._state.update { it.copy(selectedStickerSet = null) }
                is Intent.AddToGifs -> component.handleStickerClick(0) // Placeholder
                is Intent.PollOptionClick -> component.handlePollOptionClick(intent.messageId, intent.optionId)
                is Intent.RetractVote -> component.handleRetractVote(intent.messageId)
                is Intent.ShowVoters -> component.handleShowVoters(intent.messageId, intent.optionId)
                is Intent.DismissVoters -> component._state.update { it.copy(showPollVoters = false) }
                is Intent.ClosePoll -> component.handleClosePoll(intent.messageId)
                is Intent.TopicClick -> { /* Handle topic click */
                }

                is Intent.OpenInstantView -> publish(Label.Link(intent.url))
                is Intent.DismissInstantView -> component._state.update { it.copy(instantViewUrl = null) }
                is Intent.OpenYouTube -> publish(Label.Link(intent.url))
                is Intent.DismissYouTube -> component._state.update { it.copy(youtubeUrl = null) }
                is Intent.OpenMiniApp -> component.handleOpenMiniApp(intent.url, intent.name, intent.botUserId)
                is Intent.DismissMiniApp -> component._state.update { it.copy(miniAppUrl = null) }
                is Intent.AcceptMiniAppTOS -> component.handleAcceptMiniAppTOS()
                is Intent.DismissMiniAppTOS -> component.handleDismissMiniAppTOS()
                is Intent.OpenWebView -> publish(Label.Link(intent.url))
                is Intent.DismissWebView -> component._state.update { it.copy(webViewUrl = null) }
                is Intent.OpenImages -> component._state.update {
                    it.copy(
                        fullScreenImages = intent.images,
                        fullScreenCaptions = intent.captions,
                        fullScreenStartIndex = intent.startIndex,
                        fullScreenVideoMessageId = intent.messageId,
                        fullScreenVideoPath = null,
                        fullScreenVideoCaption = null
                    )
                }

                is Intent.DismissImages -> component._state.update {
                    it.copy(
                        fullScreenImages = null,
                        fullScreenVideoMessageId = null,
                        fullScreenVideoPath = null,
                        fullScreenVideoCaption = null
                    )
                }

                is Intent.OpenVideo -> component._state.update {
                    it.copy(
                        fullScreenVideoPath = intent.path,
                        fullScreenVideoMessageId = intent.messageId,
                        fullScreenVideoCaption = intent.caption,
                        fullScreenImages = null
                    )
                }

                is Intent.DismissVideo -> component._state.update {
                    it.copy(
                        fullScreenVideoPath = null,
                        fullScreenVideoMessageId = null,
                        fullScreenVideoCaption = null
                    )
                }

                is Intent.AddToAdBlockWhitelist -> component.onAddToAdBlockWhitelist()
                is Intent.RemoveFromAdBlockWhitelist -> component.onRemoveFromAdBlockWhitelist()
                is Intent.ToggleMute -> { /* Handle mute */
                }

                is Intent.SearchToggle -> component._state.update {
                    it.copy(
                        isSearchActive = !it.isSearchActive,
                        searchQuery = ""
                    )
                }

                is Intent.SearchQueryChange -> component._state.update { it.copy(searchQuery = intent.query) }
                is Intent.ClearHistory -> { /* Handle clear history */
                }

                is Intent.DeleteChat -> { /* Handle delete chat */
                }

                is Intent.Report -> component._state.update { it.copy(showReportDialog = true) }
                is Intent.ReportMessage -> { /* Handle report message */
                }

                is Intent.ReportReasonSelected -> { /* Handle report reason */
                }

                is Intent.DismissReportDialog -> component._state.update { it.copy(showReportDialog = false) }
                is Intent.CopyLink -> { /* Handle copy link */
                }

                is Intent.ScrollToMessage -> component.scrollToMessageInternal(intent.messageId)
                is Intent.BotCommandClick -> { /* Handle bot command */
                }

                is Intent.ShowBotCommands -> component._state.update { it.copy(showBotCommands = true) }
                is Intent.DismissBotCommands -> component._state.update { it.copy(showBotCommands = false) }
                is Intent.CommentsClick -> { /* Handle comments click */
                }

                is Intent.ReplyMarkupButtonClick -> component.handleReplyMarkupButtonClick(
                    intent.messageId,
                    intent.button,
                    intent.botUserId
                )

                is Intent.KeyboardButtonClick -> component.handleKeyboardButtonClick(
                    intent.messageId,
                    intent.button,
                    intent.botUserId
                )

                is Intent.LinkClick -> publish(Label.Link(intent.url))
                is Intent.OpenInvoice -> component.handleOpenInvoice(intent.slug, intent.messageId)
                is Intent.DismissInvoice -> component.handleDismissInvoice(intent.status)
                is Intent.MentionQueryChange -> component.handleMentionQueryChange(
                    query = intent.query,
                    allMembers = component.allMembers,
                    onMembersUpdated = { component.allMembers = it }
                )

                is Intent.JoinChat -> { /* Handle join chat */
                }

                is Intent.BlockUser -> { /* Handle block user */
                }

                is Intent.UnblockUser -> { /* Handle unblock user */
                }

                is Intent.RestrictUser -> component._state.update { it.copy(restrictUserId = intent.userId) }
                is Intent.ConfirmRestrict -> { /* Handle confirm restrict */
                }

                is Intent.DismissRestrictDialog -> component._state.update { it.copy(restrictUserId = null) }
                is Intent.InlineQueryChange -> component.handleInlineQueryChange(intent.botUsername, intent.query)
                is Intent.LoadMoreInlineResults -> component.handleLoadMoreInlineResults(intent.offset)
                is Intent.SendInlineResult -> component.handleSendInlineResult(intent.resultId)
            }
        }
    }

    private object ReducerImpl : Reducer<ChatComponent.State, Message> {
        override fun ChatComponent.State.reduce(msg: Message): ChatComponent.State =
            when (msg) {
                is Message.UpdateState -> msg.state
            }
    }

    sealed class Message {
        data class UpdateState(val state: ChatComponent.State) : Message()
    }
}
