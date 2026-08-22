package org.monogram.data.mtproto

import android.media.MediaMetadataRetriever

internal data class MtProtoStoryVideoMetadata(
    val width: Int,
    val height: Int,
    val durationSeconds: Double,
) {
    init {
        require(width > 0) { "MTProto story video width must be positive" }
        require(height > 0) { "MTProto story video height must be positive" }
        require(durationSeconds > 0.0) { "MTProto story video duration must be positive" }
    }
}

internal fun interface MtProtoStoryVideoMetadataReader {
    fun read(path: String): MtProtoStoryVideoMetadata
}

internal object AndroidMtProtoStoryVideoMetadataReader : MtProtoStoryVideoMetadataReader {
    override fun read(path: String): MtProtoStoryVideoMetadata {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(path)
            return MtProtoStoryVideoMetadata(
                width = retriever.requiredInt(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH),
                height = retriever.requiredInt(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT),
                durationSeconds = retriever.requiredInt(MediaMetadataRetriever.METADATA_KEY_DURATION) / 1_000.0,
            )
        } finally {
            retriever.release()
        }
    }

    private fun MediaMetadataRetriever.requiredInt(key: Int): Int =
        requireNotNull(extractMetadata(key)?.toIntOrNull()) { "MTProto story video metadata is unavailable" }
}
