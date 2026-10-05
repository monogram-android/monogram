package org.monogram.core.ui.media

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/** Viewer motion preference. False snaps transitions to their target state. */
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

object MediaMotion {
    @Composable
    fun <T> spatial(motion: Boolean): FiniteAnimationSpec<T> =
        if (motion) MaterialTheme.motionScheme.defaultSpatialSpec() else snap()

    @Composable
    fun <T> quick(motion: Boolean): FiniteAnimationSpec<T> = spatial(motion)

    @Composable
    fun <T> effects(motion: Boolean): FiniteAnimationSpec<T> =
        if (motion) MaterialTheme.motionScheme.defaultEffectsSpec() else snap()
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
