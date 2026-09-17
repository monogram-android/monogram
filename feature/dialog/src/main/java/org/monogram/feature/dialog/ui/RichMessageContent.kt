package org.monogram.feature.dialog.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import org.monogram.core.markup.CodeHighlight
import org.monogram.core.models.RichBlock
import org.monogram.core.models.TaskItem
import org.monogram.core.models.TextEntity
import org.monogram.feature.dialog.R
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

@Composable
internal fun RichMessageContent(
    text: String,
    entities: List<TextEntity>,
    contentColor: Color,
    linkColor: Color,
    revealSpoilers: Boolean,
    onSpoilerClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    selectable: Boolean = false,
    selectAllNonce: Int = 0,
    onSelectedText: (String) -> Unit = {},
    onTextLayout: (androidx.compose.ui.text.TextLayoutResult) -> Unit = {},
    onOpenStickerPack: ((Long) -> Unit)? = null,
    preparedBlocks: List<RichBlock>? = null,
    parseMarkdown: Boolean = true,
    hostMessage: org.monogram.core.models.Message? = null,
    mediaRepository: org.monogram.network.http.MediaRepository? = null,
) {
    val blocks = preparedBlocks?.takeIf { it.isNotEmpty() }
        ?: rememberMessageBlocks(text, entities, parseMarkdown)

    if (selectable) {
        SelectionContainer(modifier = modifier) {
            RichMessageContent(
                text = text,
                entities = entities,
                contentColor = contentColor,
                linkColor = linkColor,
                revealSpoilers = revealSpoilers,
                onSpoilerClick = onSpoilerClick,
                preparedBlocks = blocks,
                onOpenStickerPack = onOpenStickerPack,
                onTextLayout = onTextLayout,
                hostMessage = hostMessage,
                mediaRepository = mediaRepository,
            )
        }
        return
    }

    if (blocks.size <= 1 && blocks.firstOrNull() is RichBlock.Paragraph) {
        val single = blocks.firstOrNull() as? RichBlock.Paragraph
        MathAwareText(
            text = single?.text ?: text,
            entities = single?.entities ?: entities,
            contentColor = contentColor,
            linkColor = linkColor,
            revealSpoilers = revealSpoilers,
            onSpoilerClick = onSpoilerClick,
            selectable = selectable,
            selectAllNonce = selectAllNonce,
            onSelectedText = onSelectedText,
            onTextLayout = onTextLayout,
            onOpenStickerPack = onOpenStickerPack,
            modifier = modifier,
        )
        return
    }

    Column(
        modifier = modifier.wrapContentWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        RichBlockList(
            blocks = blocks,
            contentColor = contentColor,
            linkColor = linkColor,
            revealSpoilers = revealSpoilers,
            onSpoilerClick = onSpoilerClick,
            selectable = selectable,
            selectAllNonce = selectAllNonce,
            onSelectedText = onSelectedText,
            onOpenStickerPack = onOpenStickerPack,
            hostMessage = hostMessage,
            mediaRepository = mediaRepository,
        )
    }
}

