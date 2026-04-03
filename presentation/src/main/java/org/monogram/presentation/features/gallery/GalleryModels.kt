package org.monogram.presentation.features.gallery

import android.net.Uri

enum class GalleryFilter {
    All,
    Photos,
    Videos
}

sealed class BucketFilter(val key: String) {
    data object All : BucketFilter("all")
    data object Camera : BucketFilter("camera")
    data object Screenshots : BucketFilter("screenshots")
    data class Custom(val name: String) : BucketFilter("custom_$name")
}

data class GalleryMediaItem(
    val uri: Uri,
    val dateAdded: Long,
    val duration: Long,
    val bucketName: String,
    val relativePath: String,
    val isCamera: Boolean,
    val isScreenshot: Boolean
) {
    val isVideo: Boolean
        get() = duration > 0
}
