package org.monogram.app

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import org.monogram.app.ui.theme.MonoGramTheme
import org.monogram.presentation.core.util.AppPreferences
import org.monogram.presentation.core.util.NightMode
import java.util.*

@Composable
fun AppThemeContainer(
    appPreferences: AppPreferences,
    content: @Composable () -> Unit
) {
    val nightMode by appPreferences.nightMode.collectAsState()
    val isDynamicColorsEnabled by appPreferences.isDynamicColorsEnabled.collectAsState()
    val startTimeStr by appPreferences.nightModeStartTime.collectAsState()
    val endTimeStr by appPreferences.nightModeEndTime.collectAsState()
    val brightnessThreshold by appPreferences.nightModeBrightnessThreshold.collectAsState()

    val systemDark = isSystemInDarkTheme()
    var screenBrightness by remember { mutableFloatStateOf(1f) }
    val context = LocalContext.current

    DisposableEffect(nightMode) {
        if (nightMode == NightMode.BRIGHTNESS) {
            val contentResolver = context.contentResolver
            val listener = object :
                ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    val brightness =
                        Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, 255)
                    screenBrightness = brightness / 255f
                }
            }
            contentResolver.registerContentObserver(
                Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS),
                false,
                listener
            )
            // Initial value
            val brightness = Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, 255)
            screenBrightness = brightness / 255f

            onDispose {
                contentResolver.unregisterContentObserver(listener)
            }
        } else {
            onDispose {}
        }
    }

    val darkTheme = when (nightMode) {
        NightMode.SYSTEM -> systemDark
        NightMode.LIGHT -> false
        NightMode.DARK -> true
        NightMode.SCHEDULED -> {
            val calendar = Calendar.getInstance()
            val nowHour = calendar.get(Calendar.HOUR_OF_DAY)
            val nowMinute = calendar.get(Calendar.MINUTE)
            val now = nowHour * 60 + nowMinute

            val startParts = startTimeStr.split(":")
            val start = if (startParts.size == 2) startParts[0].toInt() * 60 + startParts[1].toInt() else 22 * 60

            val endParts = endTimeStr.split(":")
            val end = if (endParts.size == 2) endParts[0].toInt() * 60 + endParts[1].toInt() else 7 * 60

            if (start < end) {
                now in start until end
            } else {
                now >= start || now < end
            }
        }

        NightMode.BRIGHTNESS -> {
            screenBrightness <= brightnessThreshold
        }
    }

    MonoGramTheme(
        darkTheme = darkTheme,
        dynamicColor = isDynamicColorsEnabled
    ) {
        content()
    }
}
