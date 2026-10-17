package org.monogram.feature.dialog.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.monogram.core.common.Outcome
import org.monogram.core.models.TextEntity
import org.monogram.core.ui.components.LocalMediaAnimationEnabled
import org.monogram.core.ui.loading.MonogramLoading
import org.monogram.core.ui.rememberEnsuredFile
import org.monogram.feature.dialog.PickerPoster
import org.monogram.feature.dialog.PosterLoadGate
import org.monogram.feature.dialog.posterRequestKey
import org.monogram.network.http.MediaRepository
import java.io.File

internal val LocalDialogMedia = staticCompositionLocalOf<MediaRepository?> { null }

internal fun isInViewport(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    viewportWidth: Int,
    viewportHeight: Int,
    lookaheadPx: Float,
): Boolean {
    if (right <= left || bottom <= top || viewportWidth <= 0 || viewportHeight <= 0) return false
    return right > -lookaheadPx &&
            bottom > -lookaheadPx &&
            left < viewportWidth + lookaheadPx &&
            top < viewportHeight + lookaheadPx
}

@Composable
internal fun rememberViewportVisible(key: Any?): Pair<Boolean, Modifier> {
    var measuredVisible by remember(key) { mutableStateOf(false) }
    var visible by remember(key) { mutableStateOf(false) }
    LaunchedEffect(measuredVisible) {
        if (measuredVisible) {
            visible = true
        } else {
            delay(250L)
            visible = false
        }
    }
    val modifier = Modifier.onGloballyPositioned { coordinates ->
        if (!coordinates.isAttached) return@onGloballyPositioned
        val bounds = coordinates.boundsInRoot()
        val root = coordinates.findRootCoordinates().size
        val lookahead = root.height * 0.35f
        val next = isInViewport(
            left = bounds.left,
            top = bounds.top,
            right = bounds.right,
            bottom = bounds.bottom,
            viewportWidth = root.width,
            viewportHeight = root.height,
            lookaheadPx = lookahead,
        )
        if (measuredVisible != next) measuredVisible = next
    }
    return visible to modifier
}

internal fun customEmojiInlineMap(
    text: String,
    entities: List<TextEntity>,
    onOpenPack: ((Long) -> Unit)? = null,
): Map<String, InlineTextContent> {
    val ids = entities.mapNotNull { entity ->
        if (entity.kind != "custom_emoji") return@mapNotNull null
        entity.url?.toLongOrNull()
    }.distinct()
    if (ids.isEmpty()) return emptyMap()
    return ids.associate { id ->
        val alt = entities.firstOrNull { it.url == id.toString() }?.let { entity ->
            val start = entity.offset.coerceIn(0, text.length)
            val end = (entity.offset + entity.length).coerceIn(start, text.length)
            text.substring(start, end)
        }.orEmpty()
        "ce:$id" to InlineTextContent(
            Placeholder(22.sp, 22.sp, PlaceholderVerticalAlign.TextCenter),
        ) {
            CustomEmojiGlyph(
                documentId = id,
                fallback = alt,
                onClick = onOpenPack?.let { open -> { open(id) } },
            )
        }
    }
}

internal fun AnnotatedString.Builder.appendWithCustomEmoji(
    text: String,
    entities: List<TextEntity>,
) {
    val marks = entities
        .filter { it.kind == "custom_emoji" && !it.url.isNullOrBlank() }
        .sortedBy { it.offset }
    if (marks.isEmpty()) {
        append(text)
        return
    }
    var cursor = 0
    for (entity in marks) {
        val start = entity.offset.coerceIn(0, text.length)
        val end = (entity.offset + entity.length).coerceIn(start, text.length)
        if (start > cursor) append(text.substring(cursor, start))
        appendInlineContent("ce:${entity.url}", text.substring(start, end).ifEmpty { "□" })
        cursor = end
    }
    if (cursor < text.length) append(text.substring(cursor))
}

