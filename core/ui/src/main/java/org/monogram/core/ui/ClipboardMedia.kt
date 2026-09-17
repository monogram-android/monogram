package org.monogram.core.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import androidx.core.content.FileProvider
import java.io.File

fun clipboardMediaExtension(mime: String): String = when {
    mime.contains("png") -> "png"
    mime.contains("webp") -> "webp"
    mime.contains("gif") -> "gif"
    mime.contains("webm") -> "webm"
    mime.contains("quicktime") || mime.contains("mov") -> "mov"
    mime.startsWith("video/") -> "mp4"
    else -> "jpg"
}

/** Cache files are extensionless hashes; FileProvider getType needs a real name. */
fun clipboardMediaCopy(source: File, cacheDir: File, mime: String): File {
    val dir = File(cacheDir, "clipboard").apply { mkdirs() }
    val dest = File(dir, "copy.${clipboardMediaExtension(mime)}")
    source.copyTo(dest, overwrite = true)
    return dest
}

fun copyMediaToClipboard(context: Context, file: File, mime: String): Boolean = runCatching {
    if (!file.isFile || file.length() <= 0L) return false
    val named = clipboardMediaCopy(file, context.cacheDir, mime)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", named)
    val clip = ClipData(ClipDescription("Image", arrayOf(mime)), ClipData.Item(uri))
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(clip)
    true
}.getOrDefault(false)
