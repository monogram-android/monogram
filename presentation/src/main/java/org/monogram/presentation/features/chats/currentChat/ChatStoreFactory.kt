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
                is Intent.SendMessage -> component.handleSendMessage(intent.text, intent.entities, intent.sendOptions)
                is Intent.SendSticker -> component.handleSendSticker(intent.stickerPath)
                is Intent.SendPhoto -> component.handleSendPhoto(
                    photoPath = intent.photoPath,
                    caption = intent.caption,
                    captionEntities = intent.captionEntities,
                    sendOptions = intent.sendOptions
                )

                is Intent.SendVideo -> component.handleSendVideo(
                    videoPath = intent.videoPath,
                    caption = intent.caption,
                    captionEntities = intent.captionEntities,
                    sendOptions = intent.sendOptions
                )

                is Intent.SendGif -> component.handleSendGif(intent.gif)
                is Intent.SendGifFile -> component.handleSendGifFile(
                    path = intent.path,
                    caption = intent.caption,
                    captionEntities = intent.captionEntities,
                    sendOptions = intent.sendOptions
                )

                is Intent.SendAlbum -> component.handleSendAlbum(
                    paths = intent.paths,
                    caption = intent.caption,
                    captionEntities = intent.captionEntities,
                    sendOptions = intent.sendOptions
                )

                is Intent.SendVoice -> component.handleSendVoice(intent.path, intent.duration, intent.waveform)
                is Intent.RefreshScheduledMessages -> component.loadScheduledMessages()
                is Intent.SendScheduledNow -> component.handleSendScheduledNow(intent.message)
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
                is Intent.PinMessage -> component.handlePinMessage(intent.message)

                is Intent.UnpinMessage -> component.handleUnpinMessage(intent.message)

                is Intent.PinnedMessageClick -> component.handlePinnedMessageClick(intent.message)
                is Intent.ShowAllPinnedMessages -> {
                    component._state.update {
                        it.copy(
                            showPinnedMessagesList = true,
                            isLoadingPinnedMessages = true,
                            allPinnedMessages = emptyList()
                        )
                    }
                    component.loadAllPinnedMessages()
                }

                is Intent.DismissPinnedMessages -> {
                    component._state.update {
                        it.copy(
                            showPinnedMessagesList = false,
                            isLoadingPinnedMessages = false
                        )
                    }
                }
                is Intent.ScrollToMessageConsumed -> component._state.update { it.copy(scrollToMessageId = null) }
                is Intent.ScrollToBottom -> component.scrollToBottomInternal()
                is Intent.DownloadFile -> component.handleDownloadFile(intent.fileId)
                is Intent.DownloadHighRes -> component.handleDownloadHighRes(intent.messageId)

                is Intent.CancelDownloadFile -> component.handleCancelDownloadFile(intent.fileId)

                is Intent.UpdateScrollPosition -> component._state.update {
                    if (it.currentScrollMessageId == intent.messageId) it else it.copy(currentScrollMessageId = intent.messageId)
                }
                is Intent.BottomReached -> component._state.update {
                    if (it.isAtBottom == intent.isAtBottom) it else it.copy(isAtBottom = intent.isAtBottom)
                }
                is Intent.HighlightConsumed -> component._state.update { it.copy(highlightedMessageId = null) }
                is Intent.Typing -> { /* Handle typing */
                }

                is Intent.SendReaction -> component.handleSendReaction(intent.messageId, intent.reaction)
                is Intent.ToggleMessageSelection -> component.handleToggleMessageSelection(intent.messageId)
                is Intent.ClearSelection -> component.handleClearSelection()
                is Intent.ClearMessages -> component.handleClearMessages()

                is Intent.DeleteSelectedMessages -> component.handleDeleteSelectedMessages(intent.revoke)
                is Intent.CopySelectedMessages -> component.handleCopySelectedMessages(intent.localClipboard)
                is Intent.StickerClick -> component.handleStickerClick(intent.setId)
                is Intent.DismissStickerSet -> component._state.update { it.copy(selectedStickerSet = null) }
                is Intent.AddToGifs -> component.handleAddToGifs(intent.path)
                is Intent.PollOptionClick -> component.handlePollOptionClick(intent.messageId, intent.optionId)
                is Intent.RetractVote -> component.handleRetractVote(intent.messageId)
                is Intent.ShowVoters -> component.handleShowVoters(intent.messageId, intent.optionId)
                is Intent.DismissVoters -> component._state.update { it.copy(showPollVoters = false) }
                is Intent.ClosePoll -> component.handleClosePoll(intent.messageId)
                is Intent.TopicClick -> component.handleTopicClick(intent.topicId)

                is Intent.OpenInstantView -> component._state.update { it.copy(instantViewUrl = intent.url) }
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
                        fullScreenImageMessageIds = intent.messageIds,
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
                        fullScreenImageMessageIds = emptyList(),
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
                        fullScreenImages = null,
                        fullScreenImageMessageIds = emptyList()
                    )
                }

                is Intent.DismissVideo -> component._state.update {
                    it.copy(
                        fullScreenVideoPath = null,
                        fullScreenVideoMessageId = null,
                        fullScreenVideoCaption = null
                    )
                }

                is Intent.AddToAdBlockWhitelist -> component.handleAddToAdBlockWhitelist()
                is Intent.RemoveFromAdBlockWhitelist -> component.handleRemoveFromAdBlockWhitelist()
                is Intent.ToggleMute -> component.handleToggleMute()

                is Intent.SearchToggle -> component._state.update {
                    it.copy(
                        isSearchActive = !it.isSearchActive,
                        searchQuery = ""
                    )
                }

                is Intent.SearchQueryChange -> component._state.update { it.copy(searchQuery = intent.query) }
                is Intent.ClearHistory -> component.handleClearHistory()

                is Intent.DeleteChat -> component.handleDeleteChat()

                is Intent.Report -> component._state.update { it.copy(showReportDialog = true) }
                is Intent.ReportMessage -> component.handleReportMessage(intent.message)

                is Intent.ReportReasonSelected -> component.handleReportReasonSelected(intent.reason)

                is Intent.DismissReportDialog -> component._state.update { it.copy(showReportDialog = false) }
                is Intent.CopyLink -> component.handleCopyLink(intent.localClipboard)

                is Intent.ScrollToMessage -> component.scrollToMessageInternal(intent.messageId)
                is Intent.BotCommandClick -> component.handleBotCommandClick(intent.command)

                is Intent.ShowBotCommands -> component._state.update { it.copy(showBotCommands = true) }
                is Intent.DismissBotCommands -> component._state.update { it.copy(showBotCommands = false) }
                is Intent.CommentsClick -> component.handleCommentsClick(intent.messageId)

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

                is Intent.JoinChat -> component.handleJoinChat()

                is Intent.BlockUser -> component.handleBlockUser(intent.userId)

                is Intent.UnblockUser -> component.handleUnblockUser(intent.userId)

                is Intent.RestrictUser -> component._state.update { it.copy(restrictUserId = intent.userId) }
                is Intent.ConfirmRestrict -> component.handleConfirmRestrict(intent.permissions, intent.untilDate)

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
