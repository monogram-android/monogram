package org.monogram.presentation.core.util

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import org.monogram.domain.repository.MessageDisplayer
import org.monogram.presentation.R
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class DownloadUtils(
    private val context: Context,
    private val messageDisplayer: MessageDisplayer
) : IDownloadUtils {

    override fun saveFileToDownloads(filePath: String) {
        when (saveFileToDownloadsInternal(filePath)) {
            SaveResult.SUCCESS -> messageDisplayer.show(context.getString(R.string.download_saved_to_downloads))
            SaveResult.NOT_FOUND -> messageDisplayer.show(context.getString(R.string.download_file_not_found))
            SaveResult.FAILED -> messageDisplayer.show(context.getString(R.string.download_failed_to_save_file))
        }
    }

    override fun saveFilesToDownloads(filePaths: List<String>) {
        val paths = filePaths
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()

        if (paths.isEmpty()) {
            messageDisplayer.show(context.getString(R.string.download_no_files_to_save))
            return
        }

        var savedCount = 0
        var notFoundCount = 0
        var failedCount = 0

        paths.forEach { path ->
            when (saveFileToDownloadsInternal(path)) {
                SaveResult.SUCCESS -> savedCount++
                SaveResult.NOT_FOUND -> notFoundCount++
                SaveResult.FAILED -> failedCount++
            }
        }

        when {
            savedCount == 0 && notFoundCount > 0 && failedCount == 0 -> {
                messageDisplayer.show(context.getString(R.string.download_files_not_found))
            }

            savedCount == 0 -> {
                messageDisplayer.show(context.getString(R.string.download_failed_to_save_files))
            }

            notFoundCount == 0 && failedCount == 0 -> {
                if (savedCount == 1) {
                    messageDisplayer.show(context.getString(R.string.download_saved_to_downloads))
                } else {
                    messageDisplayer.show(
                        context.getString(
                            R.string.download_saved_files_to_downloads_format,
                            savedCount
                        )
                    )
                }
            }

            else -> {
                messageDisplayer.show(
                    context.getString(
                        R.string.download_saved_files_with_errors_format,
                        savedCount,
                        notFoundCount,
                        failedCount
                    )
                )
            }
        }
    }

    override fun saveBitmapToGallery(bitmap: Bitmap) {
        val filename = "IMG_${System.currentTimeMillis()}.jpg"

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        "${Environment.DIRECTORY_PICTURES}/MonoGram"
                    )
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }

                val resolver = context.contentResolver
                val uri = resolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    contentValues
                )

                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { outputStream ->
                        bitmap.compress(
                            Bitmap.CompressFormat.JPEG,
                            100,
                            outputStream
                        )
                    }

                    contentValues.clear()
                    contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, contentValues, null, null)

                    messageDisplayer.show("Screenshot saved to Pictures/MonoGram")
                }
            } else {
                val imagesDir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_PICTURES
                )
                val monoGramDir = File(imagesDir, "MonoGram")
                if (!monoGramDir.exists()) {
                    monoGramDir.mkdirs()
                }

                val imageFile = File(monoGramDir, filename)

                imageFile.outputStream().use { outputStream ->
                    bitmap.compress(
                        Bitmap.CompressFormat.JPEG,
                        100,
                        outputStream
                    )
                }

                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(imageFile.absolutePath),
                    null,
                    null
                )

                messageDisplayer.show("Screenshot saved to Pictures/MonoGram")
            }
        } catch (e: Exception) {
            messageDisplayer.show("Failed to save screenshot: ${e.message}")
        }
    }

    override fun copyBitmapToClipboard(bitmap: Bitmap) {
        try {
            val cachePath = File(context.cacheDir, "images")
            cachePath.mkdirs()

            val file = File(cachePath, "screenshot_temp.png")
            FileOutputStream(file).use { stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            }

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                file
            )

            val clipboard =
                context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

            val clip = ClipData.newUri(
                context.contentResolver,
                "Screenshot",
                uri
            )

            clipboard.setPrimaryClip(clip)

            messageDisplayer.show("Screenshot copied to clipboard")

        } catch (e: Exception) {
            messageDisplayer.show("Failed to copy screenshot: ${e.message}")
        }
    }

    override fun openFile(filePath: String) {
        try {
            val file = File(filePath)
            if (!file.exists()) {
                messageDisplayer.show("File not found")
                return
            }

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                file
            )

            val extension = file.extension.lowercase()
            val mimeType = if (extension == "apk") {
                "application/vnd.android.package-archive"
            } else {
                MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(extension)
            }

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (context !is Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }

            if (extension == "apk") {
                context.startActivity(intent)
            } else {
                context.startActivity(
                    Intent.createChooser(intent, "Open with")
                )
            }

        } catch (e: Exception) {
            messageDisplayer.show("Failed to open: ${e.message}")
        }
    }

    override fun copyImageToClipboard(filePath: String) {
        try {
            val file = File(filePath)
            if (!file.exists()) {
                messageDisplayer.show("File not found")
                return
            }

            val clipboard =
                context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                file
            )

            val clip = ClipData.newUri(
                context.contentResolver,
                "Image",
                uri
            )

            clipboard.setPrimaryClip(clip)

            messageDisplayer.show("Image copied to clipboard")

        } catch (e: Exception) {
            messageDisplayer.show("Failed to copy: ${e.message}")
        }
    }

    private fun saveFileToDownloadsInternal(filePath: String): SaveResult {
        return try {
            val file = File(filePath)
            if (!file.exists()) return SaveResult.NOT_FOUND

            val fileName = file.name
            val mimeType = getMimeType(filePath)
            val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/MonoGram"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }

                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    ?: return SaveResult.FAILED

                val isWritten = resolver.openOutputStream(uri)?.use { outputStream ->
                    FileInputStream(file).use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                    true
                } ?: false

                if (!isWritten) {
                    resolver.delete(uri, null, null)
                    return SaveResult.FAILED
                }

                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)

                SaveResult.SUCCESS
            } else {
                val downloadsDir =
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val monoGramDir = File(downloadsDir, "MonoGram")
                if (!monoGramDir.exists() && !monoGramDir.mkdirs()) {
                    return SaveResult.FAILED
                }

                val destinationFile = resolveUniqueDestinationFile(monoGramDir, fileName)
                FileInputStream(file).use { inputStream ->
                    destinationFile.outputStream().use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }

                SaveResult.SUCCESS
            }
        } catch (_: Exception) {
            SaveResult.FAILED
        }
    }

    private fun resolveUniqueDestinationFile(directory: File, fileName: String): File {
        val dotIndex = fileName.lastIndexOf('.')
        val baseName = if (dotIndex > 0) fileName.substring(0, dotIndex) else fileName
        val extension = if (dotIndex > 0) fileName.substring(dotIndex) else ""

        var candidate = File(directory, fileName)
        var index = 1
        while (candidate.exists()) {
            candidate = File(directory, "$baseName ($index)$extension")
            index++
        }
        return candidate
    }

    private enum class SaveResult {
        SUCCESS,
        NOT_FOUND,
        FAILED
    }

    override fun isWifiConnected(): Boolean {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val network = connectivityManager.activeNetwork ?: return false
        val capabilities =
            connectivityManager.getNetworkCapabilities(network) ?: return false

        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    override fun isMobileDataConnected(): Boolean {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val network = connectivityManager.activeNetwork ?: return false
        val capabilities =
            connectivityManager.getNetworkCapabilities(network) ?: return false

        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    override fun isRoaming(): Boolean {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val network = connectivityManager.activeNetwork ?: return false
        val capabilities =
            connectivityManager.getNetworkCapabilities(network) ?: return false

        return !capabilities.hasCapability(
            NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING
        )
    }
}

fun getMimeType(filePath: String): String? {
    val file = File(filePath)
    val extension = MimeTypeMap.getFileExtensionFromUrl(filePath)
        .ifBlank { file.extension }
    return MimeTypeMap.getSingleton()
        .getMimeTypeFromExtension(extension.lowercase())
}