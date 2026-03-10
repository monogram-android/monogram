package org.monogram.presentation.settings.chatSettings.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.monogram.domain.models.*
import org.monogram.presentation.core.util.IDownloadUtils
import org.monogram.presentation.features.chats.currentChat.components.MessageBubbleContainer
import org.monogram.presentation.features.chats.currentChat.components.VideoPlayerPool
import java.io.File

@Composable
fun ChatSettingsPreview(
    wallpaper: String?,
    availableWallpapers: List<WallpaperModel>,
    fontSize: Float,
    bubbleRadius: Float,
    isBlurred: Boolean,
    isMoving: Boolean = false,
    blurIntensity: Int = 20,
    dimming: Int = 0,
    isGrayscale: Boolean = false,
    modifier: Modifier = Modifier,
    downloadUtils: IDownloadUtils,
    videoPlayerPool: VideoPlayerPool
) {
    Column(modifier = modifier) {
        Text(
            text = "Preview",
            modifier = Modifier.padding(start = 12.dp, bottom = 8.dp, top = 16.dp),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(400.dp)
                .clip(RoundedCornerShape(16.dp))
        ) {
            val selectedWallpaper = remember(wallpaper, availableWallpapers) {
                if (wallpaper != null) {
                    availableWallpapers.find { it.slug == wallpaper || it.localPath == wallpaper }
                } else null
            }

            if (selectedWallpaper != null) {
                WallpaperBackground(
                    wallpaper = selectedWallpaper,
                    modifier = Modifier.fillMaxSize(),
                    isBlurred = isBlurred,
                    isMoving = isMoving,
                    blurIntensity = blurIntensity,
                    dimming = dimming,
                    isGrayscale = isGrayscale,
                    isChatSettings = true
                )
            } else if (wallpaper != null) {
                val file = remember(wallpaper) { File(wallpaper) }
                val exists = remember(file) { file.exists() }
                if (exists) {
                    val imageModifier = remember(isBlurred, blurIntensity) {
                        var m = Modifier.fillMaxSize()
                        if (isBlurred) {
                            m = m.blur((blurIntensity / 4f).dp)
                        }
                        m
                    }
                    AsyncImage(
                        model = file,
                        contentDescription = null,
                        modifier = imageModifier,
                        contentScale = ContentScale.Crop
                    )
                } else {
                    WallpaperBackground(wallpaper = null, modifier = Modifier.fillMaxSize())
                }
            } else {
                WallpaperBackground(wallpaper = null, modifier = Modifier.fillMaxSize())
            }

            val messages = remember {
                val msg2 = MessageModel(
                    id = 3,
                    date = 1678887000,
                    isOutgoing = true,
                    senderName = "Me",
                    chatId = 1,
                    content = MessageContent.Text(
                        text = "That's what you say every time you can't reach the top shelf... 🙄\nCheck this out: this",
                        entities = listOf(
                            MessageEntity(83, 4, MessageEntityType.TextUrl("https://youtu.be/dQw4w9WgXcQ"))
                        )
                    ),
                    isRead = true,
                    senderId = 1,
                    reactions = listOf(
                        MessageReactionModel(emoji = "\uD83D\uDE2D", count = 1, isChosen = false)
                    )
                )

                val msg1 = MessageModel(
                    id = 1,
                    date = 1678886400,
                    isOutgoing = false,
                    senderName = "Konata",
                    senderAvatar = "local",
                    chatId = 1,
                    content = MessageContent.Text(
                        text = "I'm not short, I'm just concentrated awesome! 🍫\nAlso, I've decided to become a professional sleeper 😴",
                        entities = listOf(
                            MessageEntity(0, 13, MessageEntityType.Bold),
                            MessageEntity(80, 20, MessageEntityType.Italic),
                            MessageEntity(93, 7, MessageEntityType.Spoiler)
                        )
                    ),
                    senderId = 2,
                    reactions = listOf(
                        MessageReactionModel(emoji = "⭐", count = 1, isChosen = false),
                        MessageReactionModel(emoji = "🔥", count = 3, isChosen = true)
                    )
                )

                val msg3 = MessageModel(
                    id = 4,
                    date = 1678887060,
                    isOutgoing = false,
                    senderName = "Konata",
                    senderAvatar = "local",
                    chatId = 1,
                    content = MessageContent.Text(
                        text = "Stop exposing me! 😤✨\nI'll just use my secret weapon: \uD83D\uDCAF pure laziness"
                    ),
                    replyToMsg = msg2,
                    replyToMsgId = msg2.id,
                    senderId = 2
                )

                val msg4 = MessageModel(
                    id = 5,
                    date = 1678887120,
                    isOutgoing = true,
                    senderName = "Me",
                    chatId = 1,
                    content = MessageContent.Text(
                        text = "It's super effective! 😵‍💫",
                        entities = listOf(
                            MessageEntity(5, 16, MessageEntityType.Bold)
                        )
                    ),
                    senderId = 1
                )
                listOf(msg1, msg2, msg3, msg4)
            }

            val onPhotoClick: (MessageModel) -> Unit = remember { {} }
            val onReplyClick: (androidx.compose.ui.geometry.Offset, androidx.compose.ui.unit.IntSize, androidx.compose.ui.geometry.Offset) -> Unit = remember { { _, _, _ -> } }
            val toProfile: (Long) -> Unit = remember { {} }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                reverseLayout = false,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item(key = "date_separator") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                        ) {
                            Text(
                                text = "Today",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                itemsIndexed(
                    items = messages,
                    key = { _, msg -> msg.id }
                ) { index, msg ->
                    val olderMsg = if (index > 0) messages[index - 1] else null
                    val newerMsg = if (index < messages.size - 1) messages[index + 1] else null

                    MessageBubbleContainer(
                        msg = msg,
                        olderMsg = olderMsg,
                        newerMsg = newerMsg,
                        isGroup = true,
                        fontSize = fontSize,
                        bubbleRadius = bubbleRadius,
                        autoDownloadMobile = true,
                        autoDownloadWifi = true,
                        autoDownloadRoaming = false,
                        autoDownloadFiles = false,
                        autoplayGifs = true,
                        autoplayVideos = true,
                        onPhotoClick = onPhotoClick,
                        onReplyClick = onReplyClick,
                        toProfile = toProfile,
                        downloadUtils = downloadUtils,
                        videoPlayerPool = videoPlayerPool
                    )
                }
            }
        }
    }
}
