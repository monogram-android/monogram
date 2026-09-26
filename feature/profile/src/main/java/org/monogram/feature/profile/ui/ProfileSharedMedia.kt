package org.monogram.feature.profile.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import org.monogram.core.common.Outcome
import org.monogram.core.models.Message
import org.monogram.core.models.ProfileTab
import org.monogram.core.ui.components.ItemPosition
import org.monogram.core.ui.components.MediaPreviewViewer
import org.monogram.core.ui.components.SettingsTile
import org.monogram.core.ui.loading.MonogramLoading
import org.monogram.core.ui.loading.MonogramLoadingInlineSize
import org.monogram.core.ui.rememberCacheGeneration
import org.monogram.feature.profile.ProfileComponent
import org.monogram.feature.profile.ProfilePanel
import org.monogram.feature.profile.ProfileStore
import org.monogram.feature.profile.R
import org.monogram.network.http.MediaPriority
import org.monogram.network.http.MediaRepository
import org.monogram.network.http.photoDisplayCacheKey

internal fun LazyListScope.sharedMediaPanel(
    tab: ProfileTab,
    state: ProfileStore.State,
    component: ProfileComponent,
    onOpenMedia: (Message) -> Unit,
) {
    val messages = state.sharedMedia[tab].orEmpty()
    val loading = tab in state.sharedMediaLoading
    if (messages.isEmpty() && loading) {
        item(key = "media-loading") { PanelLoading() }
        return
    }
    if (messages.isEmpty()) {
        val empty = state.sharedMediaError != null
        item(key = "media-empty") {
            if (empty) {
                PanelError(onRetry = { component.onLoadMore(ProfilePanel.SharedMedia(tab)) })
            } else {
                PanelEmpty(stringResource(R.string.profile_panel_empty))
            }
        }
        return
    }
    when (tab) {
        ProfileTab.MEDIA, ProfileTab.GIFS -> {
            items(
                items = messages.chunked(3),
                key = { row -> "media-row:${row.first().id.id}" },
            ) { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    row.forEach { message ->
                        MediaGridCell(
                            message = message,
                            repository = component.mediaRepository,
                            onClick = { onOpenMedia(message) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        else -> {
            itemsIndexed(
                items = messages,
                key = { _, message -> "media:${message.id.id}" },
            ) { index, message ->
                SettingsTile(
                    icon = panelIcon(ProfilePanel.SharedMedia(tab)) ?: Icons.Outlined.Description,
                    title = message.fileName
                        ?: message.text?.lines()?.firstOrNull()?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.profile_tab_files),
                    subtitle = messageSubtitle(message),
                    iconColor = MaterialTheme.colorScheme.primary,
                    position = itemPosition(index, messages.size),
                    onClick = { onOpenMedia(message) },
                )
            }
        }
    }
    item(key = "media-more") {
        PanelLoadMore(
            loading = loading,
            end = tab in state.sharedMediaEnd,
            onLoadMore = { component.onLoadMore(ProfilePanel.SharedMedia(tab)) },
        )
    }
}

@Composable
private fun MediaGridCell(
    message: Message,
    repository: MediaRepository?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val displayKey = message.mediaCacheKey?.let(::photoDisplayCacheKey)
    val cacheGeneration = rememberCacheGeneration(
        remember(repository, displayKey, message.thumbCacheKey, message.mediaCacheKey) {
            repository?.cacheGeneration(
                listOfNotNull(displayKey, message.thumbCacheKey, message.mediaCacheKey),
            )
        },
    )
    var file by remember(message.id.id, message.mediaCacheKey, cacheGeneration) {
        mutableStateOf(
            displayKey?.let { repository?.cachedFile(it) }
                ?: message.thumbCacheKey?.let { repository?.cachedFile(it) }
                ?: message.mediaCacheKey?.let { repository?.cachedFile(it) },
        )
    }
    LaunchedEffect(message.id.id, message.mediaCacheKey, cacheGeneration, repository) {
        val repo = repository ?: return@LaunchedEffect
        val next = withContext(Dispatchers.IO) {
            when (val display = repo.ensureLocalMessageDisplay(message, MediaPriority.VISIBLE)) {
                is Outcome.Ok -> display.value
                is Outcome.Err -> when (
                    val thumb = repo.ensureLocalMessageThumb(message, MediaPriority.THUMB)
                ) {
                    is Outcome.Ok -> thumb.value
                    is Outcome.Err -> null
                }
            }
        }
        if (next != null) file = next
    }
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val resolved = file
        if (resolved != null) {
            AsyncImage(
                model = resolved,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            MonogramLoading(size = MonogramLoadingInlineSize)
        }
    }
}

@Composable
private fun messageSubtitle(message: Message): String? {
    val size = message.fileSize?.takeIf { it > 0 }?.let { formatFileSize(it) }
    val kind = message.mediaKind
    return listOfNotNull(kind, size).joinToString(" · ").ifBlank { null }
}

private fun formatFileSize(bytes: Long): String {
    val units = listOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return if (unit == 0) "${value.toLong()} ${units[unit]}" else String.format("%.1f %s", value, units[unit])
}

/** Full-screen viewer for a shared-media row, reusing the app-wide preview surface. */
@Composable
internal fun ProfileMediaViewer(
    message: Message,
    repository: MediaRepository?,
    onDismiss: () -> Unit,
) {
    val cacheKey = message.mediaCacheKey.orEmpty()
    val video = message.mediaKind == "video"
    var fullFile by remember(cacheKey, repository) { mutableStateOf(repository?.cachedFile(cacheKey)) }
    val preview = remember(cacheKey, repository) {
        repository?.cachedFile(photoDisplayCacheKey(cacheKey))
            ?: message.thumbCacheKey?.let { repository?.cachedFile(it) }
    }
    var loading by remember { mutableStateOf(!video && fullFile == null) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(cacheKey, repository, attempt) {
        if (video || fullFile != null) return@LaunchedEffect
        loading = true
        failed = false
        when (val result = repository?.ensureLocalMessageMedia(message, MediaPriority.USER)) {
            is Outcome.Ok -> fullFile = result.value
            else -> failed = true
        }
        loading = false
    }
    val stream = remember(cacheKey, repository, fullFile) {
        if (video && fullFile == null) repository?.createMessageDataSourceFactory(message) else null
    }
    MediaPreviewViewer(
        file = fullFile ?: preview,
        uri = if (video && fullFile == null) {
            Uri.parse("telegram://media/${message.id.chatId.value}/${message.id.id}")
        } else {
            null
        },
        forceVideo = video,
        videoDataSourceFactory = stream,
        contentDescription = stringResource(
            if (video) R.string.profile_media_video else R.string.profile_media_photo,
        ),
        caption = message.text,
        loading = loading,
        failed = failed,
        onRetry = { attempt++ },
        stateKey = "${message.id.chatId.value}:${message.id.id}",
        onDismiss = onDismiss,
    )
}

private fun itemPosition(index: Int, size: Int): ItemPosition = when {
    size <= 1 -> ItemPosition.STANDALONE
    index == 0 -> ItemPosition.TOP
    index == size - 1 -> ItemPosition.BOTTOM
    else -> ItemPosition.MIDDLE
}
