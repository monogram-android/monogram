package org.monogram.presentation.features.chats.currentChat.components.inputbar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.monogram.domain.models.UserModel
import org.monogram.domain.models.UserStatusType
import org.monogram.presentation.core.ui.Avatar
import org.monogram.presentation.core.util.getUserStatusText
import org.monogram.presentation.features.chats.currentChat.components.VideoPlayerPool

@Composable
fun MentionSuggestions(
    suggestions: List<UserModel>,
    videoPlayerPool: VideoPlayerPool,
    onMentionClick: (UserModel) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 240.dp)
            .background(MaterialTheme.colorScheme.surface)
    ) {
        LazyColumn(
            contentPadding = PaddingValues(vertical = 4.dp)
        ) {
            items(suggestions, key = { it.id }) { user ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onMentionClick(user) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Avatar(
                        path = user.personalAvatarPath ?: user.avatarPath,
                        name = user.firstName,
                        size = 36.dp,
                        isOnline = user.userStatus == UserStatusType.ONLINE,
                        videoPlayerPool = videoPlayerPool
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${user.firstName} ${user.lastName ?: ""}".trim(),
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val status = user.username?.let { "@$it" } ?: getUserStatusText(user)
                        Text(
                            text = status,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}