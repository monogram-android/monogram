package org.monogram.presentation.features.chats.currentChat.components.chats

import android.content.ClipData
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.koin.compose.koinInject
import org.monogram.presentation.core.util.AppPreferences
import org.monogram.presentation.core.util.NightMode
import org.monogram.presentation.features.chats.currentChat.components.chats.code.CodeHighlighter
import java.util.Calendar

@Composable
fun CodeBlock(
    text: String,
    language: String,
    isOutgoing: Boolean,
    modifier: Modifier = Modifier,
    appPreferences: AppPreferences = koinInject()
) {
    val localClipboard = LocalClipboard.current
    val context = LocalContext.current
    val backgroundColor = if (isOutgoing) {
        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.08f)
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f)
    }
    val contentColor = if (isOutgoing) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val headerColor = if (isOutgoing) {
        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.05f)
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.02f)
    }

    val highlighter = remember { CodeHighlighter() }
    val colorScheme = MaterialTheme.colorScheme

    val nightMode by appPreferences.nightMode.collectAsState()
    val isDynamicColorsEnabled by appPreferences.isDynamicColorsEnabled.collectAsState()
    val startTimeStr by appPreferences.nightModeStartTime.collectAsState()
    val endTimeStr by appPreferences.nightModeEndTime.collectAsState()
    val brightnessThreshold by appPreferences.nightModeBrightnessThreshold.collectAsState()

    val systemDark = isSystemInDarkTheme()
    var screenBrightness by remember { mutableFloatStateOf(1f) }

    if (nightMode == NightMode.BRIGHTNESS) {
        DisposableEffect(Unit) {
            val contentResolver = context.contentResolver
            val listener = object :
                android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
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
            val brightness = Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, 255)
            screenBrightness = brightness / 255f

            onDispose {
                contentResolver.unregisterContentObserver(listener)
            }
        }
    }

    val isDark = when (nightMode) {
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

        NightMode.BRIGHTNESS -> screenBrightness <= brightnessThreshold
    }

    Surface(
        modifier = modifier
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = backgroundColor,
        border = null
    ) {
        Column(modifier = Modifier.widthIn(min = 120.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(headerColor)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (language.isNotEmpty()) {
                    Text(
                        text = language,
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.5f),
                        fontWeight = FontWeight.Bold
                    )
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
                IconButton(
                    onClick = {
                        localClipboard.nativeClipboard.setPrimaryClip(
                            ClipData.newPlainText("", AnnotatedString(text))
                        )
                        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copy",
                        modifier = Modifier.size(14.dp),
                        tint = contentColor.copy(alpha = 0.7f)
                    )
                }
            }
            Box(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp)
            ) {
                Text(
                    text = highlighter.highlight(
                        text = text,
                        language = language,
                        isDark = isDark,
                        scheme = if (isDynamicColorsEnabled) colorScheme else null
                    ),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp
                    ),
                    softWrap = false
                )
            }
        }
    }
}