@Composable
internal fun CustomEmojiGlyph(
    documentId: Long,
    fallback: String = "",
    size: Dp = 20.dp,
    compact: Boolean = false,
    onClick: (() -> Unit)? = null,
    playback: PanelCellPlayback? = null,
) {
    val repo = LocalDialogMedia.current
    val animationEnabled = if (playback == null) LocalMediaAnimationEnabled.current else false
    val (mediaVisible, visibilityModifier) = rememberViewportVisible(documentId)
    val mediaAnimationEnabled = playback == null && animationEnabled && mediaVisible
    val placedVisibility = if (playback == null) visibilityModifier else Modifier
    val cacheChanges = remember(repo, documentId) {
        repo?.cacheGeneration("emoji:$documentId")
    }
    val cacheGeneration = cacheChanges?.collectAsState(initial = 0L)?.value ?: 0L
    val file = rememberEnsuredFile(
        generation = cacheGeneration,
        identity = documentId,
        resolve = { repo?.cachedFile("emoji:$documentId") },
        ensure = {
            val media = repo ?: return@rememberEnsuredFile null
            when (val result = media.ensureCustomEmoji(documentId)) {
                is Outcome.Ok -> result.value
                is Outcome.Err -> null
            }
        },
        demand = playback?.decode != false,
    )
    val click = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    val local = file?.takeIf { it.exists() && it.length() > 0L }
    val kind = remember(cacheGeneration, local?.absolutePath, local?.length()) {
        local?.let(::glyphFileKind)
    }
    Box(
        modifier = Modifier
            .size(size)
            .then(click)
            .then(placedVisibility),
        contentAlignment = Alignment.Center,
    ) {
        if (local == null) {
            if (fallback.isNotEmpty()) {
                Text(text = fallback, style = TextStyle(fontSize = 18.sp))
            } else if (compact) {
                MonogramLoading(size = size / 3)
            }
            return@Box
        }
        when {
            playback != null && (kind == GlyphFileKind.Gzip || kind == GlyphFileKind.Webm) -> PanelPooledGlyph(
                documentId = documentId,
                file = local,
                playback = playback,
                modifier = Modifier.fillMaxSize(),
            )

            kind == GlyphFileKind.Gzip -> {
                val bytes by produceState<ByteArray?>(initialValue = null, local) {
                    value = withContext(Dispatchers.IO) {
                        runCatching { local.readBytes() }.getOrNull()
                    }
                }
                if (bytes != null) {
                    StickerPlayer(
                        lottieBytes = bytes!!,
                        modifier = Modifier.fillMaxSize(),
                        displaySize = size,
                        active = mediaAnimationEnabled,
                    )
                } else if (fallback.isNotEmpty()) {
                    Text(text = fallback, style = TextStyle(fontSize = 18.sp))
                }
            }

            kind == GlyphFileKind.Webm -> {
                VpxStickerPlayer(
                    file = local,
                    active = mediaAnimationEnabled,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            else -> {
                AsyncImage(
                    model = local,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

internal val PanelVideoPosterGate = PosterLoadGate<Bitmap>()

@Composable
internal fun VideoStill(
    file: File,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    maxSizePx: Int = 720,
    extract: Boolean = true,
    requestKey: String? = null,
) {
    val maxSide = maxSizePx.coerceAtLeast(1)
    val key = requestKey ?: posterRequestKey(file.absolutePath, maxSide)
    var thumbnail by remember(key) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(key, file, maxSide, extract) {
        PanelVideoPosterGate.cached(key)?.let { thumbnail = it }
        if (!extract || thumbnail != null || gzipFile(file)) return@LaunchedEffect
        val decoded = PanelVideoPosterGate.load(key) {
            withContext(Dispatchers.IO) { videoThumbnail(file, maxSide) }
        }
        if (decoded != null) thumbnail = decoded
    }
    if (thumbnail != null) {
        Image(
            bitmap = thumbnail!!.asImageBitmap(),
            contentDescription = null,
            contentScale = contentScale,
            modifier = modifier,
        )
    }
}

internal fun coldPosterAdmitted(choice: PickerPoster): Boolean = choice == PickerPoster.ColdStill
