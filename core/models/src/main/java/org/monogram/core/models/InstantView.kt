package org.monogram.core.models

/** Native media index uses `(id, INSTANT_VIEW_MEDIA_MSG_ID)` for page photos/documents. */
const val INSTANT_VIEW_MEDIA_MSG_ID = -1

/**
 * Instant View client contract from official TL schema.
 *
 * Eligibility: `webPage.cached_page` offers Instant View except documented
 * exceptions (`telegram_album`, `telegram_message`). Fetch full pages with
 * `messages.getWebPage` using `webPage.hash` (or 0). `page.part` means the
 * cached preview is incomplete. `page.rtl` drives layout direction.
 *
 * PageBlock kinds rendered: unsupported, title, subtitle, authorDate, header,
 * subheader, paragraph, preformatted, footer, divider, anchor, list,
 * blockquote, pullquote, photo, video, cover, embed, embedPost, collage,
 * slideshow, channel, audio, kicker, table, orderedList, details,
 * relatedArticles, map, plus layer-229 extras (heading1-6, math, thinking,
 * document, buttons).
 */
data class InstantViewMediaRef(
    val id: Long,
    val messageId: Int,
    val cacheKey: String,
)

data class InstantViewPage(
    val url: String,
    val displayUrl: String,
    val title: String? = null,
    val siteName: String? = null,
    val description: String? = null,
    val webpageType: String? = null,
    val hash: Int = 0,
    val hasInstantView: Boolean = false,
    val part: Boolean = false,
    val rtl: Boolean = false,
    val v2: Boolean = false,
    val notModified: Boolean = false,
    val blocks: List<InstantViewBlock> = emptyList(),
)

sealed class InstantViewBlock {
    data class Text(
        val kind: String,
        val text: String,
        val entities: List<TextEntity> = emptyList(),
        val level: Int = 0,
        val language: String? = null,
        val publishedDate: Int? = null,
    ) : InstantViewBlock()

    data class Quote(
        val text: String,
        val entities: List<TextEntity> = emptyList(),
        val caption: InstantViewRichText? = null,
        val pull: Boolean = false,
        val blocks: List<InstantViewBlock> = emptyList(),
    ) : InstantViewBlock()

    data class ListBlock(
        val ordered: Boolean,
        val items: List<InstantViewListItem> = emptyList(),
    ) : InstantViewBlock()

    data class Table(
        val bordered: Boolean,
        val striped: Boolean,
        val title: InstantViewRichText? = null,
        val rows: List<List<InstantViewTableCell>> = emptyList(),
    ) : InstantViewBlock()

    data class Details(
        val open: Boolean,
        val title: InstantViewRichText? = null,
        val blocks: List<InstantViewBlock> = emptyList(),
    ) : InstantViewBlock()

    data class Photo(
        val id: Long,
        val cacheKey: String,
        val width: Int = 0,
        val height: Int = 0,
        val url: String? = null,
        val caption: InstantViewCaption? = null,
    ) : InstantViewBlock()

    data class Document(
        val kind: String,
        val id: Long,
        val cacheKey: String,
        val width: Int = 0,
        val height: Int = 0,
        val autoplay: Boolean = false,
        val loop: Boolean = false,
        val fileName: String? = null,
        val mimeType: String? = null,
        val fileSize: Long? = null,
        val title: String? = null,
        val performer: String? = null,
        val durationSeconds: Int? = null,
        val caption: InstantViewCaption? = null,
    ) : InstantViewBlock()

    data class Cover(val block: InstantViewBlock) : InstantViewBlock()

    data class Embed(
        val url: String? = null,
        val html: String? = null,
        val width: Int? = null,
        val height: Int? = null,
        val fullWidth: Boolean = false,
        val allowScrolling: Boolean = false,
        val posterCacheKey: String? = null,
        val caption: InstantViewCaption? = null,
    ) : InstantViewBlock()

    data class EmbedPost(
        val url: String,
        val author: String,
        val date: Int,
        val photoCacheKey: String? = null,
        val blocks: List<InstantViewBlock> = emptyList(),
        val caption: InstantViewCaption? = null,
    ) : InstantViewBlock()

