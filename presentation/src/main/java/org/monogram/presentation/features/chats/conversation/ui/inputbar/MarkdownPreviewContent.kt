package org.monogram.presentation.features.chats.conversation.ui.inputbar

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import org.monogram.domain.models.MessageEntity
import org.monogram.presentation.features.chats.conversation.ui.message.BigEmojiContent
import org.monogram.presentation.features.chats.conversation.ui.message.MessageText
import org.monogram.presentation.features.chats.conversation.ui.message.MessageTextRenderData

@Composable
internal fun MarkdownPreviewContent(
    rawText: String,
    entities: List<MessageEntity>,
    renderData: MessageTextRenderData,
    style: TextStyle,
    color: Color,
    emojiFontFamily: FontFamily,
    modifier: Modifier = Modifier,
    onSpoilerClick: (Int) -> Unit = {}
) {
    val previewStyle = style.copy(
        fontSize = if (renderData.isBigEmoji) (style.fontSize.value * 5f).sp else style.fontSize,
        lineHeight = if (renderData.isBigEmoji) (style.fontSize.value * 5.5f).sp else style.lineHeight
    )

    if (renderData.isBigEmoji && renderData.bigEmojiItems.isNotEmpty()) {
        BigEmojiContent(
            items = renderData.bigEmojiItems,
            sizeDp = style.fontSize.value * 5f,
            modifier = modifier,
            emojiFontFamily = emojiFontFamily
        )
    } else {
        MessageText(
            text = renderData.annotatedText,
            rawText = rawText,
            inlineContent = renderData.inlineContent,
            entities = entities,
            style = previewStyle,
            color = color,
            modifier = modifier,
            onSpoilerClick = onSpoilerClick
        )
    }
}
