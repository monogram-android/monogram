package org.monogram.feature.dialog.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Gif
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.io.File
import java.time.ZoneId
import org.monogram.core.common.Outcome
import org.monogram.core.models.Message
import org.monogram.core.models.PeerId
import org.monogram.core.models.Profile
import org.monogram.core.ui.components.AppModalSheet
import org.monogram.core.ui.localizedServiceMessage
import org.monogram.core.ui.rememberEnsuredFile
import org.monogram.feature.dialog.DialogTime
import org.monogram.feature.dialog.R
import org.monogram.network.http.MediaPriority
import org.monogram.network.http.MediaRepository

/**
 * Telegram's pinned surfaces on the Android shell: the compact banner under the app bar and the
 * all-pins sheet the banner opens. Both draw the same leading thumbnail, so a media pin is
 * recognizable without opening its row, and both mark the pin the thread is currently on.
 */

@Composable
internal fun PinnedMessageBar(
    pinned: Message,
    total: Int,
    mediaRepository: MediaRepository?,
    onJump: () -> Unit,
    onOpenList: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.large,
        color = scheme.surfaceContainerHigh,
        shadowElevation = 2.dp,
        tonalElevation = 2.dp,
        onClick = onJump,
    ) {
        Row(
            modifier = Modifier
                .padding(start = 8.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
        PinnedLeadingThumb(
            message = pinned,
            size = 40.dp,
            mediaRepository = mediaRepository,
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(1.dp),
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = stringResource(R.string.dialog_pinned),
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.tertiary,
                    maxLines = 1,
                )
                if (total > 1) {
                    Badge(
                        containerColor = scheme.tertiaryContainer,
                        contentColor = scheme.onTertiaryContainer,
                    ) {
                        Text(
                            text = total.toString(),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
            AnimatedPinnedPreview(message = pinned)
        }
        if (total > 1) {
            IconButton(onClick = onOpenList) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.List,
                    contentDescription = stringResource(R.string.dialog_pinned_list_count, total),
                )
            }
        }
    }
    }
}

@Composable
internal fun PinnedMessagesSheet(
    messages: List<Message>,
    pinnedIndex: Int,
    senders: Map<PeerId, Profile>,
    mediaRepository: MediaRepository?,
    onJump: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val listState = rememberLazyListState()
    // Open on the current pin: with a long pin list the thread's pin must not need scrolling to.
    LaunchedEffect(pinnedIndex, messages.size) {
        if (pinnedIndex in messages.indices) listState.scrollToItem(pinnedIndex)
    }
    AppModalSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
            Text(
                text = stringResource(R.string.dialog_pinned_list),
                style = MaterialTheme.typography.headlineSmallEmphasized,
            )
            Text(
                text = pluralStringResource(
                    R.plurals.dialog_pinned_sheet_count,
                    messages.size,
                    messages.size,
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
        ) {
            itemsIndexed(
                items = messages,
                key = { _, message -> message.id.id },
            ) { index, message ->
                PinnedRow(
                    message = message,
                    index = index,
                    total = messages.size,
                    senderTitle = message.senderId?.let { senders[it]?.title },
                    selected = index == pinnedIndex,
                    mediaRepository = mediaRepository,
                    onClick = { onJump(message.id.id) },
                )
            }
        }
    }
}

