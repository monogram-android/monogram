package org.monogram.network.http.internal

import java.io.File

// Rust StagedDownload publishes atomically from <destination>.<pid>.<sequence>.part.
internal fun nativeStagingFiles(destination: File): List<File> {
    val prefix = "${destination.name}."
    return destination.parentFile?.listFiles { file ->
        file.isFile && isNativeStagingName(file.name, prefix)
    }?.toList().orEmpty()
}

/** `<destination>.<pid>.<sequence>.part`, both counters non-empty decimal runs. */
internal fun isNativeStagingName(name: String, prefix: String): Boolean {
    if (name.length < prefix.length + STAGING_SUFFIX.length) return false
    if (!name.startsWith(prefix) || !name.endsWith(STAGING_SUFFIX)) return false
    val body = name.substring(prefix.length, name.length - STAGING_SUFFIX.length)
    val separator = body.indexOf('.')
    if (separator <= 0 || separator == body.length - 1) return false
    return body.take(separator).all { it in '0'..'9' } &&
        body.substring(separator + 1).all { it in '0'..'9' }
}

private const val STAGING_SUFFIX = ".part"
