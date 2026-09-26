package org.monogram.feature.dialog.ui

import androidx.compose.ui.graphics.ImageBitmap
import org.monogram.core.markup.CodeHighlight
import org.monogram.core.models.RichBlock
import org.monogram.core.models.TextEntity

/** Keeps decoded chat chrome across LazyColumn recycle so scroll does not flash. */
internal object DialogRenderCache {
    private const val MAX = 48
    private val highlights = lru<String, List<CodeHighlight>>(MAX)
    private val formulas = lru<String, ImageBitmap>(MAX)
    private val blocks = lru<String, List<RichBlock>>(MAX)

    /**
     * Parsed rich blocks survive LazyColumn recycle: native parsing is synchronous on the
     * main thread, so re-parsing on every scroll back is the visible cost.
     */
    @Synchronized
    fun blocks(key: String): List<RichBlock>? = blocks[key]

    @Synchronized
    fun putBlocks(key: String, value: List<RichBlock>) {
        blocks[key] = value
    }

    fun blocksKey(text: String, entities: List<TextEntity>, parseMarkdown: Boolean): String {
        val sig = StringBuilder(text.length + entities.size * 24 + 8)
        sig.append(if (parseMarkdown) '1' else '0').append('\u0000')
        sig.append(text.length).append('\u0000').append(text.hashCode())
        entities.forEach { entity ->
            sig.append('\u0001').append(entity.kind).append(',').append(entity.offset)
                .append(',').append(entity.length).append(',').append(entity.url.orEmpty())
        }
        return sig.toString()
    }

    @Synchronized
    fun highlight(code: String, language: String?): List<CodeHighlight>? =
        highlights[highlightKey(code, language)]

    @Synchronized
    fun putHighlight(code: String, language: String?, value: List<CodeHighlight>) {
        highlights[highlightKey(code, language)] = value
    }

    @Synchronized
    fun formula(key: String): ImageBitmap? = formulas[key]

    @Synchronized
    fun putFormula(key: String, value: ImageBitmap) {
        formulas[key] = value
    }

    fun formulaKey(
        source: String,
        display: Boolean,
        colorArgb: Int,
        fontSizePx: Float,
        maxWidth: Int,
    ): String = "$source\u0000$display\u0000$colorArgb\u0000$fontSizePx\u0000$maxWidth"

    private fun highlightKey(code: String, language: String?): String =
        "${language.orEmpty()}\u0000${code.length}\u0000${code.hashCode()}"

    private fun <K, V> lru(max: Int) = object : LinkedHashMap<K, V>(max, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > max
    }
}
