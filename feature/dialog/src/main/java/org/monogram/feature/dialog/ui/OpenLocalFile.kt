package org.monogram.feature.dialog.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.monogram.core.common.AppLog

internal const val APK_MIME = "application/vnd.android.package-archive"

internal fun mimeFromName(name: String): String {
    val lower = name.lowercase()
    return when {
        lower.endsWith(".pdf") -> "application/pdf"
        lower.endsWith(".zip") -> "application/zip"
        lower.endsWith(".txt") -> "text/plain"
        lower.endsWith(".apk") -> APK_MIME
        lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
        lower.endsWith(".png") -> "image/png"
        lower.endsWith(".webp") -> "image/webp"
        lower.endsWith(".gif") -> "image/gif"
        lower.endsWith(".mp4") || lower.endsWith(".m4v") -> "video/mp4"
        lower.endsWith(".webm") -> "video/webm"
        lower.endsWith(".mov") -> "video/quicktime"
        lower.endsWith(".mkv") -> "video/x-matroska"
        lower.endsWith(".3gp") -> "video/3gpp"
        lower.endsWith(".mp3") -> "audio/mpeg"
        lower.endsWith(".ogg") -> "audio/ogg"
        lower.endsWith(".m4a") -> "audio/mp4"
        else -> "application/octet-stream"
    }
}

internal fun shareFileName(name: String): String {
    val mime = mimeFromName(name)
    val raw = name.substringAfterLast('/').substringAfterLast('\\').trim()
    val cleaned = buildString(raw.length) {
        for (ch in raw) {
            append(if (ch.isLetterOrDigit() || ch in "._- ()[]") ch else '_')
        }
    }.trim('.', ' ').ifBlank { "file" }
    val withExt = if (mime != APK_MIME) {
        cleaned
    } else if (cleaned.endsWith(".apk", ignoreCase = true)) {
        cleaned.dropLast(4) + ".apk"
    } else {
        "$cleaned.apk"
    }
    if (withExt.length <= 180) return withExt
    val ext = withExt.substringAfterLast('.', missingDelimiterValue = "")
    val stem = if (ext.isEmpty()) withExt else withExt.substringBeforeLast('.')
    val keep = stem.take((180 - ext.length - 1).coerceAtLeast(1))
    return if (ext.isEmpty()) keep else "$keep.$ext"
}

internal fun fileForExternalView(source: File, displayName: String): File {
    val name = shareFileName(displayName)
    val root = source.parentFile?.parentFile ?: source.parentFile
    val dest = File(File(root, "open"), name)
    if (source.canonicalFile == dest.canonicalFile) return source
    dest.parentFile?.mkdirs()
    if (dest.exists() && dest.length() == source.length() && dest.lastModified() >= source.lastModified()) {
        return dest
    }
    source.copyTo(dest, overwrite = true)
    return dest
}

internal fun needsUnknownSources(context: Context, mime: String): Boolean {
    if (mime != APK_MIME) return false
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        !context.packageManager.canRequestPackageInstalls()
    } else {
        @Suppress("DEPRECATION")
        Settings.Secure.getInt(
            context.contentResolver,
            Settings.Secure.INSTALL_NON_MARKET_APPS,
            0,
        ) != 1
    }
}

internal fun unknownSourcesIntent(packageName: String): Intent =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:$packageName"))
    } else {
        Intent(Settings.ACTION_SECURITY_SETTINGS)
    }

internal fun viewFileIntent(context: Context, file: File, mime: String): Intent {
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.files",
        file,
    )
    return Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

internal fun startViewFile(context: Context, file: File, mime: String) {
    try {
        context.startActivity(viewFileIntent(context, file, mime))
    } catch (e: SecurityException) {
        AppLog.warn("file", "SecurityException opening $file ($mime): ${e.message}")
        if (mime == APK_MIME) throw e
        context.startActivity(viewFileIntent(context, file, "application/octet-stream"))
    } catch (e: ActivityNotFoundException) {
        AppLog.warn("file", "ActivityNotFoundException opening $file ($mime): ${e.message}")
        if (mime == APK_MIME || mime == "application/octet-stream") throw e
        context.startActivity(viewFileIntent(context, file, "application/octet-stream"))
    }
}

internal suspend fun openDownloadedFile(context: Context, file: File, displayName: String): Boolean {
    if (!file.exists()) return false
    val mime = mimeFromName(displayName)
    return runCatching {
        val share = withContext(Dispatchers.IO) { fileForExternalView(file, displayName) }
        startViewFile(context, share, mime)
        true
    }.onFailure { e ->
        AppLog.warn("file", "failed to open $displayName: ${e.message}")
    }.getOrDefault(false)
}