@Composable
private fun RichBlockList(
    blocks: List<RichBlock>,
    contentColor: Color,
    linkColor: Color,
    revealSpoilers: Boolean,
    onSpoilerClick: (() -> Unit)? = null,
    selectable: Boolean,
    selectAllNonce: Int,
    onSelectedText: (String) -> Unit,
    onOpenStickerPack: ((Long) -> Unit)? = null,
    hostMessage: org.monogram.core.models.Message? = null,
    mediaRepository: org.monogram.network.http.MediaRepository? = null,
) {
    blocks.forEach { block ->
        when (block) {
            is RichBlock.Paragraph -> {
                MathAwareText(
                    text = block.text,
                    entities = block.entities,
                    contentColor = contentColor,
                    linkColor = linkColor,
                    revealSpoilers = revealSpoilers,
                    onSpoilerClick = onSpoilerClick,
                    selectable = selectable,
                    selectAllNonce = selectAllNonce,
                    onSelectedText = onSelectedText,
                    onOpenStickerPack = onOpenStickerPack,
                )
            }
            is RichBlock.Rule -> {
                HorizontalDivider(
                    color = contentColor.copy(alpha = 0.35f),
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            }
            is RichBlock.Code -> {
                CodeBlockView(
                    code = block.text,
                    language = block.language,
                    contentColor = contentColor,
                )
            }
            is RichBlock.Quote -> {
                QuoteBlockView(
                    text = block.text,
                    entities = block.entities,
                    collapsed = block.collapsed,
                    level = block.level,
                    contentColor = contentColor,
                    linkColor = linkColor,
                    revealSpoilers = revealSpoilers,
                    onSpoilerClick = onSpoilerClick,
                )
            }
            is RichBlock.TaskList -> {
                TaskListView(
                    items = block.items,
                    contentColor = contentColor,
                    linkColor = linkColor,
                    revealSpoilers = revealSpoilers,
                    onSpoilerClick = onSpoilerClick,
                )
            }
            is RichBlock.Photo -> {
                val host = hostMessage
                if (host != null) {
                    val key = block.cacheKey.takeIf { it.isNotBlank() } ?: host.mediaCacheKey
                    MessageMedia(
                        message = host.copy(
                            mediaKind = "photo",
                            mediaCacheKey = key ?: host.mediaCacheKey,
                            thumbCacheKey = host.thumbCacheKey ?: key?.let { "$it:thumb" },
                            mediaWidth = block.width.takeIf { it > 0 } ?: host.mediaWidth,
                            mediaHeight = block.height.takeIf { it > 0 } ?: host.mediaHeight,
                            text = null,
                        ),
                        mediaRepository = mediaRepository,
                    )
                }
            }
            is RichBlock.Heading -> {
                MathAwareText(
                    text = block.text,
                    entities = block.entities,
                    contentColor = contentColor,
                    linkColor = linkColor,
                    revealSpoilers = revealSpoilers,
                    onSpoilerClick = onSpoilerClick,
                    selectable = selectable,
                    selectAllNonce = selectAllNonce,
                    onSelectedText = onSelectedText,
                    textStyle = headingStyle(block.level),
                    onOpenStickerPack = onOpenStickerPack,
                )
            }
            is RichBlock.Table -> {
                TableView(
                    table = block,
                    contentColor = contentColor,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            is RichBlock.Details -> {
                var open by remember(block.title) { mutableStateOf(false) }
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable { open = !open }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (open) "▾  " else "▸  ",
                            color = contentColor,
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = block.title.ifBlank { "Details" },
                            color = contentColor,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    if (open && block.children.isNotEmpty()) {
                        Column(
                            modifier = Modifier.padding(start = 16.dp, top = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            RichBlockList(
                                blocks = block.children,
                                contentColor = contentColor,
                                linkColor = linkColor,
                                revealSpoilers = revealSpoilers,
                                selectable = selectable,
                                selectAllNonce = selectAllNonce,
                                onSelectedText = onSelectedText,
                                hostMessage = hostMessage,
                                mediaRepository = mediaRepository,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun headingStyle(level: Int): TextStyle {
    val typography = MaterialTheme.typography
    return when (level.coerceIn(1, 6)) {
        1 -> typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
        2 -> typography.titleLarge.copy(fontWeight = FontWeight.Bold)
        3 -> typography.titleMedium.copy(fontWeight = FontWeight.Bold)
        else -> typography.titleSmall.copy(fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun CodeBlockView(
    code: String,
    language: String?,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    val shape = MaterialTheme.shapes.small
    val bg = contentColor.copy(alpha = 0.08f)
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember(code) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }
    // Keep the icon on the language line: the M3 IconButton touch target would
    // push it below the label.
    val headerLine = with(LocalDensity.current) {
        val lineHeight = if (language.isNullOrBlank()) {
            MaterialTheme.typography.bodyMedium.lineHeight
        } else {
            MaterialTheme.typography.labelSmall.lineHeight
        }
        lineHeight.toDp()
    }
    Box(
        modifier = modifier
            .widthIn(min = 120.dp)
            .clip(shape)
            .background(bg)
            .padding(8.dp),
    ) {
        Column(
            modifier = Modifier.padding(end = 20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (!language.isNullOrBlank()) {
                Text(
                    text = language.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            val markup = LocalMarkupParser.current
            val scheme = MaterialTheme.colorScheme
            val highlighted by produceState(
                DialogRenderCache.highlight(code, language).orEmpty(),
                code,
                language,
                markup,
            ) {
                DialogRenderCache.highlight(code, language)?.let {
                    value = it
                    return@produceState
                }
                val result = withContext(Dispatchers.Default) {
                    markup.highlightCode(code, language.orEmpty())
                }
                DialogRenderCache.putHighlight(code, language, result)
                value = result
            }
            val annotated = remember(code, highlighted, scheme, contentColor) {
                highlightToAnnotatedString(code, highlighted) { scope ->
                    highlightScopeColor(
                        scope = scope,
                        onSurface = contentColor,
                        primary = scheme.primary,
                        secondary = scheme.secondary,
                        tertiary = scheme.tertiary,
                        onSurfaceVariant = scheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = annotated,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                ),
                color = contentColor,
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(width = 24.dp, height = headerLine)
                .clip(CircleShape)
                .clickable {
                    scope.launch {
                        clipboard.setClipEntry(ClipEntry(android.content.ClipData.newPlainText("text", code)))
                    }
                    copied = true
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                contentDescription = stringResource(
                    if (copied) R.string.dialog_code_copied else R.string.dialog_code_copy,
                ),
                tint = if (copied) MaterialTheme.colorScheme.primary else contentColor.copy(alpha = 0.7f),
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/**
 * Quote frame content: inline text or a nested region. [level] is the number of
 * frames that region draws (duplicate ranges are deeper levels).
 */
internal data class QuoteSlice(
    val text: String,
    val entities: List<TextEntity>,
    val nested: Boolean = false,
    val level: Int = 1,
    val collapsed: Boolean = false,
)

/** Splits a quote block into gap text and nested regions (quote-in-quote). */
internal fun splitQuoteSlices(text: String, entities: List<TextEntity>): List<QuoteSlice> {
    val quotes = entities
        .filter { it.kind == "blockquote" && it.length > 0 }
        .map { it.copy(offset = it.offset.coerceIn(0, text.length)) }
        .filter { it.offset + it.length <= text.length }
    val levels = quotes.groupingBy { it.offset to it.offset + it.length }.eachCount()
    // Ranges covering the block are the caller's levels, not nested regions.
    val ownLevels = levels.keys.filter { (start, end) -> start <= 0 && end >= text.length }.toSet()
    val candidates = levels.keys.filterNot { it in ownLevels }
    val direct = candidates
        .filter { range ->
            candidates.none { other ->
                other != range && other.first <= range.first && other.second >= range.second
            }
        }
        .sortedBy { it.first }
    if (direct.isEmpty()) {
        return listOf(QuoteSlice(text, entities.filterNot { it.kind == "blockquote" }))
    }
    val slices = mutableListOf<QuoteSlice>()
    var cursor = 0
    for ((start, end) in direct) {
        if (start > cursor) {
            slices += QuoteSlice(
                text = text.substring(cursor, start).trim('\n'),
                entities = shiftSliceEntities(entities, cursor, start).filterNot { it.kind == "blockquote" },
            )
        }
        slices += QuoteSlice(
            text = text.substring(start, end),
            // Only strictly inner quotes stay; the own range is already [level].
            entities = shiftSliceEntities(entities, start, end).filterNot {
                it.kind == "blockquote" && it.offset <= 0 && it.offset + it.length >= end - start
            },
            nested = true,
            level = levels.getValue(start to end),
            collapsed = entities.any {
                it.kind == "blockquote" && it.url == "collapsed" &&
                    it.offset == start && it.offset + it.length == end
            },
        )
        cursor = end
    }
    if (cursor < text.length) {
        slices += QuoteSlice(
            text = text.substring(cursor).trim('\n'),
            entities = shiftSliceEntities(entities, cursor, text.length).filterNot { it.kind == "blockquote" },
        )
    }
    return slices.filter { it.text.isNotBlank() || it.nested }
}

private fun shiftSliceEntities(entities: List<TextEntity>, start: Int, end: Int): List<TextEntity> =
    entities.mapNotNull { entity ->
        val lo = maxOf(entity.offset, start)
        val hi = minOf(entity.offset + entity.length, end)
        if (lo >= hi) null else entity.copy(offset = lo - start, length = hi - lo)
    }

@Composable
private fun QuoteBlockView(
    text: String,
    entities: List<TextEntity>,
    contentColor: Color,
    linkColor: Color,
    revealSpoilers: Boolean,
    onSpoilerClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    collapsed: Boolean = false,
    level: Int = 1,
) {
    var expanded by remember(text, collapsed) { mutableStateOf(!collapsed) }
    val slices = remember(text, entities) { splitQuoteSlices(text, entities) }
    QuoteFrame(level = level.coerceAtLeast(1), contentColor = contentColor, modifier = modifier) {
        slices.forEach { slice ->
            if (slice.nested) {
                QuoteBlockView(
                    text = slice.text,
                    entities = slice.entities,
                    contentColor = contentColor,
                    linkColor = linkColor,
                    revealSpoilers = revealSpoilers,
                    onSpoilerClick = onSpoilerClick,
                    collapsed = slice.collapsed,
                    level = slice.level,
                )
            } else {
                MathAwareText(
                    text = slice.text,
                    entities = slice.entities,
                    contentColor = contentColor.copy(alpha = 0.92f),
                    linkColor = linkColor,
                    revealSpoilers = revealSpoilers,
                    onSpoilerClick = onSpoilerClick,
                )
            }
        }
        if (collapsed && !expanded) {
            Text(
                text = stringResource(R.string.dialog_quote_show_more),
                color = linkColor,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .clickable { expanded = true }
                    .padding(top = 2.dp),
            )
        }
    }
}

/** Draws [level] stacked quote frames around [content] (nested quotes). */
@Composable
private fun QuoteFrame(
    level: Int,
    contentColor: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = modifier
            .widthIn(min = 80.dp)
            .height(IntrinsicSize.Min)
            .clip(MaterialTheme.shapes.small)
            .background(contentColor.copy(alpha = 0.08f)),
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.primary),
        )
        Column(
            modifier = Modifier.padding(start = 10.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (level > 1) QuoteFrame(level - 1, contentColor, content = content) else content()
        }
    }
}

@Composable
private fun TaskListView(
    items: List<TaskItem>,
    contentColor: Color,
    linkColor: Color,
    revealSpoilers: Boolean,
    onSpoilerClick: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items.forEach { item ->
            val label = stringResource(
                if (item.done) R.string.dialog_task_done else R.string.dialog_task_open,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 40.dp)
                    .semantics { contentDescription = label },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TelegramRoundCheck(
                    checked = item.done,
                    color = if (item.done) MaterialTheme.colorScheme.primary else contentColor,
                )
                MathAwareText(
                    text = item.text,
                    entities = item.entities,
                    contentColor = if (item.done) contentColor.copy(alpha = 0.72f) else contentColor,
                    linkColor = linkColor,
                    revealSpoilers = revealSpoilers,
                    onSpoilerClick = onSpoilerClick,
                )
            }
        }
    }
}

@Composable
private fun TableView(
    table: RichBlock.Table,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    val border = contentColor.copy(alpha = 0.22f)
    val headerBg = contentColor.copy(alpha = 0.08f)
    val shape = RoundedCornerShape(8.dp)
    var viewportPx by remember { mutableIntStateOf(0) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, border, shape),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { viewportPx = it.width }
                .horizontalScroll(rememberScrollState())
                .padding(1.dp),
        ) {
            TableGrid(
                table = table,
                contentColor = contentColor,
                border = border,
                headerBg = headerBg,
                minTableWidthPx = viewportPx,
            )
        }
    }
}

@Composable
private fun TableGrid(
    table: RichBlock.Table,
    contentColor: Color,
    border: Color,
    headerBg: Color,
    minTableWidthPx: Int,
) {
    val columnCount = table.headers.size.coerceAtLeast(1)
    val lastRow = table.rows.lastIndex
    val measurer = rememberTextMeasurer()
    val headerStyle = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
    val bodyStyle = MaterialTheme.typography.bodySmall
    val density = LocalDensity.current
    val colWidths = remember(table, minTableWidthPx, headerStyle, bodyStyle, density) {
    with(density) {
        val padPx = 16.dp.roundToPx()
        val minCol = 56.dp.roundToPx()
        val fittedMax = if (minTableWidthPx > 0 && columnCount > 0) {
            max(minCol, minTableWidthPx / columnCount)
        } else {
            180.dp.roundToPx()
        }
        val textMax = (fittedMax - padPx).coerceAtLeast(minCol / 2)
        val textConstraints = Constraints(maxWidth = textMax)
        val widths = IntArray(columnCount) { col ->
            val headerWidth = measurer.measure(
                text = table.headers.getOrElse(col) { "" },
                style = headerStyle,
                constraints = textConstraints,
            ).size.width
            var bodyWidth = 0
            table.rows.forEach { row ->
                bodyWidth = max(
                    bodyWidth,
                    measurer.measure(
                        text = row.getOrElse(col) { "" },
                        style = bodyStyle,
                        constraints = textConstraints,
                    ).size.width,
                )
            }
            (max(headerWidth, bodyWidth) + padPx).coerceIn(minCol, fittedMax)
        }
        var tableWidth = widths.sum()
        val target = max(tableWidth, minTableWidthPx)
        if (target > tableWidth && columnCount > 0) {
            val extra = target - tableWidth
            val each = extra / columnCount
            val rem = extra % columnCount
            for (col in 0 until columnCount) {
                widths[col] += each + if (col < rem) 1 else 0
            }
        }
        widths.map { it.toDp() }
    }
    }
    Column {
        Row(modifier = Modifier.height(IntrinsicSize.Max)) {
            table.headers.forEachIndexed { index, header ->
                TableCell(
                    text = header,
                    contentColor = contentColor,
                    border = border,
                    background = headerBg,
                    header = true,
                    drawEnd = index < columnCount - 1,
                    drawBottom = table.rows.isNotEmpty(),
                    modifier = Modifier
                        .width(colWidths[index])
                        .fillMaxHeight(),
                )
            }
        }
        table.rows.forEachIndexed { rowIndex, row ->
            Row(modifier = Modifier.height(IntrinsicSize.Max)) {
                (0 until columnCount).forEach { index ->
                    TableCell(
                        text = row.getOrElse(index) { "" },
                        contentColor = contentColor,
                        border = border,
                        drawEnd = index < columnCount - 1,
                        drawBottom = rowIndex < lastRow,
                        modifier = Modifier
                            .width(colWidths[index])
                            .fillMaxHeight(),
                    )
                }
            }
        }
    }
}

@Composable
private fun TableCell(
    text: String,
    contentColor: Color,
    border: Color,
    background: Color = Color.Transparent,
    header: Boolean = false,
    drawEnd: Boolean = false,
    drawBottom: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(background)
            .drawBehind {
                val stroke = 1.dp.toPx()
                if (drawEnd) {
                    drawLine(
                        color = border,
                        start = Offset(size.width - stroke / 2f, 0f),
                        end = Offset(size.width - stroke / 2f, size.height),
                        strokeWidth = stroke,
                    )
                }
                if (drawBottom) {
                    drawLine(
                        color = border,
                        start = Offset(0f, size.height - stroke / 2f),
                        end = Offset(size.width, size.height - stroke / 2f),
                        strokeWidth = stroke,
                    )
                }
            }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = text,
            style = if (header) {
                MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
            } else {
                MaterialTheme.typography.bodySmall
            },
            color = contentColor,
        )
    }
}
