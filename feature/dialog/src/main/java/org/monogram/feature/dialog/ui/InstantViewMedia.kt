package org.monogram.feature.dialog.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            HorizontalPager(
                state = pager,
                modifier = Modifier.fillMaxWidth(),
                pageSpacing = 12.dp,
            ) { page ->
                InstantViewBlockContent(
                    block = block.items[page],
                    scale = scale,
                    query = query,
                    mediaRepository = mediaRepository,
                    onOpenUrl = onOpenUrl,
                    onOpenPeer = onOpenPeer,
                )
            }
            if (block.items.size > 1) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    repeat(block.items.size) { index ->
                        val active = pager.currentPage == index
                        val width by animateFloatAsState(
                            targetValue = if (active) 18f else 6f,
                            label = "ivSlideDot",
                        )
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 3.dp)
                                .size(width = width.dp, height = 6.dp)
                                .clip(CircleShape)
                                .background(
                                    if (active) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                                    },
                                ),
                        )
                    }
                }
            }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            block.items.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { item ->
                        Box(Modifier.weight(1f)) {
                            InstantViewBlockContent(
                                block = item,
                                scale = scale,
                                query = query,
                                mediaRepository = mediaRepository,
                                onOpenUrl = onOpenUrl,
                                onOpenPeer = onOpenPeer,
                            )
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
    val density = LocalDensity.current
    val textSize = with(density) { (16 * scale).sp.toPx() }
    val maxWidth = with(density) { IvSpacing.ReadableWidth.roundToPx() }
    val bitmap by produceState<ImageBitmap?>(null, source, textSize, color, maxWidth) {
        value = withContext(Dispatchers.Default) {
            renderLatexBitmap(source, true, color.toArgb(), textSize, maxWidth)
        }
    }
    val rendered = bitmap
    if (rendered != null) {
        Image(
            bitmap = rendered,
            contentDescription = source,
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        Text(
            text = source,
            style = ivTextStyle("pre", 0, scale),
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
        is InstantViewBlock.Photo -> {
            InstantViewPhoto(
                cacheKey = inner.cacheKey,
                width = inner.width,
                height = inner.height,
                mediaRepository = mediaRepository,
                hero = true,
                openable = true,
            )
            inner.caption?.let { InstantViewCaption(it, scale, query, onOpenUrl) }
        }

        is InstantViewBlock.Document -> InstantViewDocument(
            inner,
            scale,
            query,
            mediaRepository,
            onOpenUrl
        )

        else -> InstantViewBlockContent(inner, scale, query, mediaRepository, onOpenUrl, onOpenPeer)
    }
}

@Composable
internal fun InstantViewCaption(
    caption: InstantViewCaption,
    scale: Float,
    query: String,
    onOpenUrl: (String) -> Unit,
) {
    if (caption.text.isBlank() && caption.credit == null) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (caption.text.isNotBlank()) {
            InstantViewText(
                text = caption.text,
                entities = caption.entities,
                style = ivCaptionStyle(scale),
                query = query,
                onOpenUrl = onOpenUrl,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        caption.credit?.let { InstantViewRich(it, scale, query, onOpenUrl) }
    }
}

@Composable
internal fun InstantViewRich(
    rich: InstantViewRichText,
    scale: Float,
    query: String,
    onOpenUrl: (String) -> Unit,
) {
    InstantViewText(
        text = rich.text,
        entities = rich.entities,
        style = ivCreditStyle(scale),
        query = query,
        onOpenUrl = onOpenUrl,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun InstantViewText(
    text: String,
    entities: List<TextEntity>,
    style: TextStyle,
    query: String,
    onOpenUrl: (String) -> Unit,
    color: Color? = null,
    softWrap: Boolean = true,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    if (text.isBlank()) return
    val linkColor = MaterialTheme.colorScheme.primary
    val highlight = ivSearchHighlight()
    val resolved = if (color != null) style.copy(color = color) else style
    val annotated = remember(text, entities, query, linkColor, highlight, resolved, onOpenUrl) {
        buildAnnotatedString {
            append(text)
            applyMessageEntities(text, entities, linkColor, onLink = onOpenUrl)
            val needle = query.trim()
            if (needle.isNotEmpty()) {
                var from = 0
                while (from < text.length) {
                    val at = text.indexOf(needle, from, ignoreCase = true)
                    if (at < 0) break
                    addStyle(SpanStyle(background = highlight), at, at + needle.length)
                    from = at + needle.length
                }
            }
        }
    }
    SelectionContainer {
        Text(
            text = annotated,
            style = resolved,
            softWrap = softWrap,
            overflow = overflow,
        )
    }
}

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
    is InstantViewBlock.Details -> (block.title?.text.orEmpty()) + block.blocks.joinToString {
        blockSearchText(
            it
        )
    }

    is InstantViewBlock.Related -> block.articles.joinToString { it.title.orEmpty() + it.description.orEmpty() }
    is InstantViewBlock.Math -> block.source
    is InstantViewBlock.EmbedPost -> block.author + block.blocks.joinToString { blockSearchText(it) }
    is InstantViewBlock.Cover -> blockSearchText(block.block)
    is InstantViewBlock.MediaGroup -> block.items.joinToString { blockSearchText(it) }
    is InstantViewBlock.Document -> listOfNotNull(
        block.title,
        block.fileName,
        block.performer,
        block.caption?.text
    )
        .joinToString()

    is InstantViewBlock.Photo -> block.caption?.text.orEmpty()
    is InstantViewBlock.Channel -> listOfNotNull(block.title, block.username).joinToString()
    is InstantViewBlock.Map -> block.caption?.text.orEmpty()
    is InstantViewBlock.Embed -> block.caption?.text.orEmpty()
    is InstantViewBlock.Buttons -> block.items.joinToString { it.text }
    is InstantViewBlock.Anchor, InstantViewBlock.Divider, InstantViewBlock.Unsupported -> ""
}
