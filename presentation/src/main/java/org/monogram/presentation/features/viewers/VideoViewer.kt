package org.monogram.presentation.features.viewers

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import org.monogram.presentation.core.util.IDownloadUtils

@Composable
fun VideoViewer(
    path: String,
    onDismiss: () -> Unit,
    onForward: (String) -> Unit = {},
    onDelete: ((String) -> Unit)? = null,
    onCopyLink: ((String) -> Unit)? = null,
    onCopyText: ((String) -> Unit)? = null,
    onSaveGif: ((String) -> Unit)? = null,
    caption: String? = null,
    fileId: Int = 0,
    supportsStreaming: Boolean = false,
    downloadUtils: IDownloadUtils,
    isGesturesEnabled: Boolean = true,
    isDoubleTapSeekEnabled: Boolean = true,
    seekDuration: Int = 10,
    isZoomEnabled: Boolean = true
) {
    if (path.isBlank()) {
        onDismiss()
        return
    }

    Log.d("VideoViewer", "Composing VideoViewer for path=$path, fileId=$fileId")

    val mediaItems = remember(path) { listOf(path) }
    val captions = remember(caption) { listOf(caption) }
    val fileIds = remember(fileId) { listOf(fileId) }

    MediaViewer(
        mediaItems = mediaItems,
        startIndex = 0,
        onDismiss = onDismiss,
        onForward = onForward,
        onDelete = onDelete,
        onCopyLink = onCopyLink,
        onCopyText = onCopyText,
        onSaveGif = onSaveGif,
        captions = captions,
        fileIds = fileIds,
        supportsStreaming = supportsStreaming,
        downloadUtils = downloadUtils,
        isGesturesEnabled = isGesturesEnabled,
        isDoubleTapSeekEnabled = isDoubleTapSeekEnabled,
        seekDuration = seekDuration,
        isZoomEnabled = isZoomEnabled,
        isAlwaysVideo = true
    )
}
