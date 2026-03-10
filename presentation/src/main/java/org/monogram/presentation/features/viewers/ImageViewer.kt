package org.monogram.presentation.features.viewers

import androidx.compose.runtime.Composable
import org.monogram.presentation.core.util.IDownloadUtils

@Composable
fun ImageViewer(
    images: List<String>,
    startIndex: Int = 0,
    onDismiss: () -> Unit,
    autoDownload: Boolean = true,
    onPageChanged: ((Int) -> Unit)? = null,
    onForward: (String) -> Unit = {},
    onDelete: ((String) -> Unit)? = null,
    onCopyLink: ((String) -> Unit)? = null,
    onCopyText: ((String) -> Unit)? = null,
    onVideoClick: ((String) -> Unit)? = null,
    captions: List<String?> = emptyList(),
    downloadUtils: IDownloadUtils,
    showImageNumber: Boolean = true
) {
    MediaViewer(
        mediaItems = images,
        startIndex = startIndex,
        onDismiss = onDismiss,
        autoDownload = autoDownload,
        onPageChanged = onPageChanged,
        onForward = onForward,
        onDelete = onDelete,
        onCopyLink = onCopyLink,
        onCopyText = onCopyText,
        captions = captions,
        downloadUtils = downloadUtils,
        showImageNumber = showImageNumber
    )
}
