package org.monogram.domain.models

data class ProfilePhotoMedia(
    val id: Long,
    val previewPath: String?,
    val originalFileId: Int,
    val originalPath: String? = null,
    val animationFileId: Int = 0,
    val animationPath: String? = null
) {
    val displayPath: String?
        get() = animationPath ?: previewPath ?: originalPath
}