    data class MediaGroup(
        val kind: String,
        val items: List<InstantViewBlock> = emptyList(),
        val caption: InstantViewCaption? = null,
    ) : InstantViewBlock()

    data class Channel(
        val peerId: Long,
        val title: String,
        val username: String? = null,
        val photoCacheKey: String? = null,
    ) : InstantViewBlock()

    data class Related(
        val title: InstantViewRichText? = null,
        val articles: List<InstantViewRelatedArticle> = emptyList(),
    ) : InstantViewBlock()

    data class Map(
        val latitude: Double,
        val longitude: Double,
        val zoom: Int,
        val width: Int,
        val height: Int,
        val cacheKey: String? = null,
        val caption: InstantViewCaption? = null,
    ) : InstantViewBlock()

    data class Math(val source: String) : InstantViewBlock()

    data class Anchor(val name: String) : InstantViewBlock()

    data object Divider : InstantViewBlock()

    data class Buttons(val items: List<InstantViewRichText> = emptyList()) : InstantViewBlock()

    data object Unsupported : InstantViewBlock()
}

data class InstantViewRichText(
    val text: String,
    val entities: List<TextEntity> = emptyList(),
)

data class InstantViewCaption(
    val text: String,
    val entities: List<TextEntity> = emptyList(),
    val credit: InstantViewRichText? = null,
)

data class InstantViewListItem(
    val text: String = "",
    val entities: List<TextEntity> = emptyList(),
    val blocks: List<InstantViewBlock> = emptyList(),
    val number: String? = null,
    val checkbox: Boolean = false,
    val checked: Boolean = false,
)

data class InstantViewTableCell(
    val text: String,
    val entities: List<TextEntity> = emptyList(),
    val header: Boolean = false,
    val colspan: Int = 1,
    val rowspan: Int = 1,
)

data class InstantViewPlacedCell(
    val cell: InstantViewTableCell,
    val row: Int,
    val col: Int,
)

data class InstantViewTableLayout(
    val rowCount: Int,
    val columnCount: Int,
    val cells: List<InstantViewPlacedCell>,
)

data class InstantViewRelatedArticle(
    val url: String,
    val title: String? = null,
    val description: String? = null,
    val author: String? = null,
    val publishedDate: Int? = null,
    val photoCacheKey: String? = null,
)

sealed class InstantViewLink {
    data class Anchor(val name: String) : InstantViewLink()
    data class Page(val url: String, val anchor: String? = null) : InstantViewLink()
    data class External(val url: String) : InstantViewLink()
}

sealed class InstantViewFetchDecision {
    data object KeepExisting : InstantViewFetchDecision()
    data object Unavailable : InstantViewFetchDecision()
    data class RefetchFull(val url: String) : InstantViewFetchDecision()
    data class Show(val page: InstantViewPage) : InstantViewFetchDecision()
}

object InstantViewPages {
    /** Webpage types that carry `cached_page` but must not show an Instant View button. */
    private val noInstantViewTypes = setOf("telegram_album", "telegram_message")

    fun offersInstantView(type: String?, hasCachedPage: Boolean): Boolean {
        if (!hasCachedPage) return false
        return type !in noInstantViewTypes
    }

    fun shouldShowPageTitle(page: InstantViewPage): Boolean {
        val title = page.title?.trim().orEmpty()
        if (title.isBlank()) return false
        return !containsTitle(page.blocks, title)
    }

    fun parseMediaKey(cacheKey: String): InstantViewMediaRef? {
        val trimmed = cacheKey.trim()
        if (trimmed.isEmpty()) return null
        val kind = trimmed.substringBefore(':')
        val rest = trimmed.substringAfter(':', missingDelimiterValue = "")
        if (rest.isEmpty()) return null
        val id = rest.substringBefore(':').toLongOrNull() ?: return null
        val messageId = if (kind == "avatar") 0 else INSTANT_VIEW_MEDIA_MSG_ID
        return InstantViewMediaRef(id = id, messageId = messageId, cacheKey = trimmed)
    }

