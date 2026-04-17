package org.monogram.presentation.features.chats.currentChat.components.inputbar

import androidx.compose.runtime.Immutable
import org.monogram.domain.models.AttachMenuBotModel
import org.monogram.domain.models.BotCommandModel
import org.monogram.domain.models.BotMenuButtonModel
import org.monogram.domain.models.ChatPermissionsModel
import org.monogram.domain.models.GifModel
import org.monogram.domain.models.KeyboardButtonModel
import org.monogram.domain.models.MessageEntity
import org.monogram.domain.models.MessageModel
import org.monogram.domain.models.MessageSendOptions
import org.monogram.domain.models.PollDraft
import org.monogram.domain.models.ReplyMarkupModel
import org.monogram.domain.models.UserModel
import org.monogram.domain.repository.InlineBotResultsModel

@Immutable
data class ChatInputBarState(
    val replyMessage: MessageModel? = null,
    val editingMessage: MessageModel? = null,
    val draftText: String = "",
    val pendingMediaPaths: List<String> = emptyList(),
    val pendingDocumentPaths: List<String> = emptyList(),
    val isClosed: Boolean = false,
    val permissions: ChatPermissionsModel = ChatPermissionsModel(),
    val slowModeDelay: Int = 0,
    val slowModeDelayExpiresIn: Double = 0.0,
    val isCurrentUserRestricted: Boolean = false,
    val restrictedUntilDate: Int = 0,
    val isAdmin: Boolean = false,
    val isChannel: Boolean = false,
    val isBot: Boolean = false,
    val botCommands: List<BotCommandModel> = emptyList(),
    val botMenuButton: BotMenuButtonModel = BotMenuButtonModel.Default,
    val replyMarkup: ReplyMarkupModel? = null,
    val mentionSuggestions: List<UserModel> = emptyList(),
    val inlineBotResults: InlineBotResultsModel? = null,
    val currentInlineBotUsername: String? = null,
    val currentInlineQuery: String? = null,
    val isInlineBotLoading: Boolean = false,
    val attachBots: List<AttachMenuBotModel> = emptyList(),
    val scheduledMessages: List<MessageModel> = emptyList(),
    val isPremiumUser: Boolean = false,
    val isSecretChat: Boolean = false,
)

@Immutable
data class ChatInputBarActions(
    val onSend: (String, List<MessageEntity>, MessageSendOptions) -> Unit,
    val onStickerClick: (String) -> Unit = {},
    val onGifClick: (GifModel) -> Unit = {},
    val onAttachClick: () -> Unit = {},
    val onCameraClick: () -> Unit = {},
    val onSendVoice: (String, Int, ByteArray) -> Unit = { _, _, _ -> },
    val onCancelReply: () -> Unit = {},
    val onCancelEdit: () -> Unit = {},
    val onSaveEdit: (String, List<MessageEntity>) -> Unit = { _, _ -> },
    val onDraftChange: (String) -> Unit = {},
    val onTyping: () -> Unit = {},
    val onCancelMedia: () -> Unit = {},
    val onSendMedia: (List<String>, String, List<MessageEntity>, MessageSendOptions) -> Unit = { _, _, _, _ -> },
    val onSendDocuments: (List<String>, String, List<MessageEntity>, MessageSendOptions) -> Unit = { _, _, _, _ -> },
    val onMediaOrderChange: (List<String>) -> Unit = {},
    val onDocumentOrderChange: (List<String>) -> Unit = {},
    val onMediaClick: (String) -> Unit = {},
    val onSendPoll: (PollDraft) -> Unit = {},
    val onShowBotCommands: () -> Unit = {},
    val onReplyMarkupButtonClick: (KeyboardButtonModel) -> Unit = {},
    val onOpenMiniApp: (String, String) -> Unit = { _, _ -> },
    val onMentionQueryChange: (String?) -> Unit = {},
    val onInlineQueryChange: (String, String) -> Unit = { _, _ -> },
    val onLoadMoreInlineResults: (String) -> Unit = {},
    val onSendInlineResult: (String) -> Unit = {},
    val onInlineSwitchPm: (String, String) -> Unit = { _, _ -> },
    val onAttachBotClick: (AttachMenuBotModel) -> Unit = {},
    val onGalleryClick: () -> Unit = {},
    val onRefreshScheduledMessages: () -> Unit = {},
    val onEditScheduledMessage: (MessageModel) -> Unit = {},
    val onDeleteScheduledMessage: (MessageModel) -> Unit = {},
    val onSendScheduledNow: (MessageModel) -> Unit = {},
)

internal enum class InputBarMode {
    Composer,
    SlowMode,
    Restricted
}
