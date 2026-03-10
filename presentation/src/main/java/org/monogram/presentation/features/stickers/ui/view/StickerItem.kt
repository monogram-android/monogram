package org.monogram.presentation.features.stickers.ui.view

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.koin.compose.koinInject
import org.monogram.domain.models.StickerModel
import org.monogram.domain.repository.StickerRepository

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StickerItem(
    sticker: StickerModel,
    modifier: Modifier = Modifier,
    animate: Boolean = true,
    onClick: ((String) -> Unit)? = null,
    onLongClick: ((StickerModel) -> Unit)? = null,
    stickerRepository: StickerRepository = koinInject()
) {
    var currentPath by remember(sticker.id, sticker.path) { mutableStateOf(sticker.path) }

    LaunchedEffect(sticker.id, sticker.path) {
        if (currentPath == null) {
            stickerRepository.getStickerFile(sticker.id).collect {
                currentPath = it
            }
        }
    }

    Box(
        modifier = if ((onClick != null || onLongClick != null) && currentPath != null) {
            modifier.combinedClickable(
                onClick = { onClick?.invoke(currentPath!!) },
                onLongClick = { onLongClick?.invoke(sticker) }
            )
        } else {
            modifier
        },
        contentAlignment = Alignment.Center
    ) {
        StickerImage(
            path = currentPath,
            modifier = Modifier.matchParentSize(),
            animate = animate
        )
    }
}