    fun blockStableKey(index: Int, block: InstantViewBlock): String = when (block) {
        is InstantViewBlock.Photo -> "photo:${block.id}"
        is InstantViewBlock.Document -> "${block.kind}:${block.id}"
        is InstantViewBlock.Anchor -> "anchor:${block.name}"
        is InstantViewBlock.Channel -> "channel:${block.peerId}"
        is InstantViewBlock.Map ->
            "map:${block.cacheKey ?: "${block.latitude},${block.longitude}"}"
        is InstantViewBlock.Cover -> "cover:${blockStableKey(index, block.block)}"
        is InstantViewBlock.EmbedPost -> "embedPost:${block.url}"
        else -> "$index:${block::class.simpleName}"
    }

    fun decideFetch(page: InstantViewPage, alreadyRefetchedPartial: Boolean): InstantViewFetchDecision {
        if (page.notModified) return InstantViewFetchDecision.KeepExisting
        if (!page.hasInstantView) return InstantViewFetchDecision.Unavailable
        if (page.part && !alreadyRefetchedPartial && page.url.isNotBlank()) {
            return InstantViewFetchDecision.RefetchFull(page.url)
        }
        return InstantViewFetchDecision.Show(page)
    }

    fun resolveLink(currentUrl: String, pageUrl: String?, href: String): InstantViewLink {
        val trimmed = href.trim()
        if (trimmed.isEmpty()) return InstantViewLink.External(href)
        if (trimmed.startsWith("#")) {
            return InstantViewLink.Anchor(decodeFragment(trimmed.drop(1)))
        }
        val hashAt = trimmed.lastIndexOf('#')
        val base = if (hashAt >= 0) trimmed.take(hashAt) else trimmed
        val fragment = if (hashAt >= 0) decodeFragment(trimmed.substring(hashAt + 1)) else null
        val current = (pageUrl?.takeIf { it.isNotBlank() } ?: currentUrl).lowercase()
        val baseLower = base.lowercase()
        val samePage = fragment != null && (
            base.isEmpty() ||
            current.contains(baseLower) ||
            baseLower.contains(current) ||
            current.substringBefore('#') == baseLower
        )
        if (samePage) return InstantViewLink.Anchor(fragment!!)
        if (
            trimmed.startsWith("mailto:", ignoreCase = true) ||
            trimmed.startsWith("tel:", ignoreCase = true) ||
            trimmed.startsWith("tg:", ignoreCase = true) ||
            trimmed.startsWith("geo:", ignoreCase = true)
        ) {
            return InstantViewLink.External(trimmed)
        }
        return InstantViewLink.Page(if (base.isNotEmpty()) base else trimmed, fragment)
    }

    fun findAnchorIndex(blocks: List<InstantViewBlock>, name: String): Int? {
        val want = name.trim()
        if (want.isEmpty()) return null
        return blocks.indices.firstOrNull { index -> containsAnchor(blocks[index], want) }
    }

    fun placeTable(rows: List<List<InstantViewTableCell>>): InstantViewTableLayout {
        val occupied = mutableListOf<MutableList<Boolean>>()
        val cells = mutableListOf<InstantViewPlacedCell>()
        fun ensure(row: Int, col: Int) {
            while (occupied.size <= row) occupied.add(mutableListOf())
            while (occupied[row].size <= col) occupied[row].add(false)
        }
        fun isFree(row: Int, col: Int): Boolean {
            ensure(row, col)
            return !occupied[row][col]
        }
        rows.forEachIndexed { rowIndex, row ->
            var col = 0
            row.forEach { cell ->
                val colspan = cell.colspan.coerceAtLeast(1)
                val rowspan = cell.rowspan.coerceAtLeast(1)
                while (!isFree(rowIndex, col)) col++
                for (rowOffset in 0 until rowspan) {
                    for (colOffset in 0 until colspan) {
                        ensure(rowIndex + rowOffset, col + colOffset)
                        occupied[rowIndex + rowOffset][col + colOffset] = true
                    }
                }
                cells += InstantViewPlacedCell(cell, rowIndex, col)
                col += colspan
            }
        }
        return InstantViewTableLayout(
            rowCount = occupied.size,
            columnCount = occupied.maxOfOrNull { it.size } ?: 0,
            cells = cells,
        )
    }

    private fun containsTitle(blocks: List<InstantViewBlock>, title: String): Boolean =
        blocks.any { blockHasTitle(it, title) }

