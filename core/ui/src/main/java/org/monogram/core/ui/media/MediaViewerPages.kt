package org.monogram.core.ui.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color.green
import android.graphics.Color.rgb
import android.graphics.ImageDecoder
import android.graphics.Outline
import android.os.Build
import android.view.SurfaceView
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.scale
import androidx.media3.common.Player
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.monogram.core.ui.ExpressiveDefaults
import org.monogram.core.ui.R
import org.monogram.core.ui.components.boundedMediaOffset
import org.monogram.core.ui.components.fittedMediaSize
import org.monogram.core.ui.components.focalMediaOffset
import org.monogram.core.ui.loading.MonogramCircularProgress
import org.monogram.core.ui.loading.MonogramLoadingHeroSize
import kotlin.math.abs

@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ViewerLoadingIndicator(progress: Float?, modifier: Modifier = Modifier) {
    val bounded = progress?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
    if (bounded != null) {
        androidx.compose.material3.LoadingIndicator(
            progress = { bounded },
            modifier = modifier.size(48.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
        )
    } else {
        androidx.compose.material3.LoadingIndicator(
            modifier = modifier.size(48.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
        )
    }
}

internal fun decodeSpoilerPreview(file: java.io.File): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    try {
        BitmapFactory.decodeFile(file.absolutePath, bounds)
    } catch (_: IllegalArgumentException) {
        return null
    }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 32) sample *= 2
    val decoded = try {
        BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (_: IllegalArgumentException) {
        null
    } ?: return null
    val tiny = decoded.scale(12, 12)
    if (tiny !== decoded) decoded.recycle()
    val pixels = IntArray(144)
    tiny.getPixels(pixels, 0, 12, 0, 0, 12, 12)
    val blurred = IntArray(144)
    for (y in 0 until 12) for (x in 0 until 12) {
        var red = 0;
        var green = 0;
        var blue = 0;
        var count = 0
        for (dy in -2..2) for (dx in -2..2) {
            val color = pixels[(y + dy).coerceIn(0, 11) * 12 + (x + dx).coerceIn(0, 11)]
            red += android.graphics.Color.red(color)
            green += green(color)
            blue += android.graphics.Color.blue(color)
            count++
        }
        blurred[y * 12 + x] = rgb(red / count, green / count, blue / count)
    }
    tiny.recycle()
    val result = Bitmap.createBitmap(blurred, 12, 12, Bitmap.Config.ARGB_8888)
    val width = if (bounds.outWidth >= bounds.outHeight) 48 else
        (48f * bounds.outWidth / bounds.outHeight).toInt().coerceAtLeast(1)
    val height = if (bounds.outHeight >= bounds.outWidth) 48 else
        (48f * bounds.outHeight / bounds.outWidth).toInt().coerceAtLeast(1)
    return result.scale(width, height)
        .also { if (it !== result) result.recycle() }
}

internal fun shouldDismissViewer(distance: Float, height: Float): Boolean =
    height > 0f && distance / height > 0.25f

internal const val MAX_PHOTO_SCALE = 4f
internal const val DOUBLE_TAP_SCALE = 2.5f

/**
 * Still page. Zoom, pan, double-tap at the focal point and drag-to-dismiss. When the
 * photo is zoomed the page consumes the gesture, so panning never changes album item.
 */
@Composable
internal fun MediaPhotoPage(
    item: MediaViewerItem,
    onToggleChrome: () -> Unit,
    onDragProgress: (Float) -> Unit,
    onDragEnd: (dismiss: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    onHdrDetected: (MediaHdr) -> Unit = {},
    onDimensionsDetected: (width: Int, height: Int) -> Unit = { _, _ -> },
) {
    val fullFile = (item.source as? MediaSource.Local)?.file
    var revealed by rememberSaveable(item.id) { mutableStateOf(!item.spoiler) }
    val concealed = item.spoiler && !revealed
    val reportHdr by rememberUpdatedState(onHdrDetected)
    val reportDimensions by rememberUpdatedState(onDimensionsDetected)
    var bitmap by remember(item.id, fullFile, concealed) { mutableStateOf<Bitmap?>(null) }
    var spoilerPreview by remember(
        item.id,
        item.preview,
        concealed
    ) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(item.id, item.preview, fullFile, concealed) {
        spoilerPreview = if (concealed) withContext(Dispatchers.IO) {
            (item.preview ?: fullFile)?.let(::decodeSpoilerPreview)
        } else null
    }
    // Zoom survives rotation and process recreation, like any other viewer state.
    var scale by rememberSaveable(item.id) { mutableFloatStateOf(1f) }
    var relativeX by rememberSaveable(item.id) { mutableFloatStateOf(0f) }
    var relativeY by rememberSaveable(item.id) { mutableFloatStateOf(0f) }
    var viewport by remember { mutableStateOf(Size.Zero) }
    var zoomActivity by remember(item.id) { mutableIntStateOf(0) }
    var zoomBadgeVisible by remember(item.id) { mutableStateOf(false) }
    val badgeEffects = MediaMotion.effects<Float>(mediaViewerMotionEnabled())
    LaunchedEffect(zoomActivity) {
        if (zoomActivity == 0) return@LaunchedEffect
        zoomBadgeVisible = true
        delay(900)
        zoomBadgeVisible = false
    }
    var image by remember(item.id) { mutableStateOf(Size.Zero) }
    var aspect by remember(item.id) { mutableStateOf(item.aspectRatio) }
    var failed by remember(item.id) { mutableStateOf(false) }
    var loading by remember(item.id) { mutableStateOf(true) }

    LaunchedEffect(item.id, fullFile, concealed) {
        if (concealed || fullFile == null) {
            reportHdr(MediaHdr.None)
            return@LaunchedEffect
        }
        loading = true
        failed = false
        val decoded = withContext(Dispatchers.IO) {
            try {
                if (Build.VERSION.SDK_INT >= 28) {
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(fullFile))
                } else {
                    BitmapFactory.decodeFile(fullFile.absolutePath)
                }
            } catch (_: java.io.IOException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }
        bitmap = decoded
        loading = false
        failed = decoded == null
        if (decoded != null) {
            reportDimensions(decoded.width, decoded.height)
            image = Size(decoded.width.toFloat(), decoded.height.toFloat())
            aspect = decoded.width.toFloat() / decoded.height
        }
        val hasGainMap = Build.VERSION.SDK_INT >= 34 && decoded?.hasGainmap() == true
        android.util.Log.d("MediaViewerHDR", "photo hasGainMap=$hasGainMap")
        reportHdr(if (hasGainMap) MediaHdr.GainMap else MediaHdr.None)
    }

    fun currentOffset(): Offset {
        val fit = fittedMediaSize(viewport, image)
        return boundedMediaOffset(
            Offset(relativeX * fit.width * scale, relativeY * fit.height * scale),
            scale, fit, image,
        )
    }

    fun applyTransform(nextOffset: Offset, nextScale: Float) {
        val fit = fittedMediaSize(viewport, image)
        val bounded = boundedMediaOffset(nextOffset, nextScale, fit, image)
        relativeX = if (fit.width > 0f) bounded.x / (fit.width * nextScale) else 0f
        relativeY = if (fit.height > 0f) bounded.y / (fit.height * nextScale) else 0f
        scale = nextScale
        zoomActivity++
    }

    val readOffset by rememberUpdatedState(::currentOffset)
    val apply by rememberUpdatedState(::applyTransform)
    val toggle by rememberUpdatedState(onToggleChrome)
    val reportDrag by rememberUpdatedState(onDragProgress)
    val endDrag by rememberUpdatedState(onDragEnd)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { viewport = Size(it.width.toFloat(), it.height.toFloat()) }
            .semantics {
                stateDescription = "${(scale * 100).toInt()}%"
                if (label != null) contentDescription = label
            }
            .pointerInput(item.id, concealed) {
                detectTapGestures(
                    onTap = { if (concealed) revealed = true else toggle() },
                    onDoubleTap = { point ->
                        if (concealed) {
                            revealed = true
                        } else if (scale > 1.05f) {
                            apply(Offset.Zero, 1f)
                        } else {
                            apply(
                                focalMediaOffset(
                                    readOffset(),
                                    point,
                                    viewport.center,
                                    scale,
                                    DOUBLE_TAP_SCALE,
                                    Offset.Zero
                                ),
                                DOUBLE_TAP_SCALE,
                            )
                        }
                    },
                )
            }
            .pointerInput(item.id, concealed) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var totalPan = Offset.Zero
                    var totalZoom = 1f
                    var active = false
                    var transformed = false
                    var released = false
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.any { it.isConsumed }) break
                        if (event.changes.none { it.pressed }) {
                            released = true
                            break
                        }
                        val pan = event.calculatePan()
                        val zoom = event.calculateZoom()
                        totalPan += pan
                        totalZoom *= zoom
                        if (event.changes.count { it.pressed } > 1) transformed = true
                        if (!active) {
                            active = totalPan.getDistance() > viewConfiguration.touchSlop ||
                                    abs(1f - totalZoom) * 100f > viewConfiguration.touchSlop
                        }
                        if (active) {
                            // Zoom and pan own the gesture; a vertical drag dismisses.
                            // A horizontal drag at scale 1 is deliberately NOT consumed so
                            // the album pager keeps it: swipe X changes item, never seeks.
                            if (transformed || scale > 1f) {
                                val next = (scale * zoom).coerceIn(1f, MAX_PHOTO_SCALE)
                                apply(
                                    focalMediaOffset(
                                        readOffset(),
                                        event.calculateCentroid(useCurrent = false),
                                        viewport.center, scale, next, pan,
                                    ),
                                    next,
                                )
                                event.changes.forEach { it.consume() }
                            } else if (abs(totalPan.y) > abs(totalPan.x)) {
                                reportDrag(totalPan.y)
                                event.changes.forEach { it.consume() }
                            }
                        }
                    } while (event.changes.any { it.pressed })
                    if (released && !transformed && scale <= 1f && abs(totalPan.y) > abs(totalPan.x)) {
                        endDrag(shouldDismissViewer(totalPan.y, size.height.toFloat()))
                    } else {
                        endDrag(false)
                    }
                }
            },
    ) {
        val aspectRatio = aspect ?: (4f / 3f)
        val fittedWidth =
            if (maxWidth / maxHeight > aspectRatio) maxHeight * aspectRatio else maxWidth
        val fittedHeight =
            if (maxWidth / maxHeight > aspectRatio) maxHeight else maxWidth / aspectRatio
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(fittedWidth, fittedHeight),
        ) {
            val imageModifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    val offset = currentOffset()
                    translationX = offset.x
                    translationY = offset.y
                }
            if (concealed) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .testTag("media-photo-spoiler")
                ) {
                    spoilerPreview?.let { preview ->
                        Image(
                            bitmap = preview.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                        Box(Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.25f)))
                    }
                    Icon(
                        imageVector = Icons.Default.VisibilityOff,
                        contentDescription = label,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(48.dp),
                    )
                }
            } else if (bitmap != null) {
                AndroidView(
                    factory = { context ->
                        ImageView(context).apply {
                            scaleType = ImageView.ScaleType.FIT_CENTER
                            isClickable = false
                            isFocusable = false
                        }
                    },
                    update = { it.setImageBitmap(bitmap) },
                    onRelease = { it.setImageDrawable(null) },
                    modifier = imageModifier,
                )
            } else if (item.preview != null) {
                AsyncImage(
                    model = item.preview,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    onSuccess = { result ->
                        val width = result.result.drawable.intrinsicWidth.toFloat()
                        val height = result.result.drawable.intrinsicHeight.toFloat()
                        image = Size(width, height)
                        if (aspect == null && width > 0f && height > 0f) aspect = width / height
                    },
                    modifier = imageModifier,
                )
            }
        }
        if (!concealed && bitmap == null && (item.loading || loading)) {
            ViewerLoadingIndicator(item.progress, Modifier.align(Alignment.Center))
        }
        if (failed) {
            Surface(Modifier.align(Alignment.Center), shape = MaterialTheme.shapes.large) {
                Text(
                    text = stringResource(R.string.media_image_error),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(20.dp),
                )
            }
        }
        AnimatedVisibility(
            visible = zoomBadgeVisible && scale > 1.05f,
            enter = fadeIn(badgeEffects),
            exit = fadeOut(badgeEffects),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 76.dp)
                .testTag("media-zoom-badge"),
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.7f),
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            ) {
                Text(
                    text = "${(scale * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
    }
}

