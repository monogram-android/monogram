package org.monogram.core.ui.media

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.monogram.core.ui.ExpressiveDefaults
import org.monogram.core.ui.R

internal enum class CaptionDetent { COLLAPSED, HALF, EXPANDED }

@Composable
internal fun ViewerCaptionRow(
    caption: String?,
    reserved: Boolean,
    onExpand: () -> Unit,
    entities: List<CaptionEntity> = emptyList(),
    actions: MediaViewerActions = MediaViewerActions(),
) {
    val hasCaption = !caption.isNullOrBlank()
    if (!hasCaption && !reserved) return
    val dragUp = remember { mutableStateOf(0f) }
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = if (reserved) 78.dp else 0.dp)
            .pointerInput(reserved) {
                detectVerticalDragGestures(
                    onDragStart = { dragUp.value = 0f },
                    onVerticalDrag = { change, amount -> change.consume(); dragUp.value += amount },
                    onDragEnd = { if (dragUp.value < -24.dp.toPx()) onExpand(); dragUp.value = 0f },
                    onDragCancel = { dragUp.value = 0f },
                )
            }
            .clickable(onClick = onExpand)
            .padding(start = 18.dp, end = 4.dp, top = 10.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (hasCaption) {
            Text(
                text = captionText(caption.orEmpty(), entities, MaterialTheme.colorScheme.primary, actions),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        } else Spacer(Modifier.weight(1f))
        IconButton(onClick = onExpand, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.ExpandLess, stringResource(R.string.media_caption_expand))
        }
    }
}

internal fun captionText(
    text: String,
    entities: List<CaptionEntity>,
    linkColor: Color,
    actions: MediaViewerActions,
): AnnotatedString = AnnotatedString.Builder(text).apply {
    val linkStyles = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
    for (entity in entities) {
        val start = entity.offset
        val end = start.toLong() + entity.length
        if (start < 0 || entity.length <= 0 || end > text.length || !text.isUtf16Boundary(start) ||
            !text.isUtf16Boundary(end.toInt())) continue
        val finish = end.toInt()
        when (entity.type) {
            CaptionEntity.Type.BOLD -> addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, finish)
            CaptionEntity.Type.URL -> {
                val raw = entity.value?.takeIf { it.isNotBlank() } ?: text.substring(start, finish)
                if (raw.any { it.isWhitespace() || it.isISOControl() }) continue
                val target = when {
                    raw.matches(Regex("[A-Za-z][A-Za-z0-9+.-]*:.*")) -> raw
                    raw.substringBefore('/').contains('.') -> "https://$raw"
                    else -> continue
                }
                addLink(LinkAnnotation.Clickable(target, linkStyles) { actions.onCaptionUrl(target) }, start, finish)
            }
            CaptionEntity.Type.MENTION -> {
                val target = entity.value?.takeIf { it.matches(Regex("tg://user\\?id=[1-9][0-9]*")) }
                    ?: text.substring(start, finish).takeIf { it.matches(Regex("@[A-Za-z0-9_]+")) }
                        ?.let { "https://t.me/${it.removePrefix("@")}" }
                    ?: continue
                addLink(LinkAnnotation.Clickable(target, linkStyles) { actions.onCaptionMention(target) }, start, finish)
            }
        }
    }
}.toAnnotatedString()

