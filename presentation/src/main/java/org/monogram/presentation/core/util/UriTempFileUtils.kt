package org.monogram.presentation.core.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream

data class CopiedUriFile(
    val localPath: String,
    val mimeType: String?,
    val displayName: String?
)

private val INVALID_FILE_CHARS = Regex("[\\\\/:*?\"<>|]")

fun Context.copyUriToTempMediaFile(uri: Uri): CopiedUriFile? {
    return copyUriToTempFile(
        uri = uri,
        directoryName = "shared_media",
        fallbackFileName = { mimeType ->
            val extension = resolveMediaExtension(mimeType)
            "attach_${System.nanoTime()}.$extension"
        }
    )
}

fun Context.copyUriToTempDocumentFile(uri: Uri): CopiedUriFile? {
    return copyUriToTempFile(
        uri = uri,
        directoryName = "shared_documents",
        fallbackFileName = { mimeType ->
            "document_${System.nanoTime()}.${resolveDocumentExtension(mimeType)}"
        }
    )
}

internal fun resolveMediaExtension(mimeType: String?): String {
    val normalizedMimeType = mimeType?.substringBefore(';')?.trim()?.lowercase()
    return when {
        normalizedMimeType.isNullOrBlank() -> "jpg"
        normalizedMimeType == "image/gif" -> "gif"
        normalizedMimeType.startsWith("video/") -> resolveKnownSubtype(normalizedMimeType) ?: "mp4"
        normalizedMimeType.startsWith("image/") -> resolveExtensionFromMimeType(normalizedMimeType)
            ?: "jpg"

        else -> resolveExtensionFromMimeType(normalizedMimeType) ?: "jpg"
    }
}

internal fun resolveDocumentExtension(mimeType: String?): String {
    if (mimeType.isNullOrBlank()) return "bin"
    return resolveExtensionFromMimeType(mimeType) ?: "bin"
}

private fun resolveExtensionFromMimeType(mimeType: String): String? {
    return when (val normalizedMimeType = mimeType.substringBefore(';').trim().lowercase()) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/heic" -> "heic"
        "image/heif" -> "heif"
        "image/avif" -> "avif"
        "application/pdf" -> "pdf"
        else -> resolveKnownSubtype(normalizedMimeType)
    }
}

private fun resolveKnownSubtype(mimeType: String): String? {
    val normalizedMimeType = mimeType.substringBefore(';').trim().lowercase()
    val subtype = normalizedMimeType.substringAfter('/', "")
    if (subtype.isBlank()) return null
    if (subtype == "unknown" || subtype == "octet-stream") return null
    return when (subtype) {
        "jpeg" -> "jpg"
        "svg+xml" -> "svg"
        else -> subtype.takeIf { candidate ->
            candidate.all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }
        }?.substringBefore('+')
    }
}

fun Context.copyUriToTempFile(
    uri: Uri,
    directoryName: String = "shared",
    fallbackFileName: (String?) -> String = { "file_${System.nanoTime()}" }
): CopiedUriFile? {
    return try {
        if (uri.scheme == "file") {
            val path = uri.path ?: return null
            val name = File(path).name.takeIf { it.isNotBlank() }
            return CopiedUriFile(
                localPath = path,
                mimeType = contentResolver.getType(uri),
                displayName = name
            )
        }

        val mimeType = contentResolver.getType(uri)
        val displayName = queryDisplayName(uri)?.trim().takeUnless { it.isNullOrBlank() }
        val safeName = displayName
            ?.replace(INVALID_FILE_CHARS, "_")
            ?: fallbackFileName(mimeType)

        val tempDir = File(cacheDir, directoryName).apply { mkdirs() }
        val targetFile = File(tempDir, "${System.nanoTime()}_$safeName")
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(targetFile).use { output -> input.copyTo(output) }
        } ?: return null

        CopiedUriFile(
            localPath = targetFile.absolutePath,
            mimeType = mimeType,
            displayName = displayName
        )
    } catch (_: Exception) {
        null
    }
}

private fun Context.queryDisplayName(uri: Uri): String? {
    return contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null
    )?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0) cursor.getString(index) else null
    }
}
