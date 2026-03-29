package org.monogram.presentation.features.chats.currentChat.components.chats

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.koin.compose.koinInject
import org.monogram.domain.models.MessageReactionModel
import org.monogram.domain.repository.StickerRepository
import org.monogram.presentation.core.ui.Avatar
import org.monogram.presentation.core.util.AppPreferences
import org.monogram.presentation.core.util.coRunCatching
import org.monogram.presentation.features.chats.currentChat.components.VideoPlayerPool
import org.monogram.presentation.features.stickers.ui.view.StickerImage

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MessageReactionsView(
    reactions: List<MessageReactionModel>,
    onReactionClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    stickerRepository: StickerRepository = koinInject(),
    appPreferences: AppPreferences = koinInject(),
    videoPlayerPool: VideoPlayerPool = koinInject()
) {
    val context = LocalContext.current
    val emojiStyle by appPreferences.emojiStyle.collectAsState()
    val emojiFontFamily = remember(context, emojiStyle) { getEmojiFontFamily(context, emojiStyle) }
    val customEmojiStickerSets by stickerRepository.customEmojiStickerSets.collectAsState()

    LaunchedEffect(Unit) {
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

    AnimatedVisibility(
        visible = reactions.isNotEmpty(),
        enter = fadeIn(animationSpec = tween(durationMillis = 260, easing = LinearOutSlowInEasing)),
        exit = fadeOut(animationSpec = tween(durationMillis = 140)),
        modifier = modifier
    ) {
        FlowRow(
            modifier = Modifier.padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            reactions.forEachIndexed { index, reaction ->
                key(reaction.emoji ?: reaction.customEmojiId) {
                    var isVisible by remember { mutableStateOf(false) }

                    LaunchedEffect(Unit) {
                        delay(index * 35L)
                        isVisible = true
                    }

                    AnimatedVisibility(
                        visible = isVisible,
                        enter = scaleIn(
                            initialScale = 0.88f,
                            animationSpec = tween(
                                durationMillis = 280,
                                easing = FastOutSlowInEasing
                            )
                        ) +
                            fadeIn(animationSpec = tween(durationMillis = 220)),
                        exit = scaleOut(
                            targetScale = 0.92f,
                            animationSpec = tween(durationMillis = 140)
                        ) +
                            fadeOut(animationSpec = tween(durationMillis = 120))
                    ) {
                        MessageReactionItem(
                            reaction = reaction,
                            onReactionClick = onReactionClick,
                            emojiFontFamily = emojiFontFamily,
                            stickerRepository = stickerRepository,
                            customEmojiFileIdsById = customEmojiFileIdsById,
                            videoPlayerPool = videoPlayerPool
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageReactionItem(
    reaction: MessageReactionModel,
    onReactionClick: (String) -> Unit,
    emojiFontFamily: FontFamily,
    stickerRepository: StickerRepository,
    customEmojiFileIdsById: Map<Long, Long>,
    videoPlayerPool: VideoPlayerPool
) {
    val customEmojiId = reaction.customEmojiId
    val emoji = reaction.emoji

    if (customEmojiId == null && emoji == null) return

    val isChosen = reaction.isChosen

    val backgroundColor by animateColorAsState(
        targetValue = if (isChosen) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        },
        animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
        label = "reactionBackground"
    )

    val contentColor by animateColorAsState(
        targetValue = if (isChosen) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
        label = "reactionContent"
    )

    val scale by animateFloatAsState(
        targetValue = if (isChosen) 1.05f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "reactionScale"
    )

    val alpha by animateFloatAsState(
        targetValue = if (isChosen) 1f else 0.96f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "reactionAlpha"
    )

    val customEmojiFileId = customEmojiId?.let(customEmojiFileIdsById::get)
    val customEmojiPath by if (customEmojiFileId != null && reaction.customEmojiPath == null) {
        stickerRepository.getStickerFile(customEmojiFileId).collectAsState(initial = null)
    } else {
        remember { mutableStateOf(reaction.customEmojiPath) }
    }

    var showDropdown by remember { mutableStateOf(false) }
    val linkHandler = LocalLinkHandler.current

    Box(
        modifier = Modifier.graphicsLayer(
            scaleX = scale,
            scaleY = scale,
            alpha = alpha
        )
    ) {
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(backgroundColor)
                .padding(horizontal = 10.dp, vertical = 6.dp)
                .animateContentSize(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (customEmojiId != null) {
                StickerImage(
                    path = customEmojiPath,
                    modifier = Modifier.size(22.dp)
                )
            } else if (emoji != null) {
                Text(
                    text = emoji,
                    fontSize = 18.sp,
                    fontFamily = emojiFontFamily
                )
            }

            AnimatedVisibility(
                visible = reaction.recentSenders.isNotEmpty() && reaction.count <= 3,
                enter = fadeIn(animationSpec = tween(durationMillis = 220)) +
                    expandHorizontally(
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        ),
                        expandFrom = Alignment.Start
                    ),
                exit = fadeOut(animationSpec = tween(durationMillis = 120)) +
                    shrinkHorizontally(animationSpec = tween(durationMillis = 140), shrinkTowards = Alignment.Start)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy((-8).dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    reaction.recentSenders.take(3).forEach { sender ->
                        Avatar(
                            path = sender.avatar,
                            name = sender.name,
                            size = 22.dp,
                            videoPlayerPool = videoPlayerPool,
                            modifier = Modifier
                                .background(backgroundColor, CircleShape)
                                .padding(1.dp)
                        )
                    }
                }
            }

            if (reaction.count > 3 || reaction.recentSenders.isEmpty()) {
                AnimatedContent(
                    targetState = reaction.count,
                    transitionSpec = {
                        if (targetState > initialState) {
                            (slideInVertically(
                                animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                                initialOffsetY = { height -> height / 2 }
                            ) + fadeIn(animationSpec = tween(durationMillis = 180))).togetherWith(
                                slideOutVertically(
                                    animationSpec = tween(durationMillis = 160),
                                    targetOffsetY = { height -> -height / 2 }
                                ) + fadeOut(animationSpec = tween(durationMillis = 120))
                            )
                        } else {
                            (slideInVertically(
                                animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                                initialOffsetY = { height -> -height / 2 }
                            ) + fadeIn(animationSpec = tween(durationMillis = 180))).togetherWith(
                                slideOutVertically(
                                    animationSpec = tween(durationMillis = 160),
                                    targetOffsetY = { height -> height / 2 }
                                ) + fadeOut(animationSpec = tween(durationMillis = 120))
                            )
                        }.using(
                            SizeTransform(clip = false)
                        )
                    },
                    label = "reactionCount"
                ) { count ->
                    Text(
                        text = count.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = contentColor
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(CircleShape)
                .combinedClickable(
                    onClick = {
                        val reactionValue = emoji ?: customEmojiId?.toString()
                        if (reactionValue != null) {
                            onReactionClick(reactionValue)
                        }
                    },
                    onLongClick = {
                        if (reaction.recentSenders.isNotEmpty()) {
                            showDropdown = true
                        }
                    }
                )
        )

        DropdownMenu(
            expanded = showDropdown,
            onDismissRequest = { showDropdown = false },
            shape = RoundedCornerShape(16.dp)
        ) {
            reaction.recentSenders.forEach { sender ->
                DropdownMenuItem(
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Avatar(
                                path = sender.avatar,
                                name = sender.name,
                                size = 28.dp,
                                videoPlayerPool = videoPlayerPool
                            )
                            Text(
                                text = sender.name,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    },
                    onClick = {
                        showDropdown = false
                        linkHandler("tg://user?id=${sender.id}")
                    }
                )
            }
        }
    }
}