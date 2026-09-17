package org.monogram.feature.dialog.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.monogram.core.common.Outcome
import org.monogram.core.models.StickerPack
import org.monogram.core.ui.components.AppModalSheet
import org.monogram.core.ui.components.LocalMediaAnimationEnabled
import org.monogram.feature.dialog.R
import org.monogram.network.bridge.MtprotoClient

@Composable
internal fun StickerPackSheet(
    documentId: Long,
    client: MtprotoClient,
    onDismiss: () -> Unit,
) {
    var pack by remember(documentId) { mutableStateOf<StickerPack?>(null) }
    var error by remember(documentId) { mutableStateOf<String?>(null) }
    var attempt by remember(documentId) { mutableStateOf(0) }
    LaunchedEffect(documentId, client, attempt) {
        when (val result = client.getStickerPack(documentId)) {
            is Outcome.Ok -> {
                pack = result.value
                error = null
            }
            is Outcome.Err -> {
                pack = null
                error = result.message
            }
        }
    }
    val sheetMax = (LocalConfiguration.current.screenHeightDp.dp * 0.72f)
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    AppModalSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        padNavigationBars = false,
        padIme = false,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = sheetMax)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val title = pack?.title?.takeIf { it.isNotBlank() }
                ?: stringResource(R.string.dialog_media_sticker)
            Text(text = title, style = MaterialTheme.typography.titleLarge)
            if (pack == null && error == null) {
                PickerSkeleton(
                    kind = PickerSkeletonKind.StickerPack,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                )
            }
            pack?.let { info ->
                val count = if (info.isEmoji) {
                    pluralStringResource(R.plurals.dialog_emoji_pack_count, info.count, info.count)
                } else {
                    pluralStringResource(R.plurals.dialog_sticker_pack_count, info.count, info.count)
                }
                Text(
                    text = buildString {
                        append(count)
                        if (info.shortName.isNotBlank()) {
                            append(" · ")
                            append(info.shortName)
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (info.previewDocumentIds.isNotEmpty()) {
                    val cell = if (info.isEmoji) {
                        PickerMetrics.EmojiPackCell.dp
                    } else {
                        PickerMetrics.StickerCell.dp
                    }
                    val gridState = rememberLazyGridState()
                    CompositionLocalProvider(
                        LocalMediaAnimationEnabled provides !gridState.isScrollInProgress,
                    ) {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = cell),
                            state = gridState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 420.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp + navBottom),
                        ) {
                            items(info.previewDocumentIds, key = { it }) { id ->
                                Box(
                                    modifier = Modifier
                                        .size(cell)
                                        .background(
                                            MaterialTheme.colorScheme.surfaceVariant,
                                            MaterialTheme.shapes.small,
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CustomEmojiGlyph(documentId = id, size = cell)
                                }
                            }
                        }
                    }
                }
            }
            error?.let {
                PickerStatus(
                    text = stringResource(R.string.dialog_pack_error),
                    onRetry = { attempt += 1 },
                )
            }
        }
    }
}

internal fun stickerDocumentId(cacheKey: String?): Long? {
    val key = cacheKey ?: return null
    return key.substringAfter("doc:", missingDelimiterValue = "")
        .ifEmpty { key.substringAfter("emoji:", missingDelimiterValue = "") }
        .substringBefore(':')
        .toLongOrNull()
}
