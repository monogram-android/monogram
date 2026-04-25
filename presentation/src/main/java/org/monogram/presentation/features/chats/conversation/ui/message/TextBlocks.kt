package org.monogram.presentation.features.chats.conversation.ui.message

import androidx.compose.runtime.Composable
import org.monogram.domain.models.MessageEntity
import org.monogram.domain.models.MessageEntityType
import org.monogram.presentation.features.chats.conversation.ui.message.model.blockFor
import org.monogram.presentation.features.chats.conversation.ui.message.model.inlineEntitiesForBlock

@Composable
internal fun TextBlocks(
    text: String,
    entities: List<MessageEntity>,
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
                entities = entities.inlineEntitiesForBlock(entity),
                isOutgoing = isOutgoing,
                expandable = false,
            )
        }
        is MessageEntityType.BlockQuoteExpandable -> {
            QuoteBlock(
                text = text blockFor entity,
                entities = entities.inlineEntitiesForBlock(entity),
                isOutgoing = isOutgoing,
                expandable = true,
            )
        }
        else -> {
            /***/
        }
    }
}