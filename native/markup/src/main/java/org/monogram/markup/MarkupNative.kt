package org.monogram.markup

import uniffi.monogram_markup.extractMath as nativeExtractMath
import uniffi.monogram_markup.highlightCode as nativeHighlightCode
import uniffi.monogram_markup.libraryVersion as nativeLibraryVersion
import uniffi.monogram_markup.parseTelegramMarkdown as nativeParseTelegramMarkdown
import uniffi.monogram_markup.supportedHighlightLanguages as nativeSupportedHighlightLanguages
import uniffi.monogram_markup.uniffiEnsureInitialized
import uniffi.monogram_markup.renderBlocks as nativeRenderBlocks
import uniffi.monogram_markup.MarkupEntityDto

/**
 * Thin UniFFI surface for tree-sitter markup. Features must not import `uniffi.*`.
 */
object MarkupNative {
    init {
        uniffiEnsureInitialized()
    }

    fun libraryVersion(): String = nativeLibraryVersion()

    fun renderBlocks(text: String, entities: List<NativeMarkupEntity>, parseMarkdown: Boolean): List<NativeMarkupBlock> =
        nativeRenderBlocks(text, entities.map { MarkupEntityDto(it.kind, it.offset, it.length, it.extra) }, parseMarkdown)
            .map { block ->
                NativeMarkupBlock(
                    block.kind, block.text,
                    block.entities.map { NativeMarkupEntity(it.kind, it.offset, it.length, it.extra) },
                    block.language, block.level, block.headers, block.rows,
                )
            }

    fun parseTelegramMarkdown(raw: String): NativeStyledMarkup {
        val dto = nativeParseTelegramMarkdown(raw)
        return NativeStyledMarkup(
            text = dto.text,
            entities = dto.entities.map { entity ->
                NativeMarkupEntity(
                    kind = entity.kind,
                    offset = entity.offset,
                    length = entity.length,
                    extra = entity.extra,
                )
            },
        )
    }

    fun highlightCode(code: String, language: String): List<NativeHighlightSpan> =
        nativeHighlightCode(code, language).map { span ->
            NativeHighlightSpan(start = span.start, end = span.end, scope = span.scope)
        }

    fun extractMath(raw: String): List<NativeMathSpan> =
        nativeExtractMath(raw).map { span ->
            NativeMathSpan(
                start = span.start,
                end = span.end,
                display = span.display,
                source = span.source,
            )
        }

    fun supportedHighlightLanguages(): List<String> = nativeSupportedHighlightLanguages()
}

data class NativeStyledMarkup(
    val text: String,
    val entities: List<NativeMarkupEntity>,
)

data class NativeMarkupEntity(
    val kind: String,
    val offset: Int,
    val length: Int,
    val extra: String? = null,
)

data class NativeHighlightSpan(
    val start: Int,
    val end: Int,
    val scope: String,
)

data class NativeMathSpan(
    val start: Int,
    val end: Int,
    val display: Boolean,
    val source: String,
)

data class NativeMarkupBlock(
    val kind: String,
    val text: String,
    val entities: List<NativeMarkupEntity>,
    val language: String?,
    val level: Int,
    val headers: List<String>,
    val rows: List<List<String>>,
)
