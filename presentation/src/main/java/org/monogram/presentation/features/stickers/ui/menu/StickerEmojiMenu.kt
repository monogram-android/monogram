package org.monogram.presentation.features.stickers.ui.menu

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.monogram.domain.models.GifModel
import org.monogram.domain.models.StickerModel
import org.monogram.domain.repository.StickerRepository
import org.monogram.presentation.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StickerEmojiMenu(
    onStickerSelected: (String) -> Unit,
    onEmojiSelected: (String, StickerModel?) -> Unit,
    onGifSelected: (GifModel) -> Unit,
    panelHeight: Dp = 400.dp,
    emojiOnlyMode: Boolean = false,
    canSendStickers: Boolean = true,
    onSearchFocused: (Boolean) -> Unit = {},
    stickerRepository: StickerRepository
) {
    val stickersAndGifsAllowed = !emojiOnlyMode && canSendStickers
    var selectedTab by remember(stickersAndGifsAllowed) { mutableIntStateOf(if (stickersAndGifsAllowed) 0 else 1) }
    var isSearchMode by remember { mutableStateOf(false) }
    val tabs = if (!stickersAndGifsAllowed) {
        listOf(Triple(stringResource(R.string.sticker_menu_tab_emojis), Icons.Outlined.EmojiEmotions, 1))
    } else {
        listOf(
            Triple(stringResource(R.string.sticker_menu_tab_stickers), Icons.AutoMirrored.Outlined.StickyNote2, 0),
            Triple(stringResource(R.string.sticker_menu_tab_emojis), Icons.Outlined.EmojiEmotions, 1),
            Triple(stringResource(R.string.sticker_menu_tab_gifs), Icons.Outlined.Gif, 2)
        )
    }

    LaunchedEffect(stickersAndGifsAllowed) {
        if (!stickersAndGifsAllowed && selectedTab != 1) {
            selectedTab = 1
        }
    }

    Surface(
        modifier = if (isSearchMode) {
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
        } else {
            Modifier
                .fillMaxWidth()
                .height(panelHeight)
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
        },
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 2.dp
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize()) {
                when (selectedTab) {
                    0 -> if (stickersAndGifsAllowed) StickersView(
                        onStickerSelected = onStickerSelected,
                        onSearchFocused = { focused ->
                            isSearchMode = focused
                            onSearchFocused(focused)
                        },
                        contentPadding = PaddingValues(bottom = 76.dp)
                    ) else Unit

                    1 -> EmojisGrid(
                        onEmojiSelected = onEmojiSelected,
                        emojiOnlyMode = emojiOnlyMode,
                        onSearchFocused = { focused ->
                            isSearchMode = focused
                            onSearchFocused(focused)
                        },
                        contentPadding = PaddingValues(bottom = 76.dp)
                    )

                    2 -> if (stickersAndGifsAllowed) GifsView(
                        onGifSelected = onGifSelected,
                        onSearchFocused = { focused ->
                            isSearchMode = focused
                            onSearchFocused(focused)
                        },
                        contentPadding = PaddingValues(bottom = 76.dp),
                        stickerRepository = stickerRepository
                    ) else Unit
                }
            }

            AnimatedVisibility(
                visible = !isSearchMode && tabs.size > 1,
                enter = fadeIn(animationSpec = tween(180)) +
                        slideInVertically(animationSpec = tween(220)) { it / 4 },
                exit = fadeOut(animationSpec = tween(130)) +
                        slideOutVertically(animationSpec = tween(180)) { it / 4 },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp)
            ) {
                FloatingTabs(tabs, selectedTab) { selectedTab = it }
            }
        }
    }
}
