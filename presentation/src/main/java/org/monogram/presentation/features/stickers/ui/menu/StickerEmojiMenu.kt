package org.monogram.presentation.features.stickers.ui.menu

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.StickyNote2
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Gif
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.monogram.domain.models.GifModel
import org.monogram.domain.models.StickerModel
import org.monogram.domain.repository.StickerRepository
import org.monogram.presentation.R
import org.monogram.presentation.features.chats.currentChat.components.VideoPlayerPool

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StickerEmojiMenu(
    onStickerSelected: (String) -> Unit,
    onEmojiSelected: (String, StickerModel?) -> Unit,
    onGifSelected: (GifModel) -> Unit,
    onSearchFocused: (Boolean) -> Unit = {},
    videoPlayerPool: VideoPlayerPool,
    stickerRepository: StickerRepository
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var isSearchMode by remember { mutableStateOf(false) }
    val tabs = listOf(
        Triple(stringResource(R.string.sticker_menu_tab_stickers), Icons.AutoMirrored.Outlined.StickyNote2, 0),
        Triple(stringResource(R.string.sticker_menu_tab_emojis), Icons.Outlined.EmojiEmotions, 1),
        Triple(stringResource(R.string.sticker_menu_tab_gifs), Icons.Outlined.Gif, 2)
    )

    Surface(
        modifier = if (isSearchMode) {
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
        } else {
            Modifier
                .fillMaxWidth()
                .height(400.dp)
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
        },
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 2.dp
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize()) {
                when (selectedTab) {
                    0 -> StickersView(
                        onStickerSelected = onStickerSelected,
                        onSearchFocused = { focused ->
                            isSearchMode = focused
                            onSearchFocused(focused)
                        },
                        contentPadding = PaddingValues(bottom = 88.dp)
                    )

                    1 -> EmojisGrid(
                        onEmojiSelected = onEmojiSelected,
                        onSearchFocused = { focused ->
                            isSearchMode = focused
                            onSearchFocused(focused)
                        },
                        contentPadding = PaddingValues(bottom = 88.dp)
                    )

                    2 -> GifsView(
                        onGifSelected = onGifSelected,
                        onSearchFocused = { focused ->
                            isSearchMode = focused
                            onSearchFocused(focused)
                        },
                        contentPadding = PaddingValues(bottom = 88.dp),
                        videoPlayerPool = videoPlayerPool,
                        stickerRepository = stickerRepository
                    )
                }
            }

            AnimatedVisibility(
                visible = !isSearchMode,
                enter = fadeIn() + slideInVertically { it / 2 } + scaleIn(initialScale = 0.8f),
                exit = fadeOut() + slideOutVertically { it / 2 } + scaleOut(targetScale = 0.8f),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp)
            ) {
                FloatingTabs(tabs, selectedTab) { selectedTab = it }
            }
        }
    }
}
