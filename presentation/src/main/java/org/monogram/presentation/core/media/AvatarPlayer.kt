package org.monogram.presentation.core.media

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import org.monogram.presentation.features.chats.conversation.ui.InlineVideoPlayer

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
