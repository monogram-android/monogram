package org.monogram.feature.dialog.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** System actions for media: sharing, copying, and saving. */
internal object MediaShareActions {

    fun extensionFor(mime: String): String = when (mime) {
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "video/mp4" -> "mp4"
        "video/webm" -> "webm"
        "video/quicktime" -> "mov"
        else -> "jpg"
    }

    fun mimeFor(name: String?, fallbackMedia: Boolean): String = when {
        name == null -> if (fallbackMedia) "video/mp4" else "image/jpeg"
        else -> mimeFromName(name).takeIf { it != "application/octet-stream" }
            ?: if (fallbackMedia) "video/mp4" else "image/jpeg"
    }

    fun contentUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    fun shareIntent(context: Context, file: File, mime: String, subject: String?): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, contentUri(context, file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (!subject.isNullOrBlank()) putExtra(Intent.EXTRA_SUBJECT, subject)
        }
        val chooser = Intent.createChooser(send, null).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return chooser
    }

    fun share(context: Context, file: File, mime: String, subject: String? = null): Boolean = runCatching {
        context.startActivity(shareIntent(context, file, mime, subject))
        true
    }.getOrDefault(false)

    fun copyToClipboard(context: Context, file: File, mime: String): Boolean =
        org.monogram.core.ui.copyMediaToClipboard(context, file, mime)

    suspend fun saveToGallery(context: Context, file: File, mime: String, displayName: String): Uri? =
        withContext(Dispatchers.IO) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    saveViaMediaStore(context, file, mime, displayName)
                } else {
                    saveToLegacyFolder(context, file, mime, displayName)
                }
            }.getOrNull()
        }

    private fun saveViaMediaStore(context: Context, file: File, mime: String, displayName: String): Uri? {
        val isVideo = mime.startsWith("video/")
        val collection = if (isVideo) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val relativePath = if (isVideo) {
            "${Environment.DIRECTORY_MOVIES}/Monogram"
        } else {
            "${Environment.DIRECTORY_PICTURES}/Monogram"
        }
        val values = android.content.ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, shareFileName(displayName))
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(collection, values) ?: return null
        resolver.openOutputStream(uri)?.use { output ->
            file.inputStream().use { input -> input.copyTo(output) }
        } ?: run {
            resolver.delete(uri, null, null)
            return null
        }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    private fun saveToLegacyFolder(context: Context, file: File, mime: String, displayName: String): Uri? {
        val root = context.getExternalFilesDir(
            if (mime.startsWith("video/")) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES,
        ) ?: context.filesDir
        val destination = File(root, shareFileName(displayName)).apply { parentFile?.mkdirs() }
        file.copyTo(destination, overwrite = true)
        return contentUri(context, destination)
    }

    fun openExternally(context: Context, file: File, mime: String): Boolean = runCatching {
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(contentUri(context, file), mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .apply { if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        context.startActivity(view)
    }.isSuccess
}
