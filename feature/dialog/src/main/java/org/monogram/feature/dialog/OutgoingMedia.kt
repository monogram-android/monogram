package org.monogram.feature.dialog

import org.monogram.core.common.telegram.TelegramError
import java.io.File

internal const val LOCAL_MEDIA_PREFIX = "local:"

internal fun localMediaCacheKey(path: String): String = LOCAL_MEDIA_PREFIX + path

internal fun localMediaPath(cacheKey: String?): String? {
    if (cacheKey.isNullOrBlank() || !cacheKey.startsWith(LOCAL_MEDIA_PREFIX)) return null
    return cacheKey.removePrefix(LOCAL_MEDIA_PREFIX).takeIf { it.isNotBlank() }
}

internal fun localOutgoingFile(cacheKey: String?): File? {
    val path = localMediaPath(cacheKey) ?: return null
    return File(path).takeIf { it.isFile && it.length() > 0L }
}

/** Native emits `UpdateMessageId:{random_id}:{id}` through the existing Ignored event. */
internal fun parseUpdateMessageId(kind: String): Pair<Long, Int>? {
    val prefix = "UpdateMessageId:"
    if (!kind.startsWith(prefix)) return null
    val parts = kind.removePrefix(prefix).split(':')
    if (parts.size != 2) return null
    val randomId = parts[0].toLongOrNull() ?: return null
    val messageId = parts[1].toIntOrNull() ?: return null
    if (randomId == 0L || messageId <= 0) return null
    return randomId to messageId
}

internal fun isTransientSendFailure(error: TelegramError): Boolean =
    error.kind == TelegramError.Kind.Network ||
        error.httpCode == -503 ||
        error.type.equals("Timeout", ignoreCase = true)
