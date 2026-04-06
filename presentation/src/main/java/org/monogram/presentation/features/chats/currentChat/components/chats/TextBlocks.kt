package org.monogram.presentation.features.chats.currentChat.components.chats

import androidx.compose.runtime.Composable
import org.monogram.domain.models.MessageEntity
import org.monogram.domain.models.MessageEntityType
import org.monogram.presentation.features.chats.currentChat.components.chats.model.blockFor

@Composable
internal fun TextBlocks(
    text: String,
    entity: MessageEntity,
    isOutgoing: Boolean,
) {
    when (val type = entity.type) {
        is MessageEntityType.Pre -> {
            CodeBlock(
                text = text blockFor entity,
                language = type.language,
                isOutgoing = isOutgoing
            )
        }
        is MessageEntityType.BlockQuote -> {
            QuoteBlock(
                text = text blockFor entity,
                isOutgoing = isOutgoing,
                expandable = false,
            )
        }
        is MessageEntityType.BlockQuoteExpandable -> {
            QuoteBlock(
                text = text blockFor entity,
                isOutgoing = isOutgoing,
                expandable = true,
            )
        }
        else -> {
            /***/
        }
    }
}