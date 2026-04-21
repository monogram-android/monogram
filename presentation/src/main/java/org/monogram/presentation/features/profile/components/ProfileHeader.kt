package org.monogram.presentation.features.profile.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.monogram.presentation.R
import org.monogram.presentation.core.ui.Avatar
import org.monogram.presentation.features.stickers.ui.view.StickerImage


@Composable
fun ProfileHeader(
    avatarPath: String?,
    profilePhotos: List<String>,
    title: String,
    subtitle: String,
    isOnline: Boolean,
    isVerified: Boolean,
    isSponsor: Boolean,
    statusEmojiPath: String?,
    isBot: Boolean,
    isScam: Boolean,
    isFake: Boolean,
    onAvatarClick: () -> Unit
) {
    val displayPath = avatarPath

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            contentAlignment = Alignment.BottomEnd
        ) {
            Avatar(
                path = displayPath,
                name = title,
                size = 120.dp,
                fontSize = 48,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(enabled = displayPath != null) { onAvatarClick() }
            )

            if (isOnline) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .background(MaterialTheme.colorScheme.background, CircleShape)
                        .padding(3.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF4CAF50), CircleShape)
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 24.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold
                ),
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )

            if (isVerified) {
                Spacer(Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.Rounded.Verified,
                    contentDescription = stringResource(R.string.cd_verified),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
            if (isSponsor) {
                Spacer(Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.Default.Favorite,
                    contentDescription = stringResource(R.string.cd_sponsor),
                    tint = Color(0xFFE53935),
                    modifier = Modifier.size(22.dp)
                )
            }
            if (statusEmojiPath != null) {
                Spacer(Modifier.width(6.dp))
                StickerImage(
                    path = statusEmojiPath,
                    modifier = Modifier.size(22.dp),
                    animate = false
                )
            }
            if (isBot) {
                Spacer(Modifier.width(6.dp))
                ProfileStatusBadge(
                    text = stringResource(R.string.label_bot_badge),
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
            if (isScam) {
                Spacer(Modifier.width(6.dp))
                ProfileStatusBadge(
                    text = stringResource(R.string.label_scam_badge),
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            if (isFake) {
                Spacer(Modifier.width(6.dp))
                ProfileStatusBadge(
                    text = stringResource(R.string.label_fake_badge),
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyLarge,
            color = if (isOnline) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ProfileStatusBadge(
    text: String,
    containerColor: Color,
    contentColor: Color
) {
    Surface(
        color = containerColor,
        shape = RoundedCornerShape(4.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
            color = contentColor
        )
    }
}
