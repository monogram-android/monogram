package org.monogram.core.ui.media

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/** Product motion scheme. Set to false for the Standard scheme (no overshoot). */
val LocalMediaViewerMotion = staticCompositionLocalOf { true }

/**
 * Motion is enabled only when the product asked for the Expressive scheme AND the
 * platform has not turned animations off (Developer options / accessibility).
 */
@Composable
fun mediaViewerMotionEnabled(): Boolean {
    if (!LocalMediaViewerMotion.current) return false
    val context = LocalContext.current
    return remember(context) { animationsAllowed(context) }
}

private fun animationsAllowed(context: Context): Boolean = runCatching {
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
}.getOrDefault(true)

/** Expressive vs. reduced-motion spring pairs. */
object MediaMotion {
    fun <T> spatial(motion: Boolean): androidx.compose.animation.core.FiniteAnimationSpec<T> =
        if (motion) spring(dampingRatio = 0.6f, stiffness = 800f)
        else spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)

    /** Fast transition spec for small UI state changes like thumbnail selection. */
    fun <T> quick(motion: Boolean): androidx.compose.animation.core.FiniteAnimationSpec<T> =
        if (motion) androidx.compose.animation.core.tween(durationMillis = 180)
        else androidx.compose.animation.core.snap()

    fun <T> effects(motion: Boolean): androidx.compose.animation.core.FiniteAnimationSpec<T> =
        if (motion) androidx.compose.animation.core.tween(durationMillis = 220)
        else androidx.compose.animation.core.snap()
}

/** Per-chat playback preferences: speed and mute survive leaving the viewer. */
object MediaViewerPrefs {
    private const val FILE = "monogram_media_viewer"

    fun speed(context: Context, chatKey: String): Float = prefs(context).getFloat("speed_$chatKey", 1f)

    fun setSpeed(context: Context, chatKey: String, value: Float) {
        prefs(context).edit().putFloat("speed_$chatKey", value).apply()
    }

    fun muted(context: Context, chatKey: String): Boolean = prefs(context).getBoolean("muted_$chatKey", false)

    fun setMuted(context: Context, chatKey: String, value: Boolean) {
        prefs(context).edit().putBoolean("muted_$chatKey", value).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
