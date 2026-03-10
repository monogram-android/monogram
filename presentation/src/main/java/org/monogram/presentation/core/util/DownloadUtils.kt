package org.monogram.presentation.core.util

import android.app.Activity
import android.content.*
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
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class DownloadUtils(
    private val context: Context,
    private val messageDisplayer: MessageDisplayer
) : IDownloadUtils {

    override fun saveFileToDownloads(filePath: String) {
        try {
            val file = File(filePath)
            if (!file.exists()) {
                messageDisplayer.show("File not found")
                return
            }

            val fileName = file.name
            val mimeType = getMimeType(filePath)
            val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/MonoGram"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                }

                val resolver = context.contentResolver
                val uri = resolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    contentValues
                )

                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { outputStream ->
                        FileInputStream(file).use { inputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                    messageDisplayer.show("Saved to Downloads/MonoGram")
                } else {
                    messageDisplayer.show("Failed to create file in Downloads")
                }
            } else {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS
                )
                val monoGramDir = File(downloadsDir, "MonoGram")
                if (!monoGramDir.exists()) {
                    monoGramDir.mkdirs()
                }

                val destinationFile = File(monoGramDir, fileName)

                FileInputStream(file).use { inputStream ->
                    destinationFile.outputStream().use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }

                messageDisplayer.show("Saved to Downloads/MonoGram")
            }
        } catch (e: Exception) {
            messageDisplayer.show("Failed to save: ${e.message}")
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