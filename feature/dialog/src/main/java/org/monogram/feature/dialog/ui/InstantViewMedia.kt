package org.monogram.feature.dialog.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.monogram.core.models.InstantViewBlock
import org.monogram.core.models.InstantViewCaption
import org.monogram.core.models.InstantViewRichText
import org.monogram.core.models.PeerId
import org.monogram.core.models.TextEntity
import org.monogram.core.ui.components.MediaPreviewViewer
import org.monogram.feature.dialog.R
import org.monogram.network.http.MediaPriority
import org.monogram.network.http.MediaRepository

@Composable
internal fun InstantViewMediaGroup(
    block: InstantViewBlock.MediaGroup,
    scale: Float,
    query: String,
    mediaRepository: MediaRepository?,
    onOpenUrl: (String) -> Unit,
    onOpenPeer: (PeerId) -> Unit,
) {
    if (block.kind == "slideshow" && block.items.isNotEmpty()) {
        val pager = rememberPagerState(pageCount = { block.items.size })
        Column {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth()) { page ->
                InstantViewBlockContent(block.items[page], scale, query, mediaRepository, onOpenUrl, onOpenPeer)
            }
            Text(
                text = "${pager.currentPage + 1} / ${block.items.size}",
                modifier = Modifier.padding(top = 8.dp).align(Alignment.CenterHorizontally),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            block.items.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { item ->
                        Box(Modifier.weight(1f)) {
                            InstantViewBlockContent(item, scale, query, mediaRepository, onOpenUrl, onOpenPeer)
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
    block.caption?.let { InstantViewCaption(it, scale, query, onOpenUrl) }
}

@Composable
internal fun InstantViewMath(source: String, scale: Float) {
    val color = MaterialTheme.colorScheme.onSurface
    val density = androidx.compose.ui.platform.LocalDensity.current
    val textSize = with(density) { (16 * scale).sp.toPx() }
    val maxWidth = density.run { 280.dp.roundToPx() }
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, source, textSize, color) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            renderLatexBitmap(source, true, color.toArgb(), textSize, maxWidth)
        }
    }
    if (bitmap != null) {
        androidx.compose.foundation.Image(bitmap!!, contentDescription = source, modifier = Modifier.fillMaxWidth())
    } else {
        Text(
            text = source,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontSize = (14 * scale).sp),
        )
    }
}

@Composable
internal fun InstantViewCover(
    inner: InstantViewBlock,
    scale: Float,
    query: String,
    mediaRepository: MediaRepository?,
    onOpenUrl: (String) -> Unit,
    onOpenPeer: (PeerId) -> Unit,
) {
    when (inner) {
        is InstantViewBlock.Photo -> InstantViewPhoto(
            cacheKey = inner.cacheKey,
            width = inner.width,
            height = inner.height,
            mediaRepository = mediaRepository,
            hero = true,
            openable = true,
        )
        is InstantViewBlock.Document -> InstantViewDocument(inner, scale, query, mediaRepository, onOpenUrl)
        else -> InstantViewBlockContent(inner, scale, query, mediaRepository, onOpenUrl, onOpenPeer)
    }
    if (inner is InstantViewBlock.Photo) {
        inner.caption?.let { InstantViewCaption(it, scale, query, onOpenUrl) }
    }
}

@Composable
internal fun InstantViewCaption(
    caption: InstantViewCaption,
    scale: Float,
    query: String,
    onOpenUrl: (String) -> Unit,
) {
    if (caption.text.isNotBlank()) {
        InstantViewText(
            caption.text,
            caption.entities,
            MaterialTheme.typography.bodySmall.copy(fontSize = (13 * scale).sp),
            query,
            onOpenUrl,
        )
    }
    caption.credit?.let { InstantViewRich(it, scale, query, onOpenUrl) }
}

@Composable
internal fun InstantViewRich(
    rich: InstantViewRichText,
    scale: Float,
    query: String,
    onOpenUrl: (String) -> Unit,
) {
    InstantViewText(
        rich.text,
        rich.entities,
        MaterialTheme.typography.bodySmall.copy(fontSize = (13 * scale).sp),
        query,
        onOpenUrl,
    )
}

@Composable
internal fun InstantViewText(
    text: String,
    entities: List<TextEntity>,
    style: androidx.compose.ui.text.TextStyle,
    query: String,
    onOpenUrl: (String) -> Unit,
) {
    if (text.isBlank()) return
    val linkColor = MaterialTheme.colorScheme.primary
    val annotated = remember(text, entities, query, linkColor, onOpenUrl) {
        buildAnnotatedString {
            append(text)
            applyMessageEntities(text, entities, linkColor, onLink = onOpenUrl)
            val needle = query.trim()
            if (needle.isNotEmpty()) {
                var from = 0
                while (from < text.length) {
                    val at = text.indexOf(needle, from, ignoreCase = true)
                    if (at < 0) break
                    addStyle(SpanStyle(background = Color.Yellow.copy(alpha = 0.45f)), at, at + needle.length)
                    from = at + needle.length
                }
            }
        }
    }
    SelectionContainer {
        Text(text = annotated, style = style)
    }
}

/** Full-screen viewer for article photos and page media. */
@Composable
internal fun InstantViewMediaViewer(
    cacheKey: String,
    mediaRepository: MediaRepository?,
    onDismiss: () -> Unit,
) {
    val media = rememberIvMedia(
        cacheKey = cacheKey,
        mediaRepository = mediaRepository,
        fullPriority = MediaPriority.USER,
    )
    MediaPreviewViewer(
        file = media.image ?: media.thumb,
        contentDescription = stringResource(R.string.dialog_media_photo),
        loading = media.loading,
        failed = media.failed,
        onRetry = media.retry,
        stateKey = cacheKey,
        onDismiss = onDismiss,
        asDialog = false,
    )
}

internal fun blockSearchText(block: InstantViewBlock): String = when (block) {
    is InstantViewBlock.Text -> block.text
    is InstantViewBlock.Quote -> block.text + block.blocks.joinToString { blockSearchText(it) }
    is InstantViewBlock.ListBlock -> block.items.joinToString { it.text }
    is InstantViewBlock.Table -> block.rows.joinToString { row -> row.joinToString { it.text } }
    is InstantViewBlock.Details -> (block.title?.text.orEmpty()) + block.blocks.joinToString { blockSearchText(it) }
    is InstantViewBlock.Related -> block.articles.joinToString { it.title.orEmpty() + it.description.orEmpty() }
    is InstantViewBlock.Math -> block.source
    is InstantViewBlock.EmbedPost -> block.author + block.blocks.joinToString { blockSearchText(it) }
    is InstantViewBlock.Cover -> blockSearchText(block.block)
    is InstantViewBlock.MediaGroup -> block.items.joinToString { blockSearchText(it) }
    is InstantViewBlock.Document -> listOfNotNull(block.title, block.fileName, block.performer, block.caption?.text).joinToString()
    is InstantViewBlock.Photo -> block.caption?.text.orEmpty()
    is InstantViewBlock.Channel -> listOfNotNull(block.title, block.username).joinToString()
    is InstantViewBlock.Map -> block.caption?.text.orEmpty()
    else -> ""
}
