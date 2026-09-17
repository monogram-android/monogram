package org.monogram.feature.settings

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.monogram.core.common.Outcome
import org.monogram.core.models.Wallpaper
import org.monogram.core.models.WallpaperCatalog
import org.monogram.network.bridge.MtprotoClient

internal interface WallpaperSource {
    suspend fun cachedCatalog(): WallpaperCatalog?
    suspend fun refresh(hash: Long): Outcome<WallpaperCatalog>
    suspend fun preview(wallpaper: Wallpaper): Outcome<File>
}

internal class WallpaperRepository(private val directory: File, private val client: MtprotoClient) : WallpaperSource {
    override suspend fun cachedCatalog(): WallpaperCatalog? = withContext(Dispatchers.IO) {
        val file = File(directory, "catalog.json")
        if (!file.isFile || file.length() > 2 * 1024 * 1024) return@withContext null
        runCatching {
            val json = JSONObject(file.readText())
            val list = json.getJSONArray("wallpapers")
            WallpaperCatalog(json.getLong("hash"), false, List(list.length()) { decodeWallpaper(list.getJSONObject(it)) })
        }.getOrNull()
    }

    override suspend fun refresh(hash: Long): Outcome<WallpaperCatalog> {
        val result = client.getWallpapers(hash)
        if (result is Outcome.Ok && !result.value.notModified) withContext(Dispatchers.IO) {
            // A disk-cache failure must not discard a successfully fetched catalog.
            runCatching {
                directory.mkdirs()
                val json = JSONObject().put("hash", result.value.hash)
                    .put("wallpapers", JSONArray(result.value.wallpapers.map(::encodeWallpaper)))
                val temp = File(directory, "catalog.tmp")
                try {
                    temp.writeText(json.toString())
                    temp.renameTo(File(directory, "catalog.json"))
                } finally { temp.delete() }
            }
        }
        return result
    }

    override suspend fun preview(wallpaper: Wallpaper): Outcome<File> = withContext(Dispatchers.IO) {
        directory.mkdirs()
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(encodeWallpaper(wallpaper).toString().toByteArray()).joinToString("") { "%02x".format(it) }
        val image = File(directory, "$digest.jpg")
        if (image.isFile && image.length() > 0) return@withContext Outcome.Ok(image)
        val document = if (wallpaper.documentId != null) File(directory, "$digest.document") else null
        try {
            if (document != null && !document.isFile) {
                when (val result = client.downloadWallpaper(wallpaper, document.absolutePath)) {
                    is Outcome.Err -> return@withContext result
                    is Outcome.Ok -> Unit
                }
            }
            renderWallpaper(wallpaper, document, image)
            Outcome.Ok(image)
        } finally {
            document?.delete()
        }
    }
}

private fun encodeWallpaper(w: Wallpaper) = JSONObject()
    .put("id", w.id).put("hash", w.accessHash).put("slug", w.slug)
    .put("pattern", w.pattern).put("dark", w.dark).put("mime", w.mimeType)
    .put("document", w.documentId ?: JSONObject.NULL).put("colors", JSONArray(w.colors))
    .put("intensity", w.intensity ?: JSONObject.NULL).put("rotation", w.rotation)
    .put("blur", w.blur).put("motion", w.motion)

private fun decodeWallpaper(j: JSONObject): Wallpaper {
    val colors = j.getJSONArray("colors")
    return Wallpaper(j.getLong("id"), j.getLong("hash"), j.getString("slug"),
        j.getBoolean("pattern"), j.getBoolean("dark"), j.getString("mime"),
        if (j.isNull("document")) null else j.getLong("document"),
        List(colors.length()) { colors.getInt(it) },
        if (j.isNull("intensity")) null else j.getInt("intensity"),
        j.getInt("rotation"), j.getBoolean("blur"), j.getBoolean("motion"))
}
