package org.monogram.presentation.features.chats.currentChat.components.pins

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import org.monogram.domain.models.MessageContent
import org.monogram.presentation.R

/**
 * Map message content to human-readable type name
 **/
@Composable
internal fun MessageContent.toTypeName(): String = when (this) {
    is MessageContent.Text -> text
    is MessageContent.Photo ->caption.ifEmpty { stringResource(R.string.chat_mapper_photo) }
    is MessageContent.Sticker -> stringResource(R.string.message_type_sticker_format, emoji)
    is MessageContent.Video -> caption.ifEmpty { stringResource(R.string.chat_mapper_video) }
    is MessageContent.VideoNote -> stringResource(R.string.message_type_video_message)
    is MessageContent.Voice -> stringResource(R.string.chat_mapper_voice)
    is MessageContent.Gif -> caption.ifEmpty { stringResource(R.string.message_type_gif) }
    is MessageContent.Document -> caption.ifEmpty { stringResource(R.string.message_type_document) }
    is MessageContent.Poll -> stringResource(R.string.message_type_poll_format, question)
    else -> stringResource(R.string.chat_mapper_message)
}