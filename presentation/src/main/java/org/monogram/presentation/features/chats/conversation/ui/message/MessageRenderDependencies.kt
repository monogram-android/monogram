package org.monogram.presentation.features.chats.conversation.ui.message

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageEntity
import org.monogram.domain.models.MessageEntityType
import org.monogram.domain.models.MessageModel
import org.monogram.domain.repository.StickerRepository
import org.monogram.presentation.core.util.AppPreferences
import org.monogram.presentation.core.util.coRunCatching

@Immutable
data class MessageRenderDependencies(
    val emojiFontFamily: FontFamily = FontFamily.Default,
    val customEmojiPaths: Map<Long, String?> = emptyMap()
)

internal val LocalMessageRenderDependencies = staticCompositionLocalOf {
    MessageRenderDependencies()
}

@Composable
internal fun rememberChatMessageRenderDependencies(
    messages: List<MessageModel>,
    appPreferences: AppPreferences = koinInject(),
    stickerRepository: StickerRepository = koinInject()
): State<MessageRenderDependencies> {
    val context = LocalContext.current
    val emojiStyle by appPreferences.emojiStyle.collectAsState()
    val emojiFontFamily = remember(context, emojiStyle) { getEmojiFontFamily(context, emojiStyle) }
    val customEmojiStickerSets by stickerRepository.customEmojiStickerSets.collectAsState()

    LaunchedEffect(stickerRepository) {
        coRunCatching { stickerRepository.loadCustomEmojiStickerSets() }
    }

    val customEmojiFileIdsById = remember(customEmojiStickerSets) {
        buildMap {
            customEmojiStickerSets.forEach { set ->
                set.stickers.forEach { sticker ->
                    val customEmojiId = sticker.customEmojiId
                    if (customEmojiId != null) {
                        put(customEmojiId, sticker.id)
                    }
                }
            }
        }
    }
    val requests = remember(messages) { collectCustomEmojiRequests(messages) }
    return produceState(
        initialValue = MessageRenderDependencies(
            emojiFontFamily = emojiFontFamily,
            customEmojiPaths = requests.explicitPaths
        ),
        emojiFontFamily,
        requests,
        customEmojiFileIdsById,
        stickerRepository
    ) {
        value = MessageRenderDependencies(
            emojiFontFamily = emojiFontFamily,
            customEmojiPaths = requests.explicitPaths
        )

        coroutineScope {
            requests.ids.forEach { customEmojiId ->
                launch {
                    val resolvedFlow =
                        customEmojiFileIdsById[customEmojiId]?.let(stickerRepository::getStickerFile)
                            ?: stickerRepository.getCustomEmojiFile(customEmojiId)
                    resolvedFlow.collectLatest { resolvedPath ->
                        val nextPath = resolvedPath ?: requests.explicitPaths[customEmojiId]
                        if (value.customEmojiPaths[customEmojiId] != nextPath) {
                            value = value.copy(
                                customEmojiPaths = value.customEmojiPaths + (customEmojiId to nextPath)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Immutable
private data class CustomEmojiRequests(
    val ids: Set<Long>,
    val explicitPaths: Map<Long, String?>
)

private fun collectCustomEmojiRequests(messages: List<MessageModel>): CustomEmojiRequests {
    val ids = LinkedHashSet<Long>()
    val explicitPaths = LinkedHashMap<Long, String?>()

    fun appendEntities(entities: List<MessageEntity>) {
        entities.forEach { entity ->
            val type = entity.type as? MessageEntityType.CustomEmoji ?: return@forEach
            ids += type.emojiId
            if (type.path != null) {
                explicitPaths[type.emojiId] = type.path
            }
        }
    }

    messages.forEach { message ->
        when (val content = message.content) {
            is MessageContent.Text -> appendEntities(content.entities)
            is MessageContent.Photo -> appendEntities(content.entities)
            is MessageContent.Video -> appendEntities(content.entities)
            is MessageContent.Document -> appendEntities(content.entities)
            is MessageContent.Audio -> appendEntities(content.entities)
            is MessageContent.Gif -> appendEntities(content.entities)
            else -> Unit
        }

        message.reactions.forEach { reaction ->
            val customEmojiId = reaction.customEmojiId ?: return@forEach
            ids += customEmojiId
            if (reaction.customEmojiPath != null) {
                explicitPaths[customEmojiId] = reaction.customEmojiPath
            }
        }
    }

    return CustomEmojiRequests(
        ids = ids,
        explicitPaths = explicitPaths
    )
}
