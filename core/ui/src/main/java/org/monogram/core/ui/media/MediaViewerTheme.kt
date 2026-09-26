package org.monogram.core.ui.media

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color

/**
 * Token table for the media viewer.
 *
 * The viewer keeps its own dark chrome regardless of the app theme, because media
 * contrast must not depend on the user's light/dark preference. The letterbox stays
 * AMOLED-deep but is never flat #000: the chrome above it carries the surfaces.
 */
object MediaViewerTokens {
    /** Letterbox behind photos and video. Deep, but a real surface, not raw black. */
    val Letterbox = Color(0xFF07080A)

    /** Scrim over media for the collapsed caption and drag state. */
    val Scrim = Color(0xB3000000)

    val Chrome = Color(0xFF0B1116)
    val ChromeLow = Color(0xFF11181D)
    val ChromeHigh = Color(0xFF171E24)
    val ChromeHighest = Color(0xFF1C242B)
    val ChromeTop = Color(0xFF222A32)
    val OnChrome = Color(0xFFDEE3E8)
    val OnChromeVariant = Color(0xFFBFC8CF)
    val Outline = Color(0xFF3F484E)

    /** Pale Telegram chrome kept on the dark sheet: avatar, chips, tonal actions. */
    val PaleChrome = Color(0xFFC8E6FF)
    val OnPaleChrome = Color(0xFF001E2E)
}

@Composable
fun mediaViewerColorScheme(): ColorScheme {
    val base = MaterialTheme.colorScheme
    return remember(base) {
        base.copy(
            surface = MediaViewerTokens.Chrome,
            surfaceContainerLowest = MediaViewerTokens.Letterbox,
            surfaceContainerLow = MediaViewerTokens.ChromeLow,
            surfaceContainer = MediaViewerTokens.ChromeHigh,
            surfaceContainerHigh = MediaViewerTokens.ChromeHighest,
            surfaceContainerHighest = MediaViewerTokens.ChromeTop,
            onSurface = MediaViewerTokens.OnChrome,
            onSurfaceVariant = MediaViewerTokens.OnChromeVariant,
            surfaceVariant = MediaViewerTokens.ChromeHighest,
            outline = MediaViewerTokens.Outline,
            outlineVariant = MediaViewerTokens.Outline,
            primaryContainer = MediaViewerTokens.PaleChrome,
            onPrimaryContainer = MediaViewerTokens.OnPaleChrome,
            // Dark chrome whatever the app theme is, so un-paled tonal parts stay dark too.
            secondary = MediaViewerTokens.OnChromeVariant,
            onSecondary = MediaViewerTokens.Chrome,
            secondaryContainer = MediaViewerTokens.ChromeHighest,
            onSecondaryContainer = MediaViewerTokens.OnChrome,
            inverseSurface = MediaViewerTokens.OnChrome,
            inverseOnSurface = MediaViewerTokens.ChromeHighest,
            scrim = MediaViewerTokens.Scrim,
        )
    }
}

/**
 * Applies the viewer's dark chrome while keeping the app's accent for primary. Movement is
 * [MotionScheme.expressive]; the shell still paces its own transitions by reduced motion.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MediaViewerTheme(content: @Composable () -> Unit) {
    val motionScheme = remember { MotionScheme.expressive() }
    MaterialTheme(
        colorScheme = mediaViewerColorScheme(),
        motionScheme = motionScheme,
        content = content,
    )
}

/**
 * Host-provided picture-in-picture bridge. The viewer lives in a dialog, which the
 * system does not composite in PiP, so the activity owns the PiP window and only the
 * session's surface moves there.
 */
interface PictureInPictureController {
    val supported: Boolean
    fun enter()
    fun updateActions(playing: Boolean, canSkipNext: Boolean)
}

val LocalPictureInPictureController = androidx.compose.runtime.staticCompositionLocalOf<PictureInPictureController?> { null }
