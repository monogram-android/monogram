package org.monogram.presentation.core.util

import java.io.File

fun fileCacheKey(path: String?): String? {
    if (path.isNullOrBlank()) return null
    val file = File(path)
    if (!file.exists()) return null
    return "${file.absolutePath}:${file.lastModified()}:${file.length()}"
}

fun miniThumbnailCacheKey(data: ByteArray): String {
    return "mini:${data.contentHashCode()}:${data.size}"
}

fun mediaCacheKey(data: Any?): String? {
    return when (data) {
        null -> null
        is ByteArray -> miniThumbnailCacheKey(data)
        is File -> "${data.absolutePath}:${data.lastModified()}:${data.length()}"
        is String -> {
            when {
                data.startsWith("http://", ignoreCase = true) ||
                        data.startsWith("https://", ignoreCase = true) ||
                        data.startsWith("content:", ignoreCase = true) ||
                        data.startsWith("file:", ignoreCase = true) -> data

                else -> fileCacheKey(data) ?: data
            }
        }

        else -> data.toString()
    }
}

fun namespacedCacheKey(namespace: String, data: Any?): String? {
    val key = mediaCacheKey(data) ?: return null
    return "$namespace:$key"
}
