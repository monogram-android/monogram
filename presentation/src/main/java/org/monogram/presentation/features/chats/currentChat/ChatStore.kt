package org.monogram.presentation.features.chats.currentChat

import androidx.compose.ui.platform.Clipboard
import com.arkivanov.mvikotlin.core.store.Store
import org.monogram.domain.models.ChatPermissionsModel
import org.monogram.domain.models.ChatViewportCacheEntry
import org.monogram.domain.models.GifModel
import org.monogram.domain.models.InlineKeyboardButtonModel
import org.monogram.domain.models.KeyboardButtonModel
import org.monogram.domain.models.MessageEntity
import org.monogram.domain.models.MessageModel
import org.monogram.domain.models.MessageSendOptions
import org.monogram.domain.models.PollDraft
import java.io.File

interface ChatStore : Store<ChatStore.Intent, ChatComponent.State, ChatStore.Label> {

    sealed class Intent {
        data class UpdateState(val state: ChatComponent.State) : Intent()
        data class SendMessage(
            val text: String,
            val entities: List<MessageEntity> = emptyList(),
            val sendOptions: MessageSendOptions = MessageSendOptions()
        ) : Intent()

        data class SendSticker(val stickerPath: String) : Intent()
        data class SendPhoto(
            val photoPath: String,
            val caption: String = "",
            val captionEntities: List<MessageEntity> = emptyList(),
            val sendOptions: MessageSendOptions = MessageSendOptions()
        ) : Intent()

        data class SendVideo(
            val videoPath: String,
            val caption: String = "",
            val captionEntities: List<MessageEntity> = emptyList(),
            val sendOptions: MessageSendOptions = MessageSendOptions()
        ) : Intent()

        data class SendGif(val gif: GifModel) : Intent()
        data class SendDocument(
            val path: String,
            val caption: String = "",
            val captionEntities: List<MessageEntity> = emptyList(),
            val sendOptions: MessageSendOptions = MessageSendOptions()
        ) : Intent()

        data class SendPoll(
            val poll: PollDraft,
            val sendOptions: MessageSendOptions = MessageSendOptions()
        ) : Intent()

        data class SendGifFile(
            val path: String,
            val caption: String = "",
            val captionEntities: List<MessageEntity> = emptyList(),
            val sendOptions: MessageSendOptions = MessageSendOptions()
        ) : Intent()

        data class SendAlbum(
            val paths: List<String>,
            val caption: String = "",
            val captionEntities: List<MessageEntity> = emptyList(),
            val sendOptions: MessageSendOptions = MessageSendOptions()
        ) : Intent()

