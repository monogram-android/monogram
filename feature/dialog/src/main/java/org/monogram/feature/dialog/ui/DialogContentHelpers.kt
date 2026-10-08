package org.monogram.feature.dialog.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.monogram.core.models.Message
import org.monogram.core.models.UploadItem
import org.monogram.core.models.canForwardFrom
import org.monogram.core.models.isForwardSourceShape
import org.monogram.core.ui.media.showsMiniPlayer
import org.monogram.feature.dialog.DialogComponent
import org.monogram.feature.dialog.DialogDayKind
import org.monogram.feature.dialog.DialogStore
import org.monogram.feature.dialog.DialogTime
import org.monogram.feature.dialog.R
import java.io.File
import java.time.ZoneId
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds


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
        canEdit = message.outgoing &&
            !message.pending &&
            !message.text.isNullOrBlank() &&
            state.canSendPlain,
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
        androidx.compose.material3.Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
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
    val displayName = queryDisplayName(context, uri)
        ?.substringAfterLast('/')
        ?.ifBlank { null }
        ?: "file.$ext"
    val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")
    val dest = File(context.cacheDir, "attach-${System.nanoTime()}-$safeName")
    runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
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
        }.getOrNull()
        if (compressed != null) {
            dest.delete()
            val (width, height, duration) = localMediaBounds(compressed.absolutePath, "video", "video/mp4")
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
        val retriever = android.media.MediaMetadataRetriever()
        return runCatching {
            retriever.setDataSource(path)
            val width = retriever.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH,
            )?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT,
            )?.toIntOrNull() ?: 0
            val durationMs = retriever.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_DURATION,
            )?.toLongOrNull() ?: 0L
            Triple(width, height, ((durationMs + 500) / 1000).toInt())
        }.getOrDefault(Triple(0, 0, 0)).also {
            runCatching { retriever.release() }
        }
    }
    return Triple(0, 0, 0)
}

internal fun Message.belongsToSameBlockAs(other: Message?, zone: ZoneId): Boolean =
    other != null &&
        outgoing == other.outgoing &&
        senderId == other.senderId &&
        DialogTime.sameLocalDay(date, other.date, zone) &&
        kotlin.math.abs(date - other.date) <= MESSAGE_GROUPING_WINDOW_SECONDS

internal const val MESSAGE_GROUPING_WINDOW_SECONDS = 5 * 60L


/**
 * Docked now-playing bar. It appears only while a media session is alive and the
 * fullscreen viewer is closed, so the viewer chrome and the mini player never
 * compete for the same space.
 */
@Composable
internal fun DialogMiniPlayer() {
    val context = LocalContext.current
    val session = remember(context) { org.monogram.core.ui.media.MediaPlaybackHolder.session(context) }
    val reopen = LocalReopenMediaViewer.current
    var restore by remember { mutableStateOf(false) }
    val visible = session.showsMiniPlayer()
    val motion = org.monogram.core.ui.media.mediaViewerMotionEnabled()
    if (restore) {
        org.monogram.core.ui.media.MiniPlayerRestoreViewer(
            session = session,
            onDismiss = { restore = false },
        )
    }
    AnimatedVisibility(
        visible = visible && !restore,
        enter = slideInVertically(
            org.monogram.core.ui.media.MediaMotion.spatial(motion),
        ) { it } + fadeIn(),
        exit = slideOutVertically(
            org.monogram.core.ui.media.MediaMotion.spatial(motion),
        ) { it } + fadeOut(),
    ) {
        org.monogram.core.ui.media.MiniPlayerBar(
            session = session,
            onExpand = { if (!reopen()) restore = true },
            onStop = { session.stop() },
        )
    }
}


@Suppress("MissingPermission")
internal fun locationManager(
    context: android.content.Context,
): android.location.LocationManager? =
    context.getSystemService(android.content.Context.LOCATION_SERVICE)
        as? android.location.LocationManager

internal fun locationProviders(
    manager: android.location.LocationManager,
): List<String> = linkedSetOf<String>().apply {
    addAll(manager.getProviders(true))
    add(android.location.LocationManager.GPS_PROVIDER)
    add(android.location.LocationManager.NETWORK_PROVIDER)
    add(android.location.LocationManager.PASSIVE_PROVIDER)
    if (android.os.Build.VERSION.SDK_INT >= 31) {
        add(android.location.LocationManager.FUSED_PROVIDER)
    }
}.filter { name ->
    name == android.location.LocationManager.PASSIVE_PROVIDER ||
        (android.os.Build.VERSION.SDK_INT >= 31 &&
            name == android.location.LocationManager.FUSED_PROVIDER) ||
        manager.isProviderEnabled(name)
}

@Suppress("MissingPermission")
internal fun lastKnownLocation(
    context: android.content.Context,
): Pair<Double, Double>? {
    val manager = locationManager(context) ?: return null
    return locationProviders(manager).mapNotNull { provider ->
        runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
    }.maxByOrNull { it.time }?.let { it.latitude to it.longitude }
}

@Suppress("MissingPermission")
internal suspend fun currentLocation(
    context: android.content.Context,
    timeoutMs: Long = 8_000,
): Pair<Double, Double>? = withContext(Dispatchers.IO) {
    val manager = locationManager(context) ?: return@withContext null
    val providers = locationProviders(manager)
    if (providers.isEmpty()) return@withContext null
    val lastKnown = providers.mapNotNull { provider ->
        runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
    }.maxByOrNull { it.time }
    val fresh = coroutineScope {
        providers.map { provider ->
            async {
                withTimeoutOrNull(timeoutMs.milliseconds) { awaitSingleFix(manager, provider) }
            }
        }.mapNotNull { it.await() }.maxByOrNull { it.time }
    }
    listOfNotNull(fresh, lastKnown).maxByOrNull { it.time }?.let { it.latitude to it.longitude }
}

/** One fix from the platform service, suspended until it arrives, the caller cancels or it errors. */
@Suppress("MissingPermission")
internal suspend fun awaitSingleFix(
    manager: android.location.LocationManager,
    provider: String,
): android.location.Location? = suspendCancellableCoroutine { continuation ->
    val listener = object : android.location.LocationListener {
        override fun onLocationChanged(location: android.location.Location) {
            runCatching { manager.removeUpdates(this) }
            if (continuation.isActive) continuation.resumeWith(Result.success(location))
        }

        override fun onProviderDisabled(disabled: String) = Unit

        override fun onProviderEnabled(enabled: String) = Unit

        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
    }
    continuation.invokeOnCancellation {
        runCatching { manager.removeUpdates(listener) }
    }
    try {
        manager.requestLocationUpdates(
            provider,
            0L,
            0f,
            listener,
            android.os.Looper.getMainLooper(),
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            manager.getCurrentLocation(provider, null, { runnable -> runnable.run() }) { location ->
                runCatching { manager.removeUpdates(listener) }
                if (continuation.isActive) continuation.resumeWith(Result.success(location))
            }
        }
    } catch (error: Throwable) {
        if (continuation.isActive) continuation.resumeWith(Result.success(null))
    }
}