private fun String.isUtf16Boundary(index: Int): Boolean = index in 0..length &&
    (index == 0 || index == length || !(this[index - 1].isHighSurrogate() && this[index].isLowSurrogate()))

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun MediaCaptionSheet(
    visible: Boolean,
    detent: CaptionDetent,
    item: MediaViewerItem,
    index: Int,
    albumSize: Int,
    actions: MediaViewerActions,
    onDetentChange: (CaptionDetent) -> Unit,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
    sideSheet: Boolean = false,
) {
    val motion = mediaViewerMotionEnabled()
    if (sideSheet) {
        BackHandler(enabled = visible, onBack = onCollapse)
        AnimatedVisibility(
            visible = visible,
            enter = slideInHorizontally(MediaMotion.spatial(motion)) { it } + fadeIn(MediaMotion.effects(motion)),
            exit = slideOutHorizontally(MediaMotion.spatial(motion)) { it } + fadeOut(MediaMotion.effects(motion)),
            modifier = modifier,
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RectangleShape,
                modifier = Modifier.fillMaxHeight().width(360.dp)
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            ) {
                CaptionContent(item, index, albumSize, actions, expanded = true, onCollapse = onCollapse)
            }
        }
    } else if (visible) {
        val density = LocalDensity.current
        val state = remember {
            SheetState(
                enabledValues = setOf(SheetValue.Hidden, SheetValue.PartiallyExpanded, SheetValue.Expanded),
                positionalThreshold = { with(density) { 56.dp.toPx() } },
                velocityThreshold = { with(density) { 125.dp.toPx() } },
                initialValue = if (detent == CaptionDetent.EXPANDED) SheetValue.Expanded else SheetValue.PartiallyExpanded,
            )
        }
        val latestDetentChange by rememberUpdatedState(onDetentChange)
        LaunchedEffect(state) {
            snapshotFlow { state.currentValue }.collect { value ->
                when (value) {
                    SheetValue.Expanded -> latestDetentChange(CaptionDetent.EXPANDED)
                    SheetValue.PartiallyExpanded -> latestDetentChange(CaptionDetent.HALF)
                    SheetValue.Hidden -> Unit
                }
            }
        }
        MaterialTheme(motionScheme = viewerSheetMotion(motion)) {
            ModalBottomSheet(
                onDismissRequest = onCollapse,
                sheetState = state,
                shape = RectangleShape,
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                dragHandle = { BottomSheetDefaults.DragHandle() },
                contentWindowInsets = { WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal) },
            ) {
                CaptionContent(
                    item, index, albumSize, actions,
                    expanded = state.targetValue == SheetValue.Expanded,
                    onCollapse = onCollapse,
                    modifier = Modifier.height((LocalConfiguration.current.screenHeightDp * 0.72f).dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CaptionContent(
    item: MediaViewerItem,
    index: Int,
    albumSize: Int,
    actions: MediaViewerActions,
    expanded: Boolean,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasCaption = !item.caption.isNullOrBlank()
    val shapes = ButtonDefaults.shapes(shape = CircleShape, pressedShape = RoundedCornerShape(ExpressiveDefaults.PressRadius))
    Column(modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                item.senderName?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = ExpressiveDefaults.nameSemiBold(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item.dateLabel?.takeIf { it.isNotBlank() }?.let { MetaChip(it) }
                    if (albumSize > 1) MetaChip("${index + 1}/$albumSize")
                    if (item.isVideo) MetaChip(stringResource(R.string.media_badge_video_lower))
                }
            }
            IconButton(onClick = onCollapse) {
                Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.media_caption_collapse))
            }
        }
        HorizontalDivider(Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
        ) {
            SelectionContainer {
                Text(
                    text = captionText(
                        item.caption?.takeIf { it.isNotBlank() } ?: stringResource(R.string.media_caption_empty),
                        item.captionEntities, MaterialTheme.colorScheme.primary, actions,
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (hasCaption) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalButton(
                onClick = { actions.onShowInChat(item) }, shapes = shapes,
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier.weight(1f).height(52.dp),
            ) {
                Icon(Icons.Outlined.ChatBubbleOutline, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.media_action_show_in_chat), style = ExpressiveDefaults.actionSemiBold(), maxLines = 1)
            }
            if (!item.protectedContent) FilledTonalButton(
                onClick = { actions.onCopyCaption(item) }, enabled = hasCaption, shapes = shapes,
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier.weight(1f).height(52.dp),
            ) {
                Icon(Icons.Default.ContentCopy, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.media_action_copy_caption), style = ExpressiveDefaults.actionSemiBold(), maxLines = 1)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun viewerSheetMotion(motion: Boolean): MotionScheme {
    val themeMotion = MaterialTheme.motionScheme
    return remember(motion, themeMotion) {
        if (motion) themeMotion else object : MotionScheme {
            override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> = snap()
            override fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> = snap()
            override fun <T> slowSpatialSpec(): FiniteAnimationSpec<T> = snap()
            override fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> = snap()
            override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> = snap()
            override fun <T> slowEffectsSpec(): FiniteAnimationSpec<T> = snap()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun MediaInfoSheet(item: MediaViewerItem, session: MediaPlaybackSession?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val unknown = stringResource(R.string.media_info_unknown)
    val yes = stringResource(R.string.media_info_yes)
    val no = stringResource(R.string.media_info_no)
    val file = (item.source as? MediaSource.Local)?.file
    val localSize by produceState<Long?>(null, file, item.fileSize) {
        value = if (item.fileSize?.takeIf { it > 0 } == null && file != null) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                file.takeIf { it.isFile }?.length()?.takeIf { it > 0 }
            }
        } else null
    }
    val size = item.fileSize?.takeIf { it > 0 } ?: localSize
    val playerSize = if (item.isVideo && session?.current?.id == item.id &&
        session.player.currentMediaItem?.mediaId == item.id && session.aspectRatio > 0f
    ) session.player.videoSize else null
    val width = playerSize?.width?.takeIf { it > 0 } ?: item.width
    val height = playerSize?.height?.takeIf { it > 0 } ?: item.height
    val hdr = if (item.isVideo && session?.current?.id == item.id && session.videoHdr != MediaHdr.None) {
        session.videoHdr
    } else item.hdr
    val date = item.dateMillis?.takeIf { it > 0 }?.let {
        java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
            .format(java.util.Date(it))
    } ?: item.dateLabel?.takeIf { it.isNotBlank() } ?: unknown
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    MaterialTheme(motionScheme = viewerSheetMotion(mediaViewerMotionEnabled())) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = state,
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
            dragHandle = { BottomSheetDefaults.DragHandle() },
            contentWindowInsets = { WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal) },
        ) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(stringResource(R.string.media_action_info), style = MaterialTheme.typography.titleLarge)
                InfoRow("name", stringResource(R.string.media_info_name), item.fileName?.takeIf { it.isNotBlank() } ?: unknown)
                InfoRow("size", stringResource(R.string.media_info_size), size?.let { android.text.format.Formatter.formatFileSize(context, it) } ?: unknown)
                InfoRow("dimensions", stringResource(R.string.media_info_dimensions),
                    if (width != null && width > 0 && height != null && height > 0) {
                        stringResource(R.string.media_info_dimensions_value, width, height)
                    } else unknown,
                )
                InfoRow("duration", stringResource(R.string.media_info_duration),
                    item.durationSeconds?.takeIf { it > 0 }?.let { android.text.format.DateUtils.formatElapsedTime(it.toLong()) } ?: unknown,
                )
                InfoRow("date", stringResource(R.string.media_info_date), date)
                InfoRow("hdr", stringResource(R.string.media_info_hdr), if (hdr != MediaHdr.None) yes else no)
                InfoRow("gainmap", stringResource(R.string.media_info_gainmap), if (hdr == MediaHdr.GainMap) yes else no)
            }
        }
    }
}

@Composable
private fun InfoRow(tag: String, label: String, value: String) {
    Column(Modifier.fillMaxWidth().testTag("media_info_$tag").semantics(mergeDescendants = true) {}) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer { Text(value, style = MaterialTheme.typography.bodyLarge) }
    }
}

@Composable
private fun MetaChip(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = CircleShape,
    ) {
        Box(Modifier.height(28.dp).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
            Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}
