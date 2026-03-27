package org.monogram.presentation.features.profile.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.monogram.domain.models.ChatModel
import org.monogram.domain.models.UserModel
import org.monogram.presentation.core.ui.AvatarHeader
import org.monogram.presentation.features.chats.currentChat.components.VideoPlayerPool
import org.monogram.presentation.features.stickers.ui.view.StickerImage

@Composable
fun ProfileHeaderTransformed(
    avatarPath: String?,
    title: String,
    subtitle: String,
    avatarSize: Dp,
    userModel: UserModel?,
    chatModel: ChatModel?,
    avatarCornerPercent: Int,
    isOnline: Boolean,
    isVerified: Boolean,
    isSponsor: Boolean,
    statusEmojiPath: String?,
    progress: Float,
    contentPadding: PaddingValues,
    onAvatarClick: () -> Unit,
    onActionClick: () -> Unit,
    videoPlayerPool: VideoPlayerPool
) {
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(contentPadding)
    ) {
        val headerHeight = maxWidth.coerceAtMost(screenHeight * 0.6f)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(headerHeight)
                .alpha(progress)
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(avatarCornerPercent))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onAvatarClick
                    )
            ) {
                AvatarHeader(
                    path = avatarPath,
                    name = title,
                    size = avatarSize.coerceAtMost(headerHeight),
                    avatarCornerPercent = avatarCornerPercent,
                    videoPlayerPool = videoPlayerPool
                )
            }

            val scrimColor = Color.Black.copy(alpha = 0.7f)
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .alpha(progress / 1.5f)
                    .background(
                        brush = Brush.verticalGradient(
                            colorStops = arrayOf(
                                0.0f to scrimColor,
                                0.15f to Color.Transparent,
                                0.7f to Color.Transparent,
                                1.0f to scrimColor
                            )
                        ),
                        shape = RoundedCornerShape(avatarCornerPercent)
                    )
            )

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(horizontal = 20.dp, vertical = 32.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.headlineLarge.copy(
                                shadow = Shadow(
                                    color = Color.Black.copy(alpha = 0.5f),
                                    offset = Offset(2f, 2f),
                                    blurRadius = 8f
                                )
                            ),
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )

                        if (isVerified) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                imageVector = Icons.Rounded.Verified,
                                contentDescription = "Verified",
                                modifier = Modifier.size(28.dp),
                                tint = Color(0xFF31A6FD)
                            )
                        }
                        if (isSponsor) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                imageVector = Icons.Default.Favorite,
                                contentDescription = "Sponsor",
                                modifier = Modifier.size(28.dp),
                                tint = Color(0xFFE53935)
                            )
                        }

                        userModel?.let { user ->
                            if (!user.statusEmojiPath.isNullOrEmpty()) {
                                Spacer(modifier = Modifier.width(6.dp))
                                StickerImage(
                                    path = user.statusEmojiPath,
                                    modifier = Modifier.size(26.dp),
                                    animate = false
                                )
                            } else if (user.isPremium) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(
                                    imageVector = Icons.Default.Star,
                                    contentDescription = null,
                                    modifier = Modifier.size(28.dp),
                                    tint = Color(0xFF31A6FD)
                                )
                            }
                        }
                    }


                }

                Text(
                    text = subtitle,
                    color = if (isOnline) Color(0xFF4CAF50) else Color.White.copy(alpha = 0.9f),
                    fontSize = 16.sp,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        shadow = Shadow(
                            color = Color.Black.copy(alpha = 0.5f),
                            offset = Offset(1f, 1f),
                            blurRadius = 4f
                        )
                    )
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .align(Alignment.BottomCenter)
                .background(
                    color = MaterialTheme.colorScheme.background,
                    shape = RoundedCornerShape(
                        topStart = (30 * progress).dp,
                        topEnd = (30 * progress).dp
                    )
                )
        )
    }
}
