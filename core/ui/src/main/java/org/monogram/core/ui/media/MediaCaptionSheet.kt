package org.monogram.core.ui.media

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.monogram.core.ui.ExpressiveDefaults
import org.monogram.core.ui.R

private const val HairlineAlpha = 0.4f

private val CaptionActionHeight = 52.dp

/** Caption detents: collapsed strip, half sheet, full sheet. */
internal enum class CaptionDetent { COLLAPSED, HALF, EXPANDED }

/**
 * Collapsed caption inside the chrome surface. [reserved] keeps the row at its two-line
 * height for album items without a caption to prevent layout jumping.
 */
@Composable
internal fun ViewerCaptionRow(caption: String?, reserved: Boolean, onExpand: () -> Unit) {
    val hasCaption = !caption.isNullOrBlank()
    if (!hasCaption && !reserved) return
    val dragUp = remember { mutableStateOf(0f) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = if (reserved) CAPTION_RESERVED_HEIGHT else 0.dp)
            .pointerInput(reserved) {
                detectVerticalDragGestures(
                    onDragStart = { dragUp.value = 0f },
                    onVerticalDrag = { change, amount ->
                        change.consume()
                        dragUp.value += amount
                    },
                    onDragEnd = {
                        if (dragUp.value < -24.dp.toPx()) onExpand()
                        dragUp.value = 0f
                    },
                    onDragCancel = { dragUp.value = 0f },
                )
            }
            .clickable(onClick = onExpand)
            .padding(start = 18.dp, end = 4.dp, top = 10.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Box(
                Modifier
                    .padding(bottom = 6.dp)
                    .size(width = 32.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (hasCaption) 0.5f else 0.25f),
                    ),
            )
            if (hasCaption) {
                Text(
                    text = caption.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onExpand, modifier = Modifier.size(48.dp)) {
            Icon(
                imageVector = Icons.Default.ExpandLess,
                contentDescription = stringResource(R.string.media_caption_expand),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Two lines of `bodySmall` plus the grabber; the tallest collapsed caption we render. */
private val CAPTION_RESERVED_HEIGHT = 62.dp

@Composable
private fun MetaChip(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = CircleShape,
    ) {
        Box(
            modifier = Modifier
                .height(28.dp)
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
        }
    }
}

/**
 * Standard (non-modal) bottom sheet with collapsed / half / expanded detents, or a side
 * sheet on wide layouts. Drag the grabber to change detent. Selection, links and the
 * two primary actions live here; destructive actions never do.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
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
    val configuration = LocalConfiguration.current
    val motion = mediaViewerMotionEnabled()
    // Detents never swallow the media: on a short landscape window the sheet becomes a
    // side sheet instead, and on a tall one it stops well short of the full height.
    val target = when (detent) {
        CaptionDetent.COLLAPSED -> 96.dp
        CaptionDetent.HALF -> minOf(320.dp, (configuration.screenHeightDp * 0.45f).dp)
        CaptionDetent.EXPANDED -> minOf(560.dp, (configuration.screenHeightDp * 0.72f).dp)
    }
    val sheetHeight by animateDpAsState(target, MediaMotion.spatial<Dp>(motion), label = "captionDetent")
    val sheetShape = if (sideSheet) {
        RoundedCornerShape(topStart = 36.dp, bottomStart = 36.dp)
    } else {
        RoundedCornerShape(topStart = 36.dp, topEnd = 36.dp)
    }
    val sheetModifier = if (sideSheet) {
        Modifier.fillMaxHeight().width((configuration.screenWidthDp * 0.42f).dp)
    } else {
        Modifier.fillMaxWidth().height(sheetHeight)
    }
    val collapseLabel = stringResource(R.string.media_caption_collapse)
    val hasCaption = !item.caption.isNullOrBlank()
    // The app-wide Shapes.small is 12.dp, so the 16.dp press radius is pinned here.
    val actionShapes = ButtonDefaults.shapes(
        shape = CircleShape,
        pressedShape = RoundedCornerShape(ExpressiveDefaults.PressRadius),
    )
    var dragTotal by remember { mutableFloatStateOf(0f) }
    val handleDrag = Modifier.pointerInput(detent) {
        detectVerticalDragGestures(
            onDragStart = { dragTotal = 0f },
            onVerticalDrag = { change, amount ->
                change.consume()
                dragTotal += amount
            },
            onDragEnd = {
                val travelled = dragTotal
                dragTotal = 0f
                if (travelled < -40f) {
                    when (detent) {
                        CaptionDetent.COLLAPSED -> onDetentChange(CaptionDetent.HALF)
                        CaptionDetent.HALF -> onDetentChange(CaptionDetent.EXPANDED)
                        CaptionDetent.EXPANDED -> Unit
                    }
                } else if (travelled > 40f) {
                    when (detent) {
                        CaptionDetent.EXPANDED -> onDetentChange(CaptionDetent.HALF)
                        CaptionDetent.HALF -> onDetentChange(CaptionDetent.COLLAPSED)
                        CaptionDetent.COLLAPSED -> onCollapse()
                    }
                }
            },
            onDragCancel = { dragTotal = 0f },
        )
    }

    AnimatedVisibility(
        visible = visible,
        enter = if (sideSheet) {
            slideInHorizontally(MediaMotion.spatial(motion)) { it } + fadeIn()
        } else {
            slideInVertically(MediaMotion.spatial(motion)) { it } + fadeIn()
        },
        exit = if (sideSheet) {
            slideOutHorizontally(MediaMotion.spatial(motion)) { it } + fadeOut()
        } else {
            slideOutVertically(MediaMotion.spatial(motion)) { it } + fadeOut()
        },
        modifier = modifier,
    ) {
        Surface(
            color = MediaViewerTokens.Chrome,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = sheetShape,
            tonalElevation = 0.dp,
            modifier = sheetModifier
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)),
        ) {
            Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                // The whole strip drags, so the 36x4 handle does not have to be hit exactly.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .then(handleDrag),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(width = 36.dp, height = 4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = HairlineAlpha)),
                    )
                    FilledTonalIconButton(
                        onClick = onCollapse,
                        shapes = IconButtonDefaults.shapes(),
                        modifier = Modifier.align(Alignment.CenterEnd),
                    ) {
                        Icon(Icons.Default.KeyboardArrowDown, collapseLabel)
                    }
                }
                val senderName = item.senderName?.takeIf { it.isNotBlank() }
                val dateChip = item.dateLabel?.takeIf { it.isNotBlank() }
                val hasMetaChips = dateChip != null || albumSize > 1 || item.isVideo
                // No sender: no avatar, no name line, and no header row without chips either.
                if (senderName != null || hasMetaChips) {
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (senderName != null) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            shape = CircleShape,
                            modifier = Modifier.size(40.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = senderName
                                        .firstOrNull { it.isLetterOrDigit() }?.uppercase().orEmpty(),
                                    style = ExpressiveDefaults.nameSemiBold(),
                                )
                            }
                        }
                    }
                        Column(Modifier.weight(1f)) {
                            if (senderName != null) {
                                Text(
                                    text = senderName,
                                    style = ExpressiveDefaults.nameSemiBold(),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.height(6.dp))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                dateChip?.let { MetaChip(it) }
                                if (albumSize > 1) MetaChip("${index + 1}/$albumSize")
                                if (item.isVideo) MetaChip(stringResource(R.string.media_badge_video_lower))
                            }
                        }
                    }
                }
                HorizontalDivider(
                    modifier = Modifier.padding(top = 12.dp),
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = HairlineAlpha),
                )
                Column(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 16.dp),
                ) {
                    SelectionContainer {
                        Text(
                            text = item.caption ?: stringResource(R.string.media_caption_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (hasCaption) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
                HorizontalDivider(
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = HairlineAlpha),
                )
                if (detent == CaptionDetent.COLLAPSED) {
                    // The peek detent shows the header and the caption only; the actions
                    // belong to the half and expanded detents where they fit.
                    return@Column
                }
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    FilledTonalButton(
                        onClick = { actions.onShowInChat(item) },
                        shapes = actionShapes,
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier.weight(1f).height(CaptionActionHeight),
                    ) {
                        Icon(Icons.Outlined.ChatBubbleOutline, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.media_action_show_in_chat),
                            style = ExpressiveDefaults.actionSemiBold(),
                            maxLines = 1,
                        )
                    }
                    FilledTonalButton(
                        onClick = { actions.onCopyCaption(item) },
                        enabled = hasCaption,
                        shapes = actionShapes,
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier.weight(1f).height(CaptionActionHeight),
                    ) {
                        Icon(Icons.Default.ContentCopy, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.media_action_copy_caption),
                            style = ExpressiveDefaults.actionSemiBold(),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}
