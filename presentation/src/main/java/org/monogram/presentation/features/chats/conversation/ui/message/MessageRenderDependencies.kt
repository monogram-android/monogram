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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.monogram.domain.models.MessageEntity
import org.monogram.domain.models.MessageEntityType
import org.monogram.domain.models.MessageReactionModel
import org.monogram.domain.repository.StickerRepository
import org.monogram.presentation.core.util.AppPreferences
import org.monogram.presentation.core.util.coRunCatching

@Immutable
data class MessageRenderDependencies(
    val emojiFontFamily: FontFamily = FontFamily.Default,
    val customEmojiResolver: CustomEmojiPathResolver = EmptyCustomEmojiPathResolver
)

fun interface CustomEmojiPathResolver {
    @Composable
    fun resolve(
        customEmojiIds: Set<Long>,
        explicitPaths: Map<Long, String?>
    ): State<Map<Long, String?>>
}

private object EmptyCustomEmojiPathResolver : CustomEmojiPathResolver {
    @Composable
    override fun resolve(
        customEmojiIds: Set<Long>,
        explicitPaths: Map<Long, String?>
    ): State<Map<Long, String?>> = remember(explicitPaths) {
        androidx.compose.runtime.mutableStateOf(explicitPaths)
    }
}

internal val LocalMessageRenderDependencies = staticCompositionLocalOf {
    MessageRenderDependencies()
}

@Composable
internal fun rememberChatMessageRenderDependencies(
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

    val resolver = remember(stickerRepository, customEmojiFileIdsById) {
        CustomEmojiPathResolver { customEmojiIds, explicitPaths ->
            resolveCustomEmojiPaths(
                customEmojiIds = customEmojiIds,
                explicitPaths = explicitPaths,
                customEmojiFileIdsById = customEmojiFileIdsById,
                stickerRepository = stickerRepository
            )
        }
    }

    return remember(emojiFontFamily, resolver) {
        androidx.compose.runtime.mutableStateOf(
            MessageRenderDependencies(
                emojiFontFamily = emojiFontFamily,
                customEmojiResolver = resolver
            )
        )
    }
}

@Composable
private fun resolveCustomEmojiPaths(
    customEmojiIds: Set<Long>,
    explicitPaths: Map<Long, String?>,
    customEmojiFileIdsById: Map<Long, Long>,
    stickerRepository: StickerRepository
): State<Map<Long, String?>> {
    val stableIds = remember(customEmojiIds) { customEmojiIds.toSet() }
    val stableExplicitPaths = remember(explicitPaths) { explicitPaths.toMap() }
    return produceState(
        initialValue = stableExplicitPaths,
        stableIds,
        stableExplicitPaths,
        customEmojiFileIdsById,
        stickerRepository
    ) {
        value = stableExplicitPaths
        coroutineScope {
            stableIds.forEach { customEmojiId ->
                launch {
                    val resolvedFlow: Flow<String?> =
                        customEmojiFileIdsById[customEmojiId]?.let(stickerRepository::getStickerFile)
                            ?: stickerRepository.getCustomEmojiFile(customEmojiId)
                    resolvedFlow.collectLatest { resolvedPath ->
                        val nextPath = resolvedPath ?: stableExplicitPaths[customEmojiId]
                        if (value[customEmojiId] != nextPath) {
                            value = value + (customEmojiId to nextPath)
                        }
                    }
                }
            }
        }
    }
}

internal data class CustomEmojiRequests(
    val ids: Set<Long>,
    val explicitPaths: Map<Long, String?>
)

internal fun collectCustomEmojiRequests(
    entities: List<MessageEntity> = emptyList(),
    reactions: List<MessageReactionModel> = emptyList()
): CustomEmojiRequests {
    val ids = LinkedHashSet<Long>()
    val explicitPaths = LinkedHashMap<Long, String?>()

    entities.forEach { entity ->
        val type = entity.type as? MessageEntityType.CustomEmoji ?: return@forEach
        ids += type.emojiId
        if (type.path != null) {
            explicitPaths[type.emojiId] = type.path
        }
    }

    reactions.forEach { reaction ->
        val customEmojiId = reaction.customEmojiId ?: return@forEach
        ids += customEmojiId
        if (reaction.customEmojiPath != null) {
            explicitPaths[customEmojiId] = reaction.customEmojiPath
        }
    }

    return CustomEmojiRequests(ids = ids, explicitPaths = explicitPaths)
}
