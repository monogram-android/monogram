package org.monogram.core.harmony

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * HarmonyOS 7 only renders Immersive Light in navigation, dialogs and controls.
 * [Content] deliberately falls back to an opaque surface.
 */
enum class HarmonyGlassScope {
    Navigation,
    Dialog,
    Control,
    Content,
}

@Composable
fun ImmersiveGlass(
    scope: HarmonyGlassScope,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(HarmonyTokens.Radius.panel),
    strength: Float = 1f,
    content: @Composable () -> Unit,
) {
    val dark = HarmonyTheme.isDark
    val palette = HarmonyTheme.palette
    val immersive = scope != HarmonyGlassScope.Content && strength > 0f
    val alpha = strength.coerceIn(0f, 1f)

    val surface = if (immersive) {
        Color.White.copy(alpha = (if (dark) 0.08f else 0.62f) * alpha)
    } else {
        if (dark) palette.surfaceDark else palette.surfaceLight
    }
    val highlight = if (immersive) {
        Color.White.copy(alpha = (if (dark) 0.10f else 0.45f) * alpha)
    } else {
        surface
    }
    val border = if (immersive) {
        Color.White.copy(alpha = (if (dark) 0.16f else 0.80f) * alpha)
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
    }
    val elevation = if (immersive) HarmonyTokens.Elevation.medium else HarmonyTokens.Elevation.low

    Box(
        modifier
            .shadow(elevation, shape, clip = false)
            .background(brush = Brush.verticalGradient(listOf(highlight, surface)), shape = shape)
            .border(1.dp, border, shape)
            .clip(shape),
    ) {
        content()
    }
}

/**
 * Compose has no backdrop-filter, so the frosted look comes from blurring this
 * decorative layer rather than blurring arbitrary content above it.
 */
@Composable
fun GlassBackdrop(modifier: Modifier = Modifier) {
    val dark = HarmonyTheme.isDark
    val palette = HarmonyTheme.palette
    val glow = HarmonyTheme.glow
    val backdropBlur = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.blur(40.dp) else Modifier

    Box(
        modifier
            .clipToBounds()
            .background(if (dark) palette.backgroundDark else palette.backgroundLight)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .then(backdropBlur)
                .background(
                    Brush.radialGradient(
                        colors = listOf(glow.copy(alpha = 0.50f), Color.Transparent),
                        center = Offset(0.2f, 0.12f),
                        radius = 900f,
                    )
                )
        )
        Box(
            Modifier
                .fillMaxSize()
                .then(backdropBlur)
                .background(
                    Brush.radialGradient(
                        colors = listOf(palette.secondary.copy(alpha = 0.32f), Color.Transparent),
                        center = Offset(0.9f, 0.55f),
                        radius = 760f,
                    )
                )
        )
    }
}
