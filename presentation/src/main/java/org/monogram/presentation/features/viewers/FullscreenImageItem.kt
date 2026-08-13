package org.monogram.presentation.features.viewers

sealed interface FullscreenImageLoadState {
    data object Preview : FullscreenImageLoadState
    data class Loading(val progress: Float) : FullscreenImageLoadState
    data object Ready : FullscreenImageLoadState
    data object Error : FullscreenImageLoadState
}

data class FullscreenImageItem(
    val id: String,
    val previewSource: String,
    val originalFileId: Int = 0,
    val originalPath: String? = null,
    val caption: String? = null,
    val loadState: FullscreenImageLoadState = if (originalPath != null) {
        FullscreenImageLoadState.Ready
    } else {
        FullscreenImageLoadState.Preview
    }
) {
    val displaySource: String
        get() = originalPath ?: previewSource

    val actionSource: String
        get() = originalPath ?: previewSource

    val hasOriginalTarget: Boolean
        get() = originalFileId != 0 && originalPath == null

    companion object {
        fun source(path: String, caption: String? = null, id: String = path): FullscreenImageItem =
            FullscreenImageItem(
                id = id,
                previewSource = path,
                originalPath = path,
                caption = caption
            )
    }
}
