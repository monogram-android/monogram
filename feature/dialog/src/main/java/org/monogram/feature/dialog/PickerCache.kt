package org.monogram.feature.dialog

import kotlinx.coroutines.runBlocking
import org.monogram.core.database.SessionMetadataStore
import org.monogram.core.models.CompactJson
import org.monogram.core.models.SavedGif
import org.monogram.core.models.StickerPack

internal data class StickerCatalogSnapshot(
    val hash: Long,
    val sets: List<StickerPack>,
)

internal object StickerCatalogMemory {
    @Volatile private var stickers: StickerCatalogSnapshot? = null
    @Volatile private var emojis: StickerCatalogSnapshot? = null
    @Volatile private var stickersLive = false
    @Volatile private var emojisLive = false

    @Synchronized
    fun get(emoji: Boolean): StickerCatalogSnapshot? = if (emoji) emojis else stickers

    @Synchronized
    fun isLive(emoji: Boolean): Boolean = if (emoji) emojisLive else stickersLive

    @Synchronized
    fun markLive(emoji: Boolean) {
        if (emoji) emojisLive = true else stickersLive = true
    }

    @Synchronized
    fun put(emoji: Boolean, snapshot: StickerCatalogSnapshot) {
        if (emoji) emojis = snapshot else stickers = snapshot
    }

    @Synchronized
    fun clear() {
        stickers = null
        emojis = null
        stickersLive = false
        emojisLive = false
    }
}

internal object StickerPackMemory {
    private val packs = LinkedHashMap<Long, StickerPack>()

    @Synchronized
    fun get(id: Long): StickerPack? = packs[id]

    @Synchronized
    fun put(pack: StickerPack) {
        packs[pack.id] = pack
    }

    @Synchronized
    fun snapshot(): Map<Long, StickerPack> = packs.toMap()

    @Synchronized
    fun clear() {
        packs.clear()
    }
}

internal object SavedGifMemory {
    @Volatile private var gifs: List<SavedGif>? = null
    @Volatile private var live = false

    @Synchronized
    fun get(): List<SavedGif>? = gifs

    @Synchronized
    fun isLive(): Boolean = live

    @Synchronized
    fun markLive() {
        live = true
    }

    @Synchronized
    fun put(value: List<SavedGif>) {
        gifs = value
    }

    @Synchronized
    fun clear() {
        gifs = null
        live = false
    }
}

internal object PickerDisk {
    private const val KEY_STICKERS = "picker.stickers"
    private const val KEY_EMOJIS = "picker.emojis"
    private const val KEY_GIFS = "picker.gifs"
    private const val KEY_PACK_PREFIX = "picker.pack."

    suspend fun readCatalog(
        store: SessionMetadataStore,
        emoji: Boolean,
    ): StickerCatalogSnapshot? {
        val raw = store.readMeta(if (emoji) KEY_EMOJIS else KEY_STICKERS) ?: return null
        return runCatching { decodeCatalog(raw) }.getOrNull()
    }

    suspend fun writeCatalog(
        store: SessionMetadataStore,
        emoji: Boolean,
        snapshot: StickerCatalogSnapshot,
    ) {
        store.writeMeta(if (emoji) KEY_EMOJIS else KEY_STICKERS, encodeCatalog(snapshot))
    }

    suspend fun readPack(store: SessionMetadataStore, id: Long): StickerPack? {
        val raw = store.readMeta(KEY_PACK_PREFIX + id) ?: return null
        return runCatching {
            (CompactJson.parse(raw) as? Map<*, *>)?.toPack()
        }.getOrNull()
    }

    suspend fun writePack(store: SessionMetadataStore, pack: StickerPack) {
        store.writeMeta(KEY_PACK_PREFIX + pack.id, encodePack(pack))
    }

    suspend fun readGifs(store: SessionMetadataStore): List<SavedGif>? {
        val raw = store.readMeta(KEY_GIFS) ?: return null
        return runCatching { decodeGifs(raw) }.getOrNull()
    }

