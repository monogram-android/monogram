package org.monogram.presentation.features.chats.currentChat.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.monogram.domain.models.StickerSetModel
import org.monogram.domain.models.StickerType
import org.monogram.domain.repository.StickerRepository
import org.monogram.presentation.R
import org.monogram.presentation.features.stickers.ui.view.StickerImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StickerSetSheet(
    stickerSet: StickerSetModel,
    onDismiss: () -> Unit,
    onStickerClick: (String) -> Unit,
    stickerRepository: StickerRepository = koinInject()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isInstalled by remember { mutableStateOf(stickerSet.isInstalled) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stickerSet.title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (stickerSet.stickerType == StickerType.CUSTOM_EMOJI) {
                                pluralStringResource(R.plurals.emojis_count, stickerSet.stickers.size, stickerSet.stickers.size)
                            } else {
                                pluralStringResource(R.plurals.stickers_count, stickerSet.stickers.size, stickerSet.stickers.size)
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (stickerSet.stickerType != StickerType.REGULAR) {
                            Text(
                                text = " • ",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = when (stickerSet.stickerType) {
                                    StickerType.MASK -> stringResource(R.string.sticker_type_masks)
                                    StickerType.CUSTOM_EMOJI -> stringResource(R.string.sticker_type_custom_emojis)
                                    else -> ""
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        if (stickerSet.isOfficial) {
                            Text(
                                text = " • ",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = stringResource(R.string.sticker_official),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    val linkCopiedText = stringResource(R.string.link_copied)
                    IconButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val link = "https://t.me/addstickers/${stickerSet.name}"
                            val clip = ClipData.newPlainText("Sticker Set Link", link)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, linkCopiedText, Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Share,
                            contentDescription = stringResource(R.string.menu_share),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    stickerSet.thumbnail?.let { thumb ->
                        var thumbPath by remember { mutableStateOf(thumb.path) }
                        LaunchedEffect(thumb.id) {
                            if (thumb.path == null) {
                                stickerRepository.getStickerFile(thumb.id).collect { thumbPath = it }
                            }
                        }
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            StickerImage(
                                path = thumbPath,
                                modifier = Modifier.size(40.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 64.dp),
                    contentPadding = PaddingValues(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(420.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(stickerSet.stickers, key = { it.id }) { sticker ->
                        var currentPath by remember(sticker.id, sticker.path) { mutableStateOf(sticker.path) }

                        LaunchedEffect(sticker.id, sticker.path) {
                            if (sticker.path == null) {
                                stickerRepository.getStickerFile(sticker.id).collect { newPath ->
                                    if (newPath != null) {
                                        currentPath = newPath
                                    }
                                }
                            } else {
                                currentPath = sticker.path
                            }
                        }

                        Box(
                            modifier = Modifier
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    currentPath?.let { path ->
                                        onStickerClick(path)
                                        onDismiss()
                                    }
                                }
                                .padding(4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            StickerImage(
                                path = currentPath,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(stringResource(R.string.close_button), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }

                if (!isInstalled) {
                    Button(
                        onClick = {
                            scope.launch {
                                stickerRepository.toggleStickerSetInstalled(stickerSet.id, true)
                                isInstalled = true
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(stringResource(R.string.action_add), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                } else {
                    Button(
                        onClick = {
                            scope.launch {
                                stickerRepository.toggleStickerSetInstalled(stickerSet.id, false)
                                isInstalled = false
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text(stringResource(R.string.menu_delete), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
