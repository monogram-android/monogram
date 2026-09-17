package org.monogram.feature.dialog.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import org.monogram.core.models.RichBlock
import org.monogram.core.models.TextEntity

@Composable
internal fun rememberMessageBlocks(
    text: String,
    entities: List<TextEntity>,
    parseMarkdown: Boolean = true,
): List<RichBlock> {
    val parser = LocalMarkupParser.current
    return remember(text, entities, parseMarkdown, parser) {
        val key = DialogRenderCache.blocksKey(text, entities, parseMarkdown)
        DialogRenderCache.blocks(key)
            ?: parser.renderBlocks(text, entities, parseMarkdown)
                .also { DialogRenderCache.putBlocks(key, it) }
    }
}

internal fun List<RichBlock>.containsSpoilers(): Boolean = any { block ->
    when (block) {
        is RichBlock.Paragraph -> block.entities.any { it.kind == "spoiler" }
        is RichBlock.Heading -> block.entities.any { it.kind == "spoiler" }
        is RichBlock.Quote -> block.entities.any { it.kind == "spoiler" }
        is RichBlock.TaskList -> block.items.any { item -> item.entities.any { it.kind == "spoiler" } }
        is RichBlock.Details -> block.children.containsSpoilers()
        else -> false
    }
}
