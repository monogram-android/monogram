package org.monogram.feature.dialog.ui


import android.media.MediaMetadataRetriever
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.monogram.core.models.Message
import org.monogram.core.models.UploadItem
import org.monogram.core.models.canForwardFrom
import org.monogram.core.models.isForwardSourceShape
import org.monogram.core.ui.media.MediaMotion
import org.monogram.core.ui.media.MediaPlaybackHolder
import org.monogram.core.ui.media.MiniPlayerBar
import org.monogram.core.ui.media.MiniPlayerRestoreViewer
import org.monogram.core.ui.media.mediaViewerMotionEnabled
import org.monogram.core.ui.media.showsMiniPlayer
import org.monogram.feature.dialog.DialogComponent
import org.monogram.feature.dialog.DialogDayKind
import org.monogram.feature.dialog.DialogStore
import org.monogram.feature.dialog.DialogTime
import org.monogram.feature.dialog.DialogTime.sameLocalDay
import org.monogram.feature.dialog.R
import java.io.File
import java.time.ZoneId
import java.util.Locale


internal fun messageMenuActions(
    state: DialogStore.State,
    message: Message,
): MessageMenuActions {
    val canSend = state.canSendPlain || state.canSendPhotos
    val sourceShape = message.isForwardSourceShape()
    val canForward = message.canForwardFrom(state.canForward)
    return MessageMenuActions(
        canReply = canSend && !message.pending && message.id.id > 0,
        canCopy = !message.text.isNullOrBlank(),
        canEdit = message.outgoing && message.id.id > 0 && message.id.id !in state.checklistMutations && !message.pending && (!message.text.isNullOrBlank() || message.checklist != null) && (if (message.checklist != null) state.canCreateChecklist else state.canSendPlain),
        canDelete = canDeleteMessage(state, message),
        canForward = canForward,
        forwardRestricted = sourceShape && state.canForward && message.noforwards,
    )
}

@Composable
internal fun chatStatsSubtitle(
    isChannel: Boolean,
    isGroup: Boolean,
    members: Int?,
    online: Int?,
): String? {
    val count = members?.takeIf { it > 0 } ?: return null
    if (!isChannel && !isGroup) return null
    val res = LocalResources.current
    if (isChannel) {
        return res.getQuantityString(
            R.plurals.dialog_subscribers_count,
            count,
            formatCount(count),
        )
    }
    val text = res.getQuantityString(
        R.plurals.dialog_members_count,
        count,
        formatCount(count),
    )
    val onlineCount = online?.takeIf { it > 0 } ?: return text
    return "$text, " + res.getQuantityString(
        R.plurals.dialog_online_count,
        onlineCount,
        formatCount(onlineCount),
    )
}

internal fun formatCount(count: Int): String =
    java.text.NumberFormat.getIntegerInstance().format(count)

@Composable
internal fun DialogDateSeparator(epochSeconds: Long, zone: ZoneId) {
    val now = remember { System.currentTimeMillis() / 1000L }
    val locale = remember { Locale.getDefault() }
    val label = remember(epochSeconds, now, zone, locale) {
        DialogTime.dayLabel(epochSeconds, now, zone, locale)
    }
    val text = when (label.kind) {
        DialogDayKind.Today -> stringResource(R.string.dialog_date_today)
        DialogDayKind.Yesterday -> stringResource(R.string.dialog_date_yesterday)
        else -> label.text
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.8f),
        ) {
            Text(
                text = text,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal data class PickedMedia(
    val path: String,
    val kind: String,
    val fileName: String,
    val mimeType: String,
    val duration: Int,
    val width: Int,
    val height: Int,
) {
    fun toUploadItem(caption: String = "") = UploadItem(
        path = path,
        kind = kind,
        mimeType = mimeType,
        fileName = fileName,
        caption = caption,
        duration = duration,
        width = width,
        height = height,
    )
}

internal fun sendPickedMedia(
    component: DialogComponent,
    composer: MutableState<TextFieldValue>,
    picked: List<PickedMedia>,
    attachSingle: Boolean,
) {
    if (picked.isEmpty()) return
    component.onCloseAttachSheet()
    if (picked.size == 1 && attachSingle && picked.first().kind == "photo") {
        component.onAttachPhoto(picked.first().path)
        return
    }
    component.onAttachMedia(picked.map { it.toUploadItem() })
}

internal suspend fun copyPickedMedia(
    context: android.content.Context,
    uri: android.net.Uri,
    kind: String,
    fallbackExt: String,
    fallbackMime: String = "",
): PickedMedia? = withContext(Dispatchers.IO) {
    val mime = context.contentResolver.getType(uri).orEmpty().ifBlank { fallbackMime }
    val resolvedKind = when {
        kind == "document" -> "document"
        mime.startsWith("video/") -> "video"
        mime.startsWith("image/") -> "photo"
        else -> kind
    }
    val ext = runCatching {
        android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: fallbackExt
    val displayName =
        queryDisplayName(context, uri)?.substringAfterLast('/')?.ifBlank { null } ?: "file.$ext"
    val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")
    val dest = File(context.cacheDir, "attach-${System.nanoTime()}-$safeName")
    runCatching {
        val stream = context.contentResolver.openInputStream(uri) ?: return@withContext null
        stream.use { input ->
            dest.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > 4L * 1024 * 1024 * 1024) throw java.io.IOException("selected media exceeds the size limit")
                    output.write(buffer, 0, count)
                }
            }
        }
    }.onFailure { error ->
        dest.delete()
        if (error is kotlinx.coroutines.CancellationException) throw error
    }
    if (!dest.exists() || dest.length() <= 0L) return@withContext null
    if (resolvedKind == "photo" && reencodablePhoto(mime, safeName)) {
        val hq = File(context.cacheDir, "attach-hq-${System.nanoTime()}.jpg")
        val prepared = prepareOutgoingPhoto(dest, hq)
        if (prepared != null) {
            dest.delete()
            return@withContext PickedMedia(
                path = prepared.file.absolutePath,
                kind = resolvedKind,
                fileName = displayName,
                mimeType = "image/jpeg",
                duration = 0,
                width = prepared.width,
                height = prepared.height,
            )
        }
    }
    if (resolvedKind == "video") {
        val out = File(context.cacheDir, "attach-video-${System.nanoTime()}.mp4")
        val compressed = runCatching {
            compressVideoLikeAndroid(context, dest, out)
        }.onFailure { error ->
            if (error is kotlinx.coroutines.CancellationException) throw error
        }.getOrNull()
        if (compressed != null) {
            dest.delete()
            val (width, height, duration) = localMediaBounds(
                compressed.absolutePath, "video", "video/mp4"
            )
            return@withContext PickedMedia(
                path = compressed.absolutePath,
                kind = resolvedKind,
                fileName = displayName,
                mimeType = "video/mp4",
                duration = duration,
                width = width,
                height = height,
            )
        }
    }
    val (width, height, duration) = localMediaBounds(dest.absolutePath, resolvedKind, mime)
    PickedMedia(
        path = dest.absolutePath,
        kind = resolvedKind,
        fileName = displayName,
        mimeType = mime,
        duration = duration,
        width = width,
        height = height,
    )
}

