package org.monogram.core.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import org.monogram.core.ui.R

/** Time label and unread badge slots in the trailing column of the placeholder row. */
internal val SkeletonTimeWidth = 28.dp
internal val SkeletonTimeHeight = 10.dp
internal val SkeletonBadgeWidth = 22.dp
internal val SkeletonBadgeHeight = 18.dp

/** Width of the shimmering title and preview bars, as a share of the text column. */
private const val TitleBarWidth = 0.46f
private const val PreviewBarWidth = 0.78f

/**
 * One placeholder dialog row: the same avatar, line boxes, insets and trailing column as a real
 * chat row ([ChatRowMetrics]), filled with shimmering bars.
 */
@Composable
fun ChatRowSkeleton(
    modifier: Modifier = Modifier,
    showAvatar: Boolean = true,
    showBadge: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = ChatRowMetrics.Height)
            .padding(
                horizontal = ChatRowMetrics.SideInset,
                vertical = ChatRowMetrics.ContainerPaddingV,
            )
            .padding(
                horizontal = ChatRowMetrics.ContentPaddingH,
                vertical = ChatRowMetrics.ContentPaddingV,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showAvatar) {
            MonogramPlaceholder(
                modifier = Modifier.size(ChatRowMetrics.AvatarSize),
                shape = CircleShape,
            )
            Spacer(Modifier.width(ChatRowMetrics.AvatarGap))
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(ChatRowMetrics.LineSpacing),
        ) {
            // The title keeps its full line box so the name bar lands on the loaded title line.
            ChatRowSkeletonLine(height = ChatRowMetrics.TitleLineHeight) {
                MonogramPlaceholder(
                    modifier = Modifier
                        .fillMaxWidth(TitleBarWidth)
                        .height(14.dp),
                    shape = RoundedCornerShape(6.dp),
                )
            }
            ChatRowSkeletonLine(height = ChatRowMetrics.PreviewLineHeight) {
                MonogramPlaceholder(
                    modifier = Modifier
                        .fillMaxWidth(PreviewBarWidth)
                        .height(12.dp),
                    shape = RoundedCornerShape(6.dp),
                )
            }
        }
        Spacer(Modifier.width(ChatRowMetrics.RightGap))
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(ChatRowMetrics.RightSpacing),
        ) {
            // Time sits on the title line, exactly where the loaded row puts it.
            ChatRowSkeletonLine(
                height = ChatRowMetrics.TitleLineHeight,
                alignment = Alignment.CenterEnd,
            ) {
                MonogramPlaceholder(
                    modifier = Modifier.size(SkeletonTimeWidth, SkeletonTimeHeight),
                    shape = RoundedCornerShape(5.dp),
                )
            }
            if (showBadge) {
                MonogramPlaceholder(
                    modifier = Modifier.size(SkeletonBadgeWidth, SkeletonBadgeHeight),
                    shape = RoundedCornerShape(9.dp),
                )
            }
        }
    }
}

@Composable
private fun ChatRowSkeletonLine(
    height: Dp,
    alignment: Alignment = Alignment.CenterStart,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier.heightIn(min = height),
        contentAlignment = alignment,
    ) { content() }
}

@Composable
fun ChatListSkeleton(
    modifier: Modifier = Modifier,
    itemCount: Int? = null,
    showAvatar: Boolean = true,
    showBadge: Boolean = true,
) {
    val description = stringResource(R.string.status_connecting)
    val progress = remember { Animatable(0f) }
    val slidePx = with(LocalDensity.current) { 12.dp.toPx() }
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .clipToBounds()
            .semantics { contentDescription = description },
    ) {
        // The placeholder has to reach the bottom of the list pane: on tall screens a fixed row count
        // leaves an empty band under the last row.
        val rows = itemCount ?: chatSkeletonRowCount(maxHeight.value, ChatRowMetrics.Height.value)
        LaunchedEffect(rows) {
            progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 560, easing = FastOutSlowInEasing),
            )
        }
        Column(modifier = Modifier.fillMaxWidth()) {
            repeat(rows) { index ->
                val t = ((progress.value * (rows + 3) - index) / 3.5f).coerceIn(0f, 1f)
                ChatRowSkeleton(
                    showAvatar = showAvatar,
                    showBadge = showBadge,
                    modifier = Modifier.graphicsLayer {
                        alpha = t
                        translationY = (1f - t) * slidePx
                    },
                )
            }
        }
    }
}

/** Rows of dialog placeholders used when the pane height is unknown (unbounded parent). */
internal const val ChatSkeletonFallbackRows = 10

/**
 * Placeholder rows needed to cover [availableDp] of list pane. The last row may be cut off, so the
 * shimmer reaches the bottom edge instead of stopping two thirds down a tall screen.
 */
internal fun chatSkeletonRowCount(availableDp: Float, rowDp: Float): Int = when {
    rowDp <= 0f -> 1
    !availableDp.isFinite() -> ChatSkeletonFallbackRows
    else -> ceil(availableDp / rowDp).toInt().coerceIn(1, ChatSkeletonMaxRows)
}

/** A pane taller than this is not a phone screen; the cap keeps the placeholder bounded. */
private const val ChatSkeletonMaxRows = 40
