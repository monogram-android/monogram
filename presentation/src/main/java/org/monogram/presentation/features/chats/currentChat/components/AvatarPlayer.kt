package org.monogram.presentation.features.chats.currentChat.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale

@Composable
fun AvatarPlayer(
    path: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop
) {
    InlineVideoPlayer(
        path = path,
        modifier = modifier,
        contentScale = contentScale,
        placeholderData = path
    )
}