@Composable
private fun PinnedRow(
    message: Message,
    index: Int,
    total: Int,
    senderTitle: String?,
    selected: Boolean,
    mediaRepository: MediaRepository?,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .background(if (selected) scheme.surfaceContainerHigh else Color.Transparent)
            .selectable(
                selected = selected,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Pin marker: the current pin is the only row with the accent rail.
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(40.dp)
                .clip(MaterialTheme.shapes.small)
                .background(if (selected) scheme.primary else scheme.outlineVariant),
        )
        Spacer(Modifier.width(9.dp))
        PinnedLeadingThumb(
            message = message,
            size = 44.dp,
            mediaRepository = mediaRepository,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = pinnedRowHeadline(senderTitle, message.outgoing),
                    style = MaterialTheme.typography.labelLargeEmphasized,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = pinnedRowTime(message.date),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
            }
            // The index is supporting text, never the row headline.
            val countStyle = with(MaterialTheme.typography.labelSmall) {
                SpanStyle(
                    color = scheme.tertiary,
                    fontSize = fontSize,
                    fontWeight = fontWeight,
                    letterSpacing = letterSpacing,
                )
            }
            Text(
                text = buildAnnotatedString {
                    withStyle(countStyle) {
                        append(stringResource(R.string.dialog_pinned_count, index + 1, total))
                    }
                    withStyle(SpanStyle(color = scheme.onSurfaceVariant)) {
                        append(" · ")
                        append(pinnedPreviewText(message))
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected) {
            Icon(
                imageVector = Icons.Filled.PushPin,
                contentDescription = stringResource(R.string.dialog_pinned_current),
                tint = scheme.tertiary,
                modifier = Modifier.padding(start = 8.dp).size(14.dp),
            )
        }
    }
}

/**
 * Pin thumbnails: the cached small preview for visual media, a type glyph otherwise so every row
 * keeps the same leading column.
 */
@Composable
private fun PinnedLeadingThumb(
    message: Message,
    size: Dp,
    mediaRepository: MediaRepository?,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.small
    val file = rememberPinnedThumb(message, mediaRepository)
    if (file != null) {
        val context = LocalContext.current
        val sizePx = with(LocalDensity.current) { size.roundToPx() }
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(file)
                .size(sizePx)
                .memoryCacheKey("${file.absolutePath}:${file.length()}:$sizePx")
                .diskCacheKey("${file.absolutePath}:${file.length()}")
                .crossfade(false)
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(shape),
        )
        return
    }
    Box(
        modifier = Modifier
            .size(size)
            .clip(shape)
            .background(scheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = pinnedTypeIcon(message.mediaKind),
            contentDescription = null,
            tint = scheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun rememberPinnedThumb(
    message: Message,
    mediaRepository: MediaRepository?,
): File? {
    val generation = mediaRepository?.cacheGeneration?.collectAsState()?.value ?: 0L
    val key = message.thumbCacheKey ?: message.mediaCacheKey
    return rememberEnsuredFile(
        generation = generation,
        identity = message.id to key,
        resolve = { key?.let { mediaRepository?.cachedFile(it) } },
        ensure = {
            val repo = mediaRepository ?: return@rememberEnsuredFile null
            if (!pinnedThumbAvailable(message.mediaKind)) return@rememberEnsuredFile null
            when (val result = repo.ensureLocalMessageThumb(message, MediaPriority.VISIBLE)) {
                is Outcome.Ok -> result.value
                is Outcome.Err -> null
            }
        },
    )
}

/** Only visual media carries a small preview on the wire. */
private fun pinnedThumbAvailable(mediaKind: String?): Boolean = when (mediaKind) {
    "photo", "video", "video_note", "gif", "sticker", "sticker_animated" -> true
    else -> false
}

private fun pinnedTypeIcon(mediaKind: String?): ImageVector = when (mediaKind) {
    "photo" -> Icons.Outlined.Image
    "video", "video_note" -> Icons.Outlined.Videocam
    "gif" -> Icons.Outlined.Gif
    "sticker", "sticker_animated" -> Icons.Outlined.EmojiEmotions
    "document" -> Icons.AutoMirrored.Outlined.InsertDriveFile
    "audio" -> Icons.Outlined.AudioFile
    "voice" -> Icons.Outlined.Mic
    "service" -> Icons.Outlined.Info
    else -> Icons.Outlined.TextFields
}

/** Row headline: the sender, or the owner of an outgoing pin, never the pin index. */
@Composable
private fun pinnedRowHeadline(senderTitle: String?, outgoing: Boolean): String =
    senderTitle?.takeIf { it.isNotBlank() }
        ?: stringResource(if (outgoing) R.string.dialog_you else R.string.dialog_pinned)

/** Banner preview: a pin change slides the preview in from the direction of travel (standard spec). */
@Composable
private fun AnimatedPinnedPreview(message: Message) {
    AnimatedContent(
        targetState = message.id.id,
        transitionSpec = {
            if (targetState >= initialState) {
                (slideInVertically { it / 2 } + fadeIn()) togetherWith
                    (slideOutVertically { -it / 2 } + fadeOut())
            } else {
                (slideInVertically { -it / 2 } + fadeIn()) togetherWith
                    (slideOutVertically { it / 2 } + fadeOut())
            }
        },
        label = "pinnedPreview",
    ) {
        Text(
            text = pinnedPreviewText(message),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun pinnedRowTime(epochSeconds: Long): String = DialogTime.formatTime(
    epochSeconds = epochSeconds,
    zone = ZoneId.systemDefault(),
    locale = LocalLocale.current.platformLocale,
    use24Hour = android.text.format.DateFormat.is24HourFormat(LocalContext.current),
)

@Composable
internal fun pinnedPreviewText(message: Message): String {
    if (message.mediaKind == "service") {
        return localizedServiceMessage(message.text.orEmpty(), message.outgoing)
    }
    return formatPinnedPreview(
        text = message.text,
        fileName = message.fileName,
        mediaKind = message.mediaKind,
        mediaLabel = pinnedMediaLabel(message.mediaKind),
    )
}

@Composable
private fun pinnedMediaLabel(mediaKind: String?): String = when (mediaKind) {
    "photo" -> stringResource(R.string.dialog_media_photo)
    "video" -> stringResource(R.string.dialog_media_video)
    "sticker", "sticker_animated" -> stringResource(R.string.dialog_media_sticker)
    "gif" -> stringResource(R.string.dialog_media_gif)
    "document" -> stringResource(R.string.dialog_media_document)
    else -> stringResource(R.string.dialog_pinned)
}

internal fun formatPinnedPreview(
    text: String?,
    fileName: String?,
    mediaKind: String?,
    mediaLabel: String,
): String {
    if (mediaKind == "service") return text?.trim().orEmpty()
    val body = text?.trim().orEmpty()
    val name = fileName?.trim().orEmpty()
    val lead = name.ifEmpty {
        mediaLabel.takeIf { kind ->
            !kind.isNullOrBlank() &&
                mediaKind != null &&
                mediaKind != "service" &&
                !body.equals(kind, ignoreCase = true)
        }.orEmpty()
    }
    return when {
        lead.isNotEmpty() && body.isNotEmpty() && !body.equals(lead, ignoreCase = true) ->
            "$lead\n$body"
        body.isNotEmpty() -> body
        lead.isNotEmpty() -> lead
        else -> mediaLabel
    }
}