    private fun blockHasTitle(block: InstantViewBlock, title: String): Boolean = when (block) {
        is InstantViewBlock.Text ->
            (block.kind == "title" || (block.kind == "heading" && block.level <= 1)) &&
                block.text.trim().equals(title, ignoreCase = true)
        is InstantViewBlock.Cover -> blockHasTitle(block.block, title)
        else -> false
    }

    private fun containsAnchor(block: InstantViewBlock, name: String): Boolean = when (block) {
        is InstantViewBlock.Anchor -> block.name.equals(name, ignoreCase = true)
        is InstantViewBlock.Text -> entitiesHaveAnchor(block.entities, name)
        is InstantViewBlock.Quote ->
            entitiesHaveAnchor(block.entities, name) || containsAnchorName(block.blocks, name)
        is InstantViewBlock.ListBlock -> block.items.any {
            entitiesHaveAnchor(it.entities, name) || containsAnchorName(it.blocks, name)
        }
        is InstantViewBlock.Table -> block.rows.any { row ->
            row.any { entitiesHaveAnchor(it.entities, name) }
        }
        is InstantViewBlock.Cover -> containsAnchor(block.block, name)
        is InstantViewBlock.Details ->
            entitiesHaveAnchor(block.title?.entities.orEmpty(), name) || containsAnchorName(block.blocks, name)
        is InstantViewBlock.EmbedPost -> containsAnchorName(block.blocks, name)
        is InstantViewBlock.Buttons -> block.items.any { entitiesHaveAnchor(it.entities, name) }
        else -> false
    }

    private fun containsAnchorName(blocks: List<InstantViewBlock>, name: String): Boolean =
        blocks.any { containsAnchor(it, name) }

    private fun entitiesHaveAnchor(entities: List<TextEntity>, name: String): Boolean =
        entities.any { entity ->
            entity.kind.equals("anchor", ignoreCase = true) &&
                entity.url.orEmpty().removePrefix("#").equals(name, ignoreCase = true)
        }

    private fun decodeFragment(raw: String): String =
        runCatching { java.net.URLDecoder.decode(raw, Charsets.UTF_8.name()) }.getOrDefault(raw)

    fun formatFileSize(bytes: Long?): String? {
        val size = bytes ?: return null
        if (size <= 0) return null
        if (size < 1024) return "$size B"
        if (size < 1024 * 1024) return "${size / 1024} KB"
        return "${size / (1024 * 1024)} MB"
    }

    fun formatPublishedDate(epochSeconds: Int?): String? {
        val epoch = epochSeconds ?: return null
        if (epoch <= 0) return null
        val instant = java.time.Instant.ofEpochSecond(epoch.toLong())
        return java.time.format.DateTimeFormatter.ISO_LOCAL_DATE
            .withZone(java.time.ZoneOffset.UTC)
            .format(instant)
    }

    fun relatedSubtitle(article: InstantViewRelatedArticle): String =
        listOfNotNull(
            article.author?.takeIf { it.isNotBlank() },
            formatPublishedDate(article.publishedDate),
            article.description?.takeIf { it.isNotBlank() },
        ).joinToString(" · ")

    fun parse(dto: InstantViewDtoLike): InstantViewPage = InstantViewPage(
        url = dto.url,
        displayUrl = dto.displayUrl,
        title = dto.title,
        siteName = dto.siteName,
        description = dto.description,
        webpageType = dto.webpageType,
        hash = dto.hash,
        hasInstantView = dto.hasInstantView,
        part = dto.part,
        rtl = dto.rtl,
        v2 = dto.v2,
        notModified = dto.notModified,
        blocks = parseBlocks(CompactJson.parse(dto.blocksJson)),
    )

    fun parseBlocksJson(raw: String?): List<InstantViewBlock> =
        parseBlocks(CompactJson.parse(raw.orEmpty()))

    private fun parseBlocks(raw: Any?): List<InstantViewBlock> {
        val list = raw as? List<*> ?: return emptyList()
        return list.mapNotNull { parseBlock(it as? Map<*, *>) }
    }