    suspend fun writeGifs(store: SessionMetadataStore, gifs: List<SavedGif>) {
        store.writeMeta(KEY_GIFS, encodeGifs(gifs))
    }

    internal fun encodeCatalog(snapshot: StickerCatalogSnapshot): String {
        val sets = snapshot.sets.joinToString(",") { encodePack(it) }
        return "{" + "\"hash\":" + snapshot.hash + ",\"sets\":[" + sets + "]}"
    }

    internal fun decodeCatalog(raw: String): StickerCatalogSnapshot {
        val json = CompactJson.parse(raw) as? Map<*, *> ?: error("catalog")
        val sets = json["sets"] as? List<*> ?: emptyList<Any?>()
        return StickerCatalogSnapshot(
            hash = json.long("hash"),
            sets = sets.mapNotNull { (it as? Map<*, *>)?.toPack() },
        )
    }

    internal fun encodeGifs(gifs: List<SavedGif>): String {
        val rows = gifs.joinToString(",") { gif ->
            val thumb = gif.thumbCacheKey?.let { ",\"thumbCacheKey\":" + it.jsonString() } ?: ""
            "{" + "\"documentId\":" + gif.documentId +
                ",\"cacheKey\":" + gif.cacheKey.jsonString() + thumb + "}"
        }
        return "{" + "\"gifs\":[" + rows + "]}"
    }

    internal fun decodeGifs(raw: String): List<SavedGif> {
        val json = CompactJson.parse(raw) as? Map<*, *> ?: return emptyList()
        val rows = json["gifs"] as? List<*> ?: return emptyList()
        return rows.mapNotNull { row ->
            val map = row as? Map<*, *> ?: return@mapNotNull null
            SavedGif(
                documentId = map.long("documentId"),
                cacheKey = map.string("cacheKey"),
                thumbCacheKey = map.string("thumbCacheKey").takeIf { it.isNotBlank() },
            )
        }
    }

    private fun encodePack(pack: StickerPack): String {
        val ids = pack.previewDocumentIds.joinToString(",")
        return "{" +
            "\"id\":" + pack.id +
            ",\"title\":" + pack.title.jsonString() +
            ",\"shortName\":" + pack.shortName.jsonString() +
            ",\"count\":" + pack.count +
            ",\"isEmoji\":" + pack.isEmoji +
            ",\"accessHash\":" + pack.accessHash +
            ",\"previewDocumentIds\":[" + ids + "]}"
    }

    private fun Map<*, *>.toPack(): StickerPack {
        val ids = this["previewDocumentIds"] as? List<*> ?: emptyList<Any?>()
        return StickerPack(
            id = long("id"),
            title = string("title"),
            shortName = string("shortName"),
            count = long("count").toInt(),
            isEmoji = this["isEmoji"] as? Boolean ?: false,
            previewDocumentIds = ids.mapNotNull { (it as? Number)?.toLong() },
            accessHash = long("accessHash"),
        )
    }

    private fun Map<*, *>.long(key: String): Long =
        (this[key] as? Number)?.toLong() ?: 0L

    private fun Map<*, *>.string(key: String): String =
        this[key] as? String ?: ""

    private fun String.jsonString(): String =
        "\"" + CompactJson.escape(this) + "\""
}

/** Fill process memory from disk so the first picker frame can reuse a snapshot. */
internal fun warmPickerMemory(sessionStore: SessionMetadataStore?) {
    if (sessionStore == null) return
    if (
        StickerCatalogMemory.get(false) != null &&
        StickerCatalogMemory.get(true) != null &&
        SavedGifMemory.get() != null
    ) {
        return
    }
    runBlocking {
        if (StickerCatalogMemory.get(false) == null) {
            PickerDisk.readCatalog(sessionStore, false)?.let { StickerCatalogMemory.put(false, it) }
        }
        if (StickerCatalogMemory.get(true) == null) {
            PickerDisk.readCatalog(sessionStore, true)?.let { StickerCatalogMemory.put(true, it) }
        }
        if (SavedGifMemory.get() == null) {
            PickerDisk.readGifs(sessionStore)?.let { SavedGifMemory.put(it) }
        }
    }
}

