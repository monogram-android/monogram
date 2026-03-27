package org.monogram.presentation.core.ui

import androidx.compose.foundation.background
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.monogram.domain.models.UserModel
import org.monogram.presentation.features.chats.currentChat.components.VideoPlayerPool
import org.monogram.presentation.features.stickers.ui.view.StickerImage

@Composable
fun UserProfileHeader(
    userModel: UserModel,
    avatarSize: Dp,
    headerHeight: Dp,
    avatarCornerPercent: Int,
    contentPadding: PaddingValues,
    currentRadius: Dp,
    alpha: Float = 1f,
    videoPlayerPool: VideoPlayerPool
) {
    val capHeight = 24.dp

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(contentPadding)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(headerHeight)
                .alpha(alpha)
        ) {
            Box(modifier = Modifier.align(Alignment.Center)) {
                AvatarHeader(
                    path = userModel.avatarPath,
                    fallbackPath = userModel.personalAvatarPath,
                    name = "${userModel.firstName} ${userModel.lastName}",
                    size = avatarSize.coerceAtMost(headerHeight),
                    avatarCornerPercent = avatarCornerPercent,
                    videoPlayerPool = videoPlayerPool
                )
            }

            val scrimColor = Color.Black.copy(alpha = 0.7f)
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .alpha(alpha / 1.5f)
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
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(bottom = capHeight + 16.dp)
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${userModel.firstName} ${userModel.lastName ?: ""}".trim(),
                    style = MaterialTheme.typography.headlineLarge.copy(
                        shadow = Shadow(
                            color = Color.Black.copy(alpha = 0.5f),
                            offset = Offset(2f, 2f),
                            blurRadius = 8f
                        )
                    ),
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                if (userModel.isVerified) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Rounded.Verified,
                        contentDescription = "Verified",
                        modifier = Modifier.size(28.dp),
                        tint = Color(0xFF31A6FD)
                    )
                }

                if (userModel.isSponsor) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Default.Favorite,
                        contentDescription = "Sponsor",
                        modifier = Modifier.size(28.dp),
                        tint = Color(0xFFE53935)
                    )
                }

                if (!userModel.statusEmojiPath.isNullOrEmpty()) {
                    Spacer(modifier = Modifier.width(6.dp))
                    StickerImage(
                        path = userModel.statusEmojiPath,
                        modifier = Modifier.size(26.dp),
                        animate = false
                    )
                } else if (userModel.isPremium) {
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

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(capHeight)
                .align(Alignment.BottomCenter)
                .background(
                    color = MaterialTheme.colorScheme.background,
                    shape = RoundedCornerShape(topStart = currentRadius, topEnd = currentRadius)
                )
        )
    }
}