    private fun parseBlock(map: Map<*, *>?): InstantViewBlock? {
        if (map == null) return InstantViewBlock.Unsupported
        return when (map.str("k")) {
            "title", "subtitle", "kicker", "paragraph", "footer", "pre", "heading", "authorDate", "thinking" ->
                InstantViewBlock.Text(
                    kind = map.str("k") ?: "paragraph",
                    text = map.str("t").orEmpty(),
                    entities = parseEntities(map["e"]),
                    level = map.int("l"),
                    language = map.str("lang"),
                    publishedDate = map.intOrNull("d"),
                )
            "quote" -> InstantViewBlock.Quote(
                text = map.str("t").orEmpty(),
                entities = parseEntities(map["e"]),
                caption = parseRich(map["caption"] as? Map<*, *>),
                pull = map.bool("pull"),
                blocks = parseBlocks(map["blocks"]),
            )
            "list" -> InstantViewBlock.ListBlock(
                ordered = map.bool("ordered"),
                items = (map["items"] as? List<*>).orEmpty().mapNotNull { parseListItem(it as? Map<*, *>) },
            )
            "table" -> InstantViewBlock.Table(
                bordered = map.bool("bordered"),
                striped = map.bool("striped"),
                title = parseRich(map["title"] as? Map<*, *>),
                rows = (map["rows"] as? List<*>).orEmpty().map { row ->
                    (row as? List<*>).orEmpty().mapNotNull { parseCell(it as? Map<*, *>) }
                },
            )
            "details" -> InstantViewBlock.Details(
                open = map.bool("open"),
                title = parseRich(map["title"] as? Map<*, *>),
                blocks = parseBlocks(map["blocks"]),
            )
            "photo" -> InstantViewBlock.Photo(
                id = map.long("id"),
                cacheKey = map.str("cache") ?: "photo:${map.long("id")}",
                width = map.int("w"),
                height = map.int("h"),
                url = map.str("url"),
                caption = parseCaption(map["caption"] as? Map<*, *>),
            )
            "video", "audio", "document" -> InstantViewBlock.Document(
                kind = map.str("k") ?: "document",
                id = map.long("id"),
                cacheKey = map.str("cache") ?: "doc:${map.long("id")}",
                width = map.int("w"),
                height = map.int("h"),
                autoplay = map.bool("autoplay"),
                loop = map.bool("loop"),
                fileName = map.str("name"),
                mimeType = map.str("mime"),
                fileSize = map.longOrNull("size"),
                title = map.str("title"),
                performer = map.str("performer"),
                durationSeconds = map.intOrNull("duration"),
                caption = parseCaption(map["caption"] as? Map<*, *>),
            )
            "cover" -> parseBlock(map["block"] as? Map<*, *>)?.let(InstantViewBlock::Cover)
                ?: InstantViewBlock.Unsupported
            "embed" -> InstantViewBlock.Embed(
                url = map.str("url"),
                html = map.str("html"),
                width = map.intOrNull("w"),
                height = map.intOrNull("h"),
                fullWidth = map.bool("full"),
                allowScrolling = map.bool("scroll"),
                posterCacheKey = map.str("poster"),
                caption = parseCaption(map["caption"] as? Map<*, *>),
            )
            "embedPost" -> InstantViewBlock.EmbedPost(
                url = map.str("url").orEmpty(),
                author = map.str("author").orEmpty(),
                date = map.int("date"),
                photoCacheKey = map.str("photo"),
                blocks = parseBlocks(map["blocks"]),
                caption = parseCaption(map["caption"] as? Map<*, *>),
            )
            "collage", "slideshow" -> InstantViewBlock.MediaGroup(
                kind = map.str("k") ?: "collage",
                items = parseBlocks(map["items"]),
                caption = parseCaption(map["caption"] as? Map<*, *>),
            )
            "channel" -> InstantViewBlock.Channel(
                peerId = map.long("id"),
                title = map.str("title").orEmpty(),
                username = map.str("username"),
                photoCacheKey = map.str("photo"),
            )
            "related" -> InstantViewBlock.Related(
                title = parseRich(map["title"] as? Map<*, *>),
                articles = (map["articles"] as? List<*>).orEmpty().mapNotNull { parseRelated(it as? Map<*, *>) },
            )
            "map" -> InstantViewBlock.Map(
                latitude = map.double("lat"),
                longitude = map.double("lng"),
                zoom = map.int("zoom"),
                width = map.int("w"),
                height = map.int("h"),
                cacheKey = map.str("cache"),
                caption = parseCaption(map["caption"] as? Map<*, *>),
            )
            "math" -> InstantViewBlock.Math(map.str("src").orEmpty())
            "anchor" -> InstantViewBlock.Anchor(map.str("n").orEmpty())
            "divider" -> InstantViewBlock.Divider
            "buttons" -> InstantViewBlock.Buttons(
                items = (map["items"] as? List<*>).orEmpty().mapNotNull { parseRich(it as? Map<*, *>) },
            )
            else -> InstantViewBlock.Unsupported
        }
    }

