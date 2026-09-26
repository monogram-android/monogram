package org.monogram.feature.settings

import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

internal suspend fun readWallpaperPattern(input: InputStream, limit: Int = 4 * 1024 * 1024): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (true) {
        coroutineContext.ensureActive()
        val count = input.read(buffer)
        if (count < 0) return output.toByteArray()
        require(output.size().toLong() + count <= limit) { "Wallpaper pattern too large" }
        output.write(buffer, 0, count)
    }
}
