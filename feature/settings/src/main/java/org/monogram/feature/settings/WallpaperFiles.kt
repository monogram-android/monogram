package org.monogram.feature.settings

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

internal suspend fun importWallpaper(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
    val directory = File(context.filesDir, "wallpapers").apply { mkdirs() }
    val inputFile = File.createTempFile("import-", ".tmp", context.cacheDir)
    try {
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input)
            inputFile.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    coroutineContext.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= 20L * 1024 * 1024)
                    output.write(buffer, 0, count)
                }
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(inputFile.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0)
        val sample = wallpaperSampleSize(bounds.outWidth, bounds.outHeight)
        val bitmap = requireNotNull(BitmapFactory.decodeFile(inputFile.path,
            BitmapFactory.Options().apply { inSampleSize = sample }))
        val destination = File.createTempFile("wallpaper-", ".jpg", directory)
        try {
            destination.outputStream().use { require(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it)) }
            destination.absolutePath
        } catch (error: Exception) {
            destination.delete()
            throw error
        } finally { bitmap.recycle() }
    } finally { inputFile.delete() }
}

internal fun wallpaperSampleSize(width: Int, height: Int): Int {
    require(width > 0 && height > 0)
    var sample = 1
    while (maxOf(width, height) / sample > 2560) sample *= 2
    return sample
}
