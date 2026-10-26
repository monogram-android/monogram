package org.monogram.feature.dialog.scheduled

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.monogram.core.common.Outcome
import org.monogram.core.common.telegram.TelegramError
import org.monogram.core.models.ScheduledMessage
import org.monogram.core.ui.components.MediaPreviewViewer
import org.monogram.core.ui.rememberEnsuredFile
import org.monogram.core.ui.rememberFileLease
import org.monogram.feature.dialog.R
import org.monogram.network.http.MediaRepository
import org.monogram.network.http.mediaThumbCacheKey
import kotlin.time.Duration.Companion.milliseconds

@Composable
internal fun scheduledMediaLabel(message: ScheduledMessage): String = stringResource(
    when (message.message.mediaKind) {
        "video" -> R.string.dialog_media_video
        "video_note" -> R.string.dialog_media_video_note
        "gif" -> R.string.dialog_media_gif
        else -> R.string.dialog_media_photo
    }
)

@Composable
internal fun ScheduledQueueMediaThumb(
    message: ScheduledMessage, repository: MediaRepository?, onOpen: () -> Unit
) {
    val generation =
        repository?.cacheGeneration?.collectAsStateWithLifecycle(initialValue = 0L)?.value ?: 0L
    val key = message.message.thumbCacheKey?.takeUnless { it == message.message.mediaCacheKey }
        ?: message.message.mediaCacheKey?.let(::mediaThumbCacheKey)
    val file = rememberEnsuredFile(
        generation,
        message to key,
        resolve = { key?.let { repository?.cachedFile(it) } },
        ensure = {
            val repo = repository ?: return@rememberEnsuredFile null
            withContext(Dispatchers.IO) {
                when (val result = repo.ensureLocalScheduledMessageThumb(message)) {
                    is Outcome.Ok -> result.value
                    is Outcome.Err -> {
                        if (result.telegramError.kind == TelegramError.Kind.Flood) {
                            kotlinx.coroutines.delay(
                                (((result.telegramError.retryAfterSeconds ?: 1).coerceAtLeast(1)
                                    .toLong() + 1) * 1000).milliseconds
                            )
                        }
                        null
                    }
                }
            }
        })
    val label = scheduledMediaLabel(message)
    val icon =
        if (message.message.mediaKind == "photo") Icons.Outlined.Image else Icons.Outlined.PlayArrow
    Surface(
        onClick = onOpen,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier
            .size(56.dp)
            .semantics { contentDescription = label; role = Role.Button }) {
        if (file != null) AsyncImage(
            file,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            error = rememberVectorPainter(icon),
            modifier = Modifier.fillMaxSize()
        )
        else Box(
            Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center
        ) { Icon(icon, null) }
    }
}

@Composable
internal fun ScheduledQueueMediaViewer(
    preview: ScheduledQueueStore.PreviewState, onDismiss: () -> Unit, onRetry: () -> Unit
) {
    rememberFileLease(preview.file)
    val kind = preview.message.message.mediaKind
    MediaPreviewViewer(
        file = preview.file,
        onDismiss = onDismiss,
        contentDescription = scheduledMediaLabel(preview.message),
        loading = preview.loading,
        failed = preview.error != null,
        onRetry = onRetry,
        caption = preview.message.message.text,
        forceVideo = kind != "photo",
        loop = kind == "gif" || kind == "video_note",
        stateKey = "scheduled:${preview.message.message.id}:${preview.message.message.mediaCacheKey}",
    )
}
