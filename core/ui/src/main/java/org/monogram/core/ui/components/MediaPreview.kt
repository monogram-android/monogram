package org.monogram.core.ui.components

import android.content.Context
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.media3.datasource.DataSource
import org.monogram.core.ui.R
import org.monogram.core.ui.copyMediaToClipboard
import org.monogram.core.ui.gzipFile
import org.monogram.core.ui.webmFile
import org.monogram.core.ui.media.MediaAlbumState
import org.monogram.core.ui.media.MediaPlaybackHolder
import org.monogram.core.ui.media.MediaSource
import org.monogram.core.ui.media.MediaViewerActions
import org.monogram.core.ui.media.MediaViewerHost
import org.monogram.core.ui.media.MediaViewerItem
import org.monogram.core.ui.media.MediaViewerKind
import org.monogram.core.ui.videoFile
import java.io.File

@Suppress("DEPRECATION")
@Composable
fun MediaPreviewWindow(onDismiss: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    val background = Color.Black
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(
        usePlatformDefaultWidth = false,
        decorFitsSystemWindows = false,
    )) {
        val view = LocalView.current
        androidx.compose.runtime.SideEffect {
            val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
            WindowCompat.setDecorFitsSystemWindows(window, false)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                window.statusBarColor = android.graphics.Color.TRANSPARENT
                window.navigationBarColor = android.graphics.Color.TRANSPARENT
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
            }
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
        Box(Modifier.fillMaxSize().background(background), content = content)
    }
}

/**
 * Single-item entry point used outside the chat (profile photos, instant view,
 * compact player going fullscreen). It renders the same M3E shell as the album
 * viewer so photo and video behaviour never diverges.
 */
@Composable
fun MediaPreviewViewer(
    file: File?,
    onDismiss: () -> Unit,
    contentDescription: String,
    loading: Boolean = false,
    fallbackTitle: String? = null,
    loop: Boolean = false,
    muted: Boolean = loop,
    caption: String? = null,
    uri: Uri? = null,
    forceVideo: Boolean = false,
    videoDataSourceFactory: DataSource.Factory? = null,
    failed: Boolean = false,
    onRetry: (() -> Unit)? = null,
    stateKey: String = uri?.toString() ?: file?.absolutePath.orEmpty(),
    asDialog: Boolean = true,
) {
    val context = LocalContext.current
    val ready = file?.takeIf { it.exists() && it.length() > 0L }
    val video = forceVideo || ready?.let(::videoFile) == true
    val presentable = video || (ready != null && !gzipFile(ready)) || (uri != null && forceVideo)

    if (!presentable) {
        if (asDialog) {
            MediaPreviewWindow(onDismiss) {
                UnsupportedMediaBody(fallbackTitle, failed, onRetry)
            }
        } else {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                UnsupportedMediaBody(fallbackTitle, failed, onRetry)
            }
        }
        return
    }

    val source: MediaSource? = when {
        uri != null -> MediaSource.Stream(uri, videoDataSourceFactory)
        ready != null -> MediaSource.Local(ready)
        else -> null
    }
    val item = remember(
        stateKey,
        source,
        video,
        caption,
        loop,
        loading,
        failed,
    ) {
        MediaViewerItem(
            id = stateKey,
            kind = if (video) MediaViewerKind.VIDEO else MediaViewerKind.PHOTO,
            source = source,
            // While a video is still downloading, its local preview doubles as the poster.
            preview = if (video) ready else null,
            caption = caption,
            loading = loading,
            failed = failed,
            forceLoop = loop,
        )
    }
    val album = remember(item) { MediaAlbumState(listOf(item), 0) }
    val session = remember(context) { MediaPlaybackHolder.session(context) }
    if (asDialog) {
        MediaViewerHost(
            album = album,
            onDismiss = onDismiss,
            actions = previewCopyActions(context, onRetry),
            session = session,
            mediaLabel = contentDescription,
            startMutedOverride = muted,
        )
    } else {
        Box(Modifier.fillMaxSize()) {
            MediaViewerHost(
                album = album,
                onDismiss = onDismiss,
                actions = previewCopyActions(context, onRetry),
                session = session,
                mediaLabel = contentDescription,
                startMutedOverride = muted,
            )
        }
    }
}

private fun previewCopyActions(
    context: Context,
    onRetry: (() -> Unit)?,
): MediaViewerActions = MediaViewerActions(
    onRetry = { onRetry?.invoke() },
    canRetry = onRetry != null,
    onCopyMedia = { item ->
        val file = when (val source = item.source) {
            is MediaSource.Local -> source.file.takeIf { it.exists() && it.length() > 0L }
            else -> item.preview?.takeIf { it.exists() && it.length() > 0L }
        }
        val mime = when {
            file == null -> null
            webmFile(file) -> "video/webm"
            videoFile(file) -> "video/mp4"
            else -> "image/jpeg"
        }
        val copied = file != null && mime != null && copyMediaToClipboard(context, file, mime)
        Toast.makeText(
            context,
            when {
                !copied -> R.string.media_action_copy_failed
                item.isVideo -> R.string.media_action_copied_video
                else -> R.string.media_action_copied_image
            },
            Toast.LENGTH_SHORT,
        ).show()
    },
)

@Composable
private fun BoxScope.UnsupportedMediaBody(
    fallbackTitle: String?,
    failed: Boolean,
    onRetry: (() -> Unit)?,
) {
    if (failed && onRetry != null) {
        Surface(Modifier.align(Alignment.Center), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.media_video_error), style = MaterialTheme.typography.bodyLarge)
                TextButton(onClick = onRetry) { Text(stringResource(R.string.media_video_retry)) }
            }
        }
        return
    }
    PeerAvatar(
        title = fallbackTitle.orEmpty(),
        size = 220.dp,
        imageFile = null,
        modifier = Modifier.align(Alignment.Center),
    )
}

internal fun boundedMediaOffset(offset: Offset, scale: Float, viewport: Size, image: Size): Offset {
    if (scale <= 1f || viewport.width <= 0f || viewport.height <= 0f) return Offset.Zero
    val fitted = fittedMediaSize(viewport, image)
    val x = ((fitted.width * scale - viewport.width) / 2f).coerceAtLeast(0f)
    val y = ((fitted.height * scale - viewport.height) / 2f).coerceAtLeast(0f)
    return Offset(offset.x.coerceIn(-x, x), offset.y.coerceIn(-y, y))
}

internal fun fittedMediaSize(viewport: Size, image: Size): Size {
    if (image.width <= 0f || image.height <= 0f) return viewport
    val fit = minOf(viewport.width / image.width, viewport.height / image.height)
    return Size(image.width * fit, image.height * fit)
}

internal fun focalMediaOffset(offset: Offset, focal: Offset, center: Offset, oldScale: Float, newScale: Float, pan: Offset): Offset =
    (offset + center - focal) * (newScale / oldScale) + focal - center + pan

internal fun shouldDismissMedia(distance: Float, height: Float): Boolean =
    height > 0f && kotlin.math.abs(distance) >= height * 0.18f
