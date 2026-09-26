package org.monogram.core.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset

fun LazyItemScope.listItemMotion(
    animateAppearance: Boolean = true,
    animatePlacement: Boolean = true,
): Modifier = Modifier.animateItem(
    fadeInSpec = if (animateAppearance) tween(durationMillis = 220) else null,
    fadeOutSpec = if (animateAppearance) tween(durationMillis = 160) else null,
    placementSpec = if (animatePlacement) spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
        visibilityThreshold = IntOffset.VisibilityThreshold,
    ) else null,
)

/** Chat list: fade new rows, never slide existing ones when order updates. */
fun LazyItemScope.chatListItemMotion(): Modifier = Modifier.animateItem(
    fadeInSpec = tween(durationMillis = 220),
    fadeOutSpec = tween(durationMillis = 120),
    placementSpec = null,
)