/**
 * Video page. Draws the shared session's output only while this page is current, so a
 * neighbouring video shows its poster frame instead of running a second decoder.
 */
@Composable
internal fun MediaVideoPage(
    item: MediaViewerItem,
    session: MediaPlaybackSession,
    active: Boolean,
    chromeVisible: Boolean,
    playing: Boolean,
    onToggleChrome: () -> Unit,
    onDragProgress: (Float) -> Unit,
    onDragEnd: (dismiss: Boolean, swipeUp: Boolean) -> Unit,
    onDoubleTapSeek: (Long) -> Unit,
    onHoldSpeed: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    testTagPrefix: String = "media-video",
) {
    var revealed by rememberSaveable(item.id) { mutableStateOf(!item.spoiler) }
    val concealed = item.spoiler && !revealed
    var spoilerPreview by remember(item.id, item.preview) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(item.id, item.preview, concealed) {
        spoilerPreview = if (concealed) withContext(Dispatchers.IO) {
            item.preview?.let(::decodeSpoilerPreview)
        } else null
    }
    LaunchedEffect(active, concealed, session.playing) {
        if (active && concealed && session.playing) session.pause()
    }
    if (concealed) {
        Box(
            modifier
                .fillMaxSize()
                .testTag("media-video-spoiler")
                .pointerInput(item.id) {
                    detectTapGestures(onTap = { revealed = true; if (active) session.play() })
                },
            contentAlignment = Alignment.Center,
        ) {
            spoilerPreview?.let {
                Image(
                    bitmap = it.asImageBitmap(), contentDescription = null,
                    contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize(),
                )
            }
            Icon(Icons.Default.VisibilityOff, label, modifier = Modifier.size(48.dp))
        }
        return
    }
    var frameRendered by remember(item.id) { mutableStateOf(false) }
    var hint by remember(item.id) { mutableStateOf<String?>(null) }
    var holdSpeed by remember(item.id) { mutableStateOf(false) }
    val toggle by rememberUpdatedState(onToggleChrome)
    val reportDrag by rememberUpdatedState(onDragProgress)
    val endDragCallback by rememberUpdatedState(onDragEnd)
    val seekBy by rememberUpdatedState(onDoubleTapSeek)
    val holdCallback by rememberUpdatedState(onHoldSpeed)

    val player = session.player
    DisposableEffect(player, active, item.id) {
        if (!active) return@DisposableEffect onDispose { }
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                frameRendered = true
                android.util.Log.d("MediaViewerHDR", "video first frame rendered")
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .then(
                if (label != null) {
                    Modifier.semantics { contentDescription = label }
                } else {
                    Modifier
                },
            )
            .pointerInput(item.id, active) {
                detectTapGestures(
                    onTap = { if (item.isVideoNote) session.toggleMuted() else toggle() },
                    onDoubleTap = { point ->
                        val third = size.width / 3f
                        when {
                            point.x < third -> {
                                seekBy(-10_000L)
                                hint = "-10s"
                            }

                            point.x > third * 2 -> {
                                seekBy(10_000L)
                                hint = "+10s"
                            }

                            else -> toggle()
                        }
                    },
                )
            }
            .pointerInput(item.id, active) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var total = Offset.Zero
                    var holdStarted = false
                    val startedAt = System.currentTimeMillis()
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.any { it.isConsumed }) break
                        if (event.changes.none { it.pressed }) break
                        val pan = event.calculatePan()
                        total += pan
                        // Hold the right edge for a temporary 2x, like Telegram.
                        if (!holdStarted && down.position.x > size.width * 0.7f &&
                            abs(total.x) < 60f && abs(total.y) < 60f &&
                            System.currentTimeMillis() - startedAt > 380L
                        ) {
                            holdStarted = true
                            holdSpeed = true
                            holdCallback(true)
                        }
                        if (!holdStarted && abs(total.y) > abs(total.x) && abs(total.y) > 24f) {
                            reportDrag(total.y)
                        }
                    } while (event.changes.any { it.pressed })
                    if (holdSpeed) {
                        holdSpeed = false
                        holdCallback(false)
                    } else {
                        val vertical = abs(total.y) > abs(total.x)
                        endDragCallback(
                            vertical && shouldDismissViewer(total.y, size.height.toFloat()),
                            vertical && total.y < 0f,
                        )
                    }
                }
            },
    ) {
        val attachSurface = active && !session.audioOnly && item.source != null &&
                !LocalPictureInPictureActive.current
        val aspect = session.aspectRatio.takeIf { it > 0f } ?: item.aspectRatio ?: (16f / 9f)
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // Letterbox, never squash: the surface keeps the video's own aspect ratio.
            val fittedWidth = if (maxWidth / maxHeight > aspect) maxHeight * aspect else maxWidth
            val fittedHeight = if (maxWidth / maxHeight > aspect) maxHeight else maxWidth / aspect
            if (attachSurface) {
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .then(
                            if (item.isVideoNote) Modifier.size(minOf(fittedWidth, fittedHeight))
                            else Modifier.size(fittedWidth, fittedHeight)
                        )
                        .testTag(if (frameRendered) "$testTagPrefix-ready" else "$testTagPrefix-loading"),
                ) {
                    AndroidView(
                        factory = { ctx ->
                            SurfaceView(ctx).apply {
                                setZOrderOnTop(false)
                                if (item.isVideoNote) {
                                    clipToOutline = true
                                    outlineProvider = object : ViewOutlineProvider() {
                                        override fun getOutline(
                                            view: View,
                                            outline: Outline
                                        ) {
                                            outline.setOval(0, 0, view.width, view.height)
                                        }
                                    }
                                }
                                isClickable = false
                                isFocusable = false
                                player.setVideoSurfaceView(this)
                            }
                        },
                        onRelease = { surface -> runCatching { player.clearVideoSurfaceView(surface) } },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            } else {
                PosterFrame(
                    item, Modifier
                        .align(Alignment.Center)
                        .then(
                            if (item.isVideoNote) Modifier.size(minOf(fittedWidth, fittedHeight))
                            else Modifier.size(fittedWidth, fittedHeight)
                        )
                )
            }
        }
        if (active && !session.audioOnly) {
            if (session.buffering && !session.failed) {
                ViewerLoadingIndicator(item.progress, Modifier.align(Alignment.Center))
            }
        }
        if (active && session.audioOnly) {
            Surface(
                modifier = Modifier.align(Alignment.Center),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
            ) {
                Text(
                    text = stringResource(R.string.media_listen_background_active),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                )
            }
        }
        if (active && !chromeVisible) {
            // After the chrome hides, the timeline survives as a 2dp tick.
            MediaProgressTick(
                fraction = if (session.durationMs > 0L) session.positionMs.toFloat() / session.durationMs else 0f,
                playing = playing,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
        AnimatedVisibility(
            visible = hint != null,
            enter = fadeIn() + scaleIn(initialScale = 0.85f),
            exit = fadeOut() + scaleOut(targetScale = 0.85f),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.85f),
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            ) {
                Text(
                    text = hint.orEmpty(),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
        }
        if (holdSpeed) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.85f),
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(24.dp),
            ) {
                Text(
                    text = "2×",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
    LaunchedEffect(hint) {
        if (hint != null) {
            delay(700)
            hint = null
        }
    }
}

@Composable
private fun PosterFrame(item: MediaViewerItem, modifier: Modifier = Modifier) {
    val model: Any? = item.preview
    Box(modifier, contentAlignment = Alignment.Center) {
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(Modifier
                .fillMaxSize()
                .background(Color.Transparent))
        }
        MonogramCircularProgress(
            visible = model == null && item.loading,
            size = MonogramLoadingHeroSize,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** The hero play/pause control: shape morph plus an icon swap on the shared spring. */
@Composable
internal fun MediaPlayPauseHero(
    playing: Boolean,
    enabled: Boolean,
    contentDescriptionPlay: String,
    contentDescriptionPause: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource =
        remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = when {
            pressed -> 0.9f
            playing -> 1f
            else -> 1.05f
        },
        animationSpec = MediaMotion.effects(mediaViewerMotionEnabled()),
        label = "heroMorph",
    )
    val rotation by animateFloatAsState(
        targetValue = if (playing) 0f else 0f,
        animationSpec = MediaMotion.effects(mediaViewerMotionEnabled()),
        label = "heroRotation",
    )
    FilledIconButton(
        onClick = onClick,
        enabled = enabled,
        shapes = ExpressiveDefaults.iconButtonShapes(),
        interactionSource = interactionSource,
        modifier = modifier
            .size(64.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                rotationZ = rotation
            },
    ) {
        Crossfade(
            targetState = playing,
            animationSpec = MediaMotion.effects(mediaViewerMotionEnabled()),
            label = "heroIcon",
        ) { isPlaying ->
            Icon(
                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (isPlaying) contentDescriptionPause else contentDescriptionPlay,
                modifier = Modifier.size(32.dp),
            )
        }
    }
}