        data class SendVoice(val path: String, val duration: Int, val waveform: ByteArray) : Intent()
        object RefreshScheduledMessages : Intent()
        data class SendScheduledNow(val message: MessageModel) : Intent()
        object LoadMore : Intent()
        object LoadNewer : Intent()
        object BackClicked : Intent()
        object ProfileClicked : Intent()
        data class MessageClicked(val id: Long) : Intent()
        data class MessageVisible(val messageId: Long) : Intent()
        data class ReplyMessage(val message: MessageModel) : Intent()
        object CancelReply : Intent()
        data class VideoRecorded(val file: File) : Intent()
        data class ForwardMessage(val message: MessageModel) : Intent()
        object ForwardSelectedMessages : Intent()
        data class RepeatMessage(val message: MessageModel) : Intent()
        data class DeleteMessage(val message: MessageModel, val revoke: Boolean = false) : Intent()
        data class EditMessage(val message: MessageModel) : Intent()
        object CancelEdit : Intent()
        data class SaveEditedMessage(val text: String, val entities: List<MessageEntity> = emptyList()) : Intent()
        data class DraftChange(val text: String) : Intent()
        data class PinMessage(val message: MessageModel) : Intent()
        data class UnpinMessage(val message: MessageModel) : Intent()
        data class PinnedMessageClick(val message: MessageModel? = null) : Intent()
        object ShowAllPinnedMessages : Intent()
        object DismissPinnedMessages : Intent()
        object ScrollToMessageConsumed : Intent()
        object ScrollCommandConsumed : Intent()
        object ScrollToBottom : Intent()
        data class DownloadFile(val fileId: Int) : Intent()
        data class DownloadHighRes(val messageId: Long) : Intent()
        data class CancelDownloadFile(val fileId: Int) : Intent()
        data class UpdateScrollPosition(val messageId: Long) : Intent()
        data class UpdateViewport(val viewport: ChatViewportCacheEntry) : Intent()
        data class BottomReached(val isAtBottom: Boolean) : Intent()
        object HighlightConsumed : Intent()
        object Typing : Intent()
        data class SendReaction(val messageId: Long, val reaction: String) : Intent()
        data class ToggleMessageSelection(val messageId: Long) : Intent()
        object ClearSelection : Intent()
        object ClearMessages : Intent()
        data class DeleteSelectedMessages(val revoke: Boolean = false) : Intent()
        data class CopySelectedMessages(val localClipboard: Clipboard) : Intent()
        data class StickerClick(val setId: Long) : Intent()
        object DismissStickerSet : Intent()
        data class AddToGifs(val path: String) : Intent()
        data class PollOptionClick(val messageId: Long, val optionId: Int) : Intent()
        data class RetractVote(val messageId: Long) : Intent()
        data class ShowVoters(val messageId: Long, val optionId: Int) : Intent()
        object DismissVoters : Intent()
        data class ClosePoll(val messageId: Long) : Intent()
        data class TopicClick(val topicId: Int) : Intent()
        data class OpenInstantView(val url: String) : Intent()
        object DismissInstantView : Intent()
        data class OpenYouTube(val url: String) : Intent()
        object DismissYouTube : Intent()
        data class OpenMiniApp(val url: String, val name: String, val botUserId: Long = 0L) : Intent()
        object DismissMiniApp : Intent()
        object AcceptMiniAppTOS : Intent()
        object DismissMiniAppTOS : Intent()
        data class OpenWebView(val url: String) : Intent()
        object DismissWebView : Intent()
        data class OpenImages(
            val images: List<String>,
            val captions: List<String?>,
            val startIndex: Int,
            val messageId: Long? = null,
            val messageIds: List<Long> = emptyList()
        ) : Intent()

        object DismissImages : Intent()
        data class OpenVideo(val path: String? = null, val messageId: Long? = null, val caption: String? = null) :
            Intent()

        object DismissVideo : Intent()
        object AddToAdBlockWhitelist : Intent()
        object RemoveFromAdBlockWhitelist : Intent()
        object ToggleMute : Intent()
        object SearchToggle : Intent()
        data class SearchQueryChange(val query: String) : Intent()
        object ClearHistory : Intent()
        object DeleteChat : Intent()
        object Report : Intent()
        data class ReportMessage(val message: MessageModel) : Intent()
        data class ReportReasonSelected(val reason: String) : Intent()
        object DismissReportDialog : Intent()
        data class CopyLink(val localClipboard: Clipboard) : Intent()
        data class ScrollToMessage(val messageId: Long) : Intent()
        data class BotCommandClick(val command: String) : Intent()
        object ShowBotCommands : Intent()
        object DismissBotCommands : Intent()
        data class CommentsClick(val messageId: Long) : Intent()
        data class ReplyMarkupButtonClick(
            val messageId: Long,
            val button: InlineKeyboardButtonModel,
            val botUserId: Long
        ) : Intent()

        data class KeyboardButtonClick(
            val messageId: Long,
            val button: KeyboardButtonModel,
            val botUserId: Long
        ) : Intent()

        data class LinkClick(val url: String) : Intent()
        data class OpenInvoice(val slug: String? = null, val messageId: Long? = null) : Intent()
        data class DismissInvoice(val status: String) : Intent()
        data class MentionQueryChange(val query: String?) : Intent()
        object JoinChat : Intent()
        data class BlockUser(val userId: Long) : Intent()
        data class UnblockUser(val userId: Long) : Intent()
        data class RestrictUser(val userId: Long, val permissions: ChatPermissionsModel) : Intent()
        data class ConfirmRestrict(val permissions: ChatPermissionsModel, val untilDate: Int) : Intent()
        object DismissRestrictDialog : Intent()
        data class InlineQueryChange(val botUsername: String, val query: String) : Intent()
        data class LoadMoreInlineResults(val offset: String) : Intent()
        data class SendInlineResult(val resultId: String) : Intent()
    }

    sealed class Label {
        object Back : Label()
        data class Profile(val id: Long) : Label()
        data class Forward(val chatId: Long, val messageIds: List<Long>) : Label()
        data class Link(val url: String) : Label()
    }
}
