package org.monogram.presentation.features.chats.currentChat.components.chats

import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import org.koin.compose.koinInject
import org.monogram.domain.models.MessageReactionModel
import org.monogram.domain.repository.StickerRepository
import org.monogram.presentation.core.ui.Avatar
import org.monogram.presentation.core.util.AppPreferences
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

    AnimatedVisibility(
        visible = reactions.isNotEmpty(),
        enter = fadeIn(animationSpec = tween(300)) + expandVertically(
            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
            expandFrom = Alignment.Top
        ),
        exit = fadeOut(animationSpec = tween(200)) + shrinkVertically(
            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
            shrinkTowards = Alignment.Top
        ),
        modifier = modifier
    ) {
        FlowRow(
            modifier = Modifier.padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            for (reaction in reactions) {
                key(reaction.emoji ?: reaction.customEmojiId) {
                    var isVisible by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) { isVisible = true }

                    AnimatedVisibility(
                        visible = isVisible,
                        enter = scaleIn(
                            initialScale = 0.7f,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessLow
                            )
                        ) + fadeIn(),
                        exit = scaleOut(targetScale = 0.7f) + fadeOut()
                    ) {
                        MessageReactionItem(
                            reaction = reaction,
                            onReactionClick = onReactionClick,
                            emojiFontFamily = emojiFontFamily,
                            stickerRepository = stickerRepository,
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
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "reactionBackground"
    )

    val contentColor by animateColorAsState(
        targetValue = if (isChosen) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "reactionContent"
    )

    val scale by animateFloatAsState(
        targetValue = if (isChosen) 1.05f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "reactionScale"
    )

    val customEmojiPath by if (customEmojiId != null && reaction.customEmojiPath == null) {
        stickerRepository.getStickerFile(customEmojiId).collectAsState(initial = null)
    } else {
        remember { mutableStateOf(reaction.customEmojiPath) }
    }

    var showDropdown by remember { mutableStateOf(false) }
    val linkHandler = LocalLinkHandler.current

    Box(modifier = Modifier.graphicsLayer(scaleX = scale, scaleY = scale)) {
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(backgroundColor)
                .padding(horizontal = 10.dp, vertical = 6.dp)
                .animateContentSize(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)),
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
                enter = fadeIn() + expandHorizontally(),
                exit = fadeOut() + shrinkHorizontally()
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
                            (slideInVertically { height -> height } + fadeIn()).togetherWith(
                                slideOutVertically { height -> -height } + fadeOut())
                        } else {
                            (slideInVertically { height -> -height } + fadeIn()).togetherWith(
                                slideOutVertically { height -> height } + fadeOut())
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