internal fun Modifier.collapseComposerOnTap(
    selected: Boolean,
    onCollapse: () -> Unit,
): Modifier {
    if (!selected) return this
    return pointerInput(selected) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.any { it.changedToDownIgnoreConsumed() }) {
                    onCollapse()
                }
            }
        }
    }
}

internal fun queryDisplayName(context: android.content.Context, uri: android.net.Uri): String? {
    val projection = arrayOf(android.provider.OpenableColumns.DISPLAY_NAME)
    return runCatching {
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()
}

internal fun localMediaBounds(path: String, kind: String, mime: String): Triple<Int, Int, Int> {
    if (kind == "photo" || mime.startsWith("image/")) {
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(path, opts)
        return Triple(opts.outWidth.coerceAtLeast(0), opts.outHeight.coerceAtLeast(0), 0)
    }
    if (kind == "video" || mime.startsWith("video/")) {
        val retriever = MediaMetadataRetriever()
        return runCatching {
            retriever.setDataSource(path)
            val width = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH,
            )?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT,
            )?.toIntOrNull() ?: 0
            val durationMs = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION,
            )?.toLongOrNull() ?: 0L
            Triple(width, height, ((durationMs + 500) / 1000).toInt())
        }.getOrDefault(Triple(0, 0, 0)).also {
            runCatching { retriever.release() }
        }
    }
    return Triple(0, 0, 0)
}

internal fun Message.belongsToSameBlockAs(other: Message?, zone: ZoneId): Boolean =
    other != null && outgoing == other.outgoing && senderId == other.senderId && sameLocalDay(
        date, other.date, zone
    ) && kotlin.math.abs(date - other.date) <= MESSAGE_GROUPING_WINDOW_SECONDS

internal const val MESSAGE_GROUPING_WINDOW_SECONDS = 5 * 60L


/**
 * Docked now-playing bar. It appears only while a media session is alive and the
 * fullscreen viewer is closed, so the viewer chrome and the mini player never
 * compete for the same space.
 */
@Composable
internal fun DialogMiniPlayer() {
    val context = LocalContext.current
    val session = remember(context) { MediaPlaybackHolder.session(context) }
    val reopen = LocalReopenMediaViewer.current
    var restore by remember { mutableStateOf(false) }
    val visible = session.showsMiniPlayer()
    val motion = mediaViewerMotionEnabled()
    if (restore) {
        MiniPlayerRestoreViewer(
            session = session,
            onDismiss = { restore = false },
        )
    }
    AnimatedVisibility(
        visible = visible && !restore,
        enter = slideInVertically(
            MediaMotion.spatial(motion),
        ) { it } + fadeIn(),
        exit = slideOutVertically(
            MediaMotion.spatial(motion),
        ) { it } + fadeOut(),
    ) {
        MiniPlayerBar(
            session = session,
            onExpand = { if (!reopen()) restore = true },
            onStop = { session.stop() },
        )
    }
}
