package org.monogram.network.http

import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * OpenStreetMap raster tile fetcher and cache.
 */
class MapTileStore(
    cacheRoot: File,
    private val httpClientFactory: () -> HttpClient,
    private val userAgent: String = USER_AGENT,
) {
    private val root = File(cacheRoot, "map-tiles")
    private val client by lazy { httpClientFactory() }
    private val gate = Semaphore(MAX_CONCURRENT)

    fun cached(z: Int, x: Int, y: Int): File? = tileFile(z, x, y).takeIf { it.isFile }

    suspend fun tile(z: Int, x: Int, y: Int): File? = withContext(Dispatchers.IO) {
        if (z !in 0..MAX_ZOOM) return@withContext null
        val file = tileFile(z, x, y)
        if (file.isFile && System.currentTimeMillis() - file.lastModified() < TTL_MS) {
            return@withContext file
        }
        gate.withPermit {
            runCatching {
                file.parentFile?.mkdirs()
                val partial = File(file.parentFile, "${file.name}.part")
                client.prepareGet("$TILE_ENDPOINT/$z/$x/$y.png").execute { response ->
                    if (!response.status.isSuccess()) error("HTTP ${response.status.value}")
                    val type = response.headers[HttpHeaders.ContentType].orEmpty()
                    if (!type.startsWith("image/")) error("unexpected content type $type")
                    val channel = response.bodyAsChannel()
                    val buffer = ByteArray(16 * 1024)
                    var total = 0
                    FileOutputStream(partial).use { output ->
                        while (true) {
                            val read = channel.readAvailable(buffer)
                            if (read == -1) break
                            if (read == 0) continue
                            total += read
                            if (total > MAX_TILE_BYTES) error("tile larger than expected")
                            output.write(buffer, 0, read)
                        }
                    }
                }
                if (partial.length() < MIN_TILE_BYTES) error("tile too small")
                if (!partial.renameTo(file)) error("cannot publish tile")
                file.setLastModified(System.currentTimeMillis())
                file
            }.getOrNull()
        }
    }

    private fun tileFile(z: Int, x: Int, y: Int) = File(root, "$z/$x/$y.png")

    companion object {
        const val TILE_ENDPOINT = "https://tile.openstreetmap.org"
        const val USER_AGENT = "Monogram/1.0 (Android; +https://github.com/monogram-android/monogram)"
        const val MAX_CONCURRENT = 2
        const val TTL_MS = 7L * 24 * 60 * 60 * 1000

        const val MAX_ZOOM = 19
        private const val MAX_TILE_BYTES = 512 * 1024
        private const val MIN_TILE_BYTES = 128
    }
}