    private fun parseListItem(map: Map<*, *>?): InstantViewListItem? {
        map ?: return null
        return InstantViewListItem(
            text = map.str("t").orEmpty(),
            entities = parseEntities(map["e"]),
            blocks = parseBlocks(map["blocks"]),
            number = map.str("num"),
            checkbox = map.bool("checkbox"),
            checked = map.bool("checked"),
        )
    }

    private fun parseCell(map: Map<*, *>?): InstantViewTableCell? {
        map ?: return null
        return InstantViewTableCell(
            text = map.str("t").orEmpty(),
            entities = parseEntities(map["e"]),
            header = map.bool("h"),
            colspan = map.intOrNull("cs") ?: 1,
            rowspan = map.intOrNull("rs") ?: 1,
        )
    }

    private fun parseRelated(map: Map<*, *>?): InstantViewRelatedArticle? {
        val url = map?.str("url") ?: return null
        return InstantViewRelatedArticle(
            url = url,
            title = map.str("title"),
            description = map.str("description"),
            author = map.str("author"),
            publishedDate = map.intOrNull("date"),
            photoCacheKey = map.str("photo"),
        )
    }

    private fun parseCaption(map: Map<*, *>?): InstantViewCaption? {
        map ?: return null
        val text = map.str("t").orEmpty()
        val credit = parseRich(map["credit"] as? Map<*, *>)
        if (text.isBlank() && credit == null) return null
        return InstantViewCaption(
            text = text,
            entities = parseEntities(map["e"]),
            credit = credit,
        )
    }

    private fun parseRich(map: Map<*, *>?): InstantViewRichText? {
        map ?: return null
        val text = map.str("t").orEmpty()
        if (text.isBlank()) return null
        return InstantViewRichText(text, parseEntities(map["e"]))
    }

    private fun parseEntities(raw: Any?): List<TextEntity> {
        val list = raw as? List<*> ?: return emptyList()
        return list.mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            val kind = map.str("k") ?: return@mapNotNull null
            TextEntity(
                kind = kind,
                offset = map.int("o"),
                length = map.int("l"),
                url = map.str("u"),
            )
        }
    }
}

/** Bridge-facing Instant View DTO without UniFFI types. */
data class InstantViewDtoLike(
    val url: String,
    val displayUrl: String,
    val title: String? = null,
    val siteName: String? = null,
    val description: String? = null,
    val webpageType: String? = null,
    val hash: Int = 0,
    val hasInstantView: Boolean = false,
    val part: Boolean = false,
    val rtl: Boolean = false,
    val v2: Boolean = false,
    val notModified: Boolean = false,
    val blocksJson: String = "[]",
)

private fun Map<*, *>.str(key: String): String? = this[key] as? String
private fun Map<*, *>.bool(key: String): Boolean = this[key] == true
private fun Map<*, *>.int(key: String): Int = intOrNull(key) ?: 0
private fun Map<*, *>.intOrNull(key: String): Int? = when (val value = this[key]) {
    is Int -> value
    is Long -> value.toInt()
    is Double -> value.toInt()
    is String -> value.toIntOrNull()
    else -> null
}
private fun Map<*, *>.long(key: String): Long = longOrNull(key) ?: 0L
private fun Map<*, *>.longOrNull(key: String): Long? = when (val value = this[key]) {
    is Long -> value
    is Int -> value.toLong()
    is Double -> value.toLong()
    is String -> value.toLongOrNull()
    else -> null
}
private fun Map<*, *>.double(key: String): Double = when (val value = this[key]) {
    is Double -> value
    is Int -> value.toDouble()
    is Long -> value.toDouble()
    is String -> value.toDoubleOrNull() ?: 0.0
    else -> 0.0
}
