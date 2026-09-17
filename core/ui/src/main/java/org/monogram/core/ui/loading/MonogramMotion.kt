package org.monogram.core.ui.loading

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.WavyProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize

object MonogramMotion {

    val appear: SpringSpec<Float> = spring(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMediumLow,
        visibilityThreshold = 0.001f,
    )

    val disappear: SpringSpec<Float> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium,
        visibilityThreshold = 0.001f,
    )

    val barSize: SpringSpec<IntSize> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium,
        visibilityThreshold = IntSize.VisibilityThreshold,
    )

    val progress: AnimationSpec<Float> = WavyProgressIndicatorDefaults.ProgressAnimationSpec

    const val AppearScale: Float = 0.92f

    const val DisappearScale: Float = 0.96f

    const val SettleMillis: Long = 210L
}

@Composable
internal fun MonogramOverlayHost(
    visible: Boolean,
    modifier: Modifier = Modifier,
    generation: Int = 0,
    semantics: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(MonogramMotion.appear) + scaleIn(MonogramMotion.appear, initialScale = MonogramMotion.AppearScale),
        exit = fadeOut(MonogramMotion.disappear) + scaleOut(MonogramMotion.disappear, targetScale = MonogramMotion.DisappearScale),
    ) {
        Box(modifier = semantics, contentAlignment = contentAlignment) {
            key(generation) { content() }
        }
    }
}

@Composable
internal fun MonogramBarHost(
    visible: Boolean,
    modifier: Modifier = Modifier,
    generation: Int = 0,
    semantics: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(MonogramMotion.appear) + expandVertically(MonogramMotion.barSize, expandFrom = Alignment.Top),
        exit = fadeOut(MonogramMotion.disappear) + shrinkVertically(MonogramMotion.barSize, shrinkTowards = Alignment.Top),
    ) {
        Box(modifier = semantics) {
            key(generation) { content() }
        }
    }
}

@Composable
internal fun rememberMonoProgress(
    generation: Int,
    progress: (() -> Float)?,
): Float {
    val target = progress?.invoke()?.coerceIn(0f, 1f) ?: 0f
    val animated = remember { Animatable(0f) }
    var seenGeneration by remember { mutableIntStateOf(generation) }
    LaunchedEffect(generation, target) {
        val fresh = seenGeneration != generation
        seenGeneration = generation
        when {
            fresh -> animated.snapTo(0f)
            target < animated.value -> animated.snapTo(target)
        }
        animated.animateTo(target, MonogramMotion.progress)
    }
    return animated.value
}

internal fun Modifier.monoProgressSemantics(
    progress: Float?,
    status: String?,
): Modifier = semantics(mergeDescendants = true) {
    progressBarRangeInfo =
        if (progress != null) ProgressBarRangeInfo(progress, 0f..1f) else ProgressBarRangeInfo.Indeterminate
    if (!status.isNullOrBlank()) contentDescription = status
}
