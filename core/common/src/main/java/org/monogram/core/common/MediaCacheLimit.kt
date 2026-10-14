package org.monogram.core.common

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** User-chosen media cache ceiling. `bytes == null` means size eviction is off. */
enum class MediaCacheLimit(val stored: String, val bytes: Long?) {
    MB_500("500mb", 500L * 1024L * 1024L),
    GB_1("1gb", 1L shl 30),
    GB_2("2gb", 2L shl 30),
    GB_5("5gb", 5L shl 30),
    GB_10("10gb", 10L shl 30),
    UNLIMITED("unlimited", null),
    ;

    companion object {
        val DEFAULT = GB_1

        fun fromStored(raw: String?): MediaCacheLimit =
            entries.firstOrNull { it.stored == raw?.trim() } ?: DEFAULT
    }
}

/** The choice lives beside the app files, not inside the media directory that clear deletes. */
object MediaCacheLimitStore {
    private const val FILE_NAME = "media-cache-limit"

    private val mutable = MutableStateFlow(MediaCacheLimit.DEFAULT)
    val current: StateFlow<MediaCacheLimit> = mutable.asStateFlow()

    fun install(directory: File) {
        mutable.value = read(directory)
    }

    fun read(directory: File): MediaCacheLimit {
        val file = File(directory, FILE_NAME)
        if (!file.isFile) return MediaCacheLimit.DEFAULT
        return MediaCacheLimit.fromStored(runCatching { file.readText() }.getOrNull())
    }

    fun write(directory: File, limit: MediaCacheLimit) {
        directory.mkdirs()
        File(directory, FILE_NAME).writeText(limit.stored)
        mutable.value = limit
    }
}
