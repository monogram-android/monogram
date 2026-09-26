@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package org.monogram.core.ui.loading

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.WavyProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.monogram.core.ui.R

@Composable
internal fun monoDefaultStatus(): String = stringResource(R.string.mono_loading)

val MonogramLoadingInlineSize: Dp = 20.dp

val MonogramLoadingListSize: Dp = 24.dp

val MonogramLoadingHeroSize: Dp = 48.dp

val MonogramLoadingMediaSize: Dp = 38.dp

val MonogramLoadingBarHeight: Dp = 4.dp

@Composable
fun MonogramLoading(
    visible: Boolean = true,
    modifier: Modifier = Modifier,
    size: Dp = MonogramLoadingListSize,
    generation: Int = 0,
    color: Color = MaterialTheme.colorScheme.primary,
    status: String? = monoDefaultStatus(),
) {
    MonogramOverlayHost(
        visible = visible,
        modifier = modifier,
        generation = generation,
        semantics = Modifier.monoProgressSemantics(progress = null, status = status),
    ) {
        LoadingIndicator(modifier = Modifier.size(size), color = color)
    }
}

@Composable
fun MonogramLoadingContained(
    visible: Boolean = true,
    modifier: Modifier = Modifier,
    size: Dp = MonogramLoadingHeroSize,
    generation: Int = 0,
    progress: (() -> Float)? = null,
    status: String? = monoDefaultStatus(),
) {
    val eased = rememberMonoProgress(generation, progress)
    MonogramOverlayHost(
        visible = visible,
        modifier = modifier,
        generation = generation,
        semantics = Modifier.monoProgressSemantics(
            progress = if (progress != null) eased else null,
            status = status,
        ),
    ) {
        val mark = { eased }
        if (progress != null) {
            ContainedLoadingIndicator(
                progress = mark,
                modifier = Modifier.size(size),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                indicatorColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        } else {
            ContainedLoadingIndicator(
                modifier = Modifier.size(size),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                indicatorColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
fun MonogramLinearProgress(
    visible: Boolean = true,
    modifier: Modifier = Modifier,
    progress: (() -> Float)? = null,
    generation: Int = 0,
    height: Dp? = MonogramLoadingBarHeight,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    status: String? = monoDefaultStatus(),
) {
    val eased = rememberMonoProgress(generation, progress)
    val stroke = monoLinearStroke(height)
    MonogramBarHost(
        visible = visible,
        modifier = modifier.fillMaxWidth(),
        generation = generation,
        semantics = Modifier.monoProgressSemantics(
            progress = if (progress != null) eased else null,
            status = status,
        ),
    ) {
        val sizing = if (height != null) Modifier.height(height) else Modifier
        val mark = { eased }
        if (progress != null) {
            LinearWavyProgressIndicator(
                progress = mark,
                modifier = Modifier.fillMaxWidth().then(sizing),
                color = color,
                trackColor = trackColor,
                stroke = stroke,
                trackStroke = stroke,
            )
        } else {
            LinearWavyProgressIndicator(
                modifier = Modifier.fillMaxWidth().then(sizing),
                color = color,
                trackColor = trackColor,
                stroke = stroke,
                trackStroke = stroke,
            )
        }
    }
}

@Composable
fun MonogramCircularProgress(
    visible: Boolean = true,
    modifier: Modifier = Modifier,
    progress: (() -> Float)? = null,
    generation: Int = 0,
    size: Dp = MonogramLoadingMediaSize,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    status: String? = monoDefaultStatus(),
) {
    val eased = rememberMonoProgress(generation, progress)
    MonogramOverlayHost(
        visible = visible,
        modifier = modifier,
        generation = generation,
        semantics = Modifier.monoProgressSemantics(
            progress = if (progress != null) eased else null,
            status = status,
        ),
    ) {
        val mark = { eased }
        if (progress != null) {
            CircularWavyProgressIndicator(
                progress = mark,
                modifier = Modifier.size(size),
                color = color,
                trackColor = trackColor,
            )
        } else {
            CircularWavyProgressIndicator(
                modifier = Modifier.size(size),
                color = color,
                trackColor = trackColor,
            )
        }
    }
}

@Composable
fun MonogramProgressSettled(
    progress: (() -> Float)?,
    generation: Int = 0,
    onSettled: () -> Unit,
): Boolean {
    val eased = rememberMonoProgress(generation, progress)
    val settled = progress != null && eased >= 0.999f
    androidx.compose.runtime.LaunchedEffect(settled, generation) {
        if (!settled) return@LaunchedEffect
        kotlinx.coroutines.delay(MonogramMotion.SettleMillis)
        onSettled()
    }
    return settled
}

@Composable
fun MonogramMediaLoadingOverlay(
    visible: Boolean,
    modifier: Modifier = Modifier,
    generation: Int = 0,
    progress: (() -> Float)? = null,
    size: Dp = MonogramLoadingMediaSize,
    scrim: Color = Color.Black.copy(alpha = 0.28f),
    color: Color = Color.White,
    trackColor: Color = Color.White.copy(alpha = 0.25f),
    status: String? = monoDefaultStatus(),
) {
    val eased = rememberMonoProgress(generation, progress)
    MonogramOverlayHost(
        visible = visible,
        modifier = modifier.fillMaxSize(),
        generation = generation,
        semantics = Modifier.monoProgressSemantics(
            progress = if (progress != null) eased else null,
            status = status,
        ),
    ) {
        Box(
            modifier = Modifier.fillMaxSize().background(scrim),
            contentAlignment = Alignment.Center,
        ) {
            val mark = { eased }
            if (progress != null) {
                CircularWavyProgressIndicator(
                    progress = mark,
                    modifier = Modifier.size(size),
                    color = color,
                    trackColor = trackColor,
                )
            } else {
                CircularWavyProgressIndicator(
                    modifier = Modifier.size(size),
                    color = color,
                    trackColor = trackColor,
                )
            }
        }
    }
}

@Composable
fun <T> MonogramStateSwap(
    state: T,
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.Center,
    label: String = "monoStateSwap",
    content: @Composable (T) -> Unit,
) {
    AnimatedContent(
        targetState = state,
        modifier = modifier,
        transitionSpec = {
            (fadeIn(MonogramMotion.appear) + scaleIn(MonogramMotion.appear, initialScale = MonogramMotion.AppearScale)) togetherWith
                (fadeOut(MonogramMotion.disappear) + scaleOut(MonogramMotion.disappear, targetScale = MonogramMotion.DisappearScale))
        },
        contentAlignment = contentAlignment,
        label = label,
    ) { value -> content(value) }
}

@Composable
private fun monoLinearStroke(height: Dp?): Stroke {
    val density = LocalDensity.current
    return remember(height, density) {
        val width = if (height == null) {
            with(density) { WavyProgressIndicatorDefaults.LinearContainerHeight.toPx() * 0.4f }
        } else {
            with(density) { (height * 0.4f).coerceIn(1.dp, 4.dp).toPx() }
        }
        Stroke(width = width, cap = StrokeCap.Round)
    }
}
