package org.monogram.presentation.features.webapp.components

import org.monogram.presentation.core.util.coRunCatching
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.os.Build
import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AddToHomeScreen
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import org.monogram.presentation.R
import org.monogram.presentation.features.stickers.ui.menu.MenuOptionRow
import org.monogram.presentation.features.viewers.components.ViewerSettingsDropdown

@Composable
fun MiniAppMenu(
    visible: Boolean,
    isFullscreen: Boolean,
    url: String,
    botUserId: Long,
    botName: String,
    botAvatarPath: String?,
    context: Context,
    clipboardManager: ClipboardManager,
    onDismiss: () -> Unit,
    onReload: () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(150)) + scaleIn(
            animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium),
            initialScale = 0.8f,
            transformOrigin = TransformOrigin(1f, 0f)
        ),
        exit = fadeOut(tween(150)) + scaleOut(
            animationSpec = tween(150),
            targetScale = 0.9f,
            transformOrigin = TransformOrigin(1f, 0f)
        ),
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(top = if (isFullscreen) 64.dp else 56.dp, end = 16.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                ),
            contentAlignment = Alignment.TopEnd
        ) {
            ViewerSettingsDropdown {
                MenuOptionRow(
                    icon = Icons.Rounded.Refresh,
                    title = stringResource(R.string.mini_app_menu_reload),
                    onClick = {
                        onReload()
                        onDismiss()
                    }
                )
                MenuOptionRow(
                    icon = Icons.Rounded.ContentCopy,
                    title = stringResource(R.string.mini_app_menu_copy_link),
                    onClick = {
                        clipboardManager.setText(AnnotatedString(url))
                        onDismiss()
                    }
                )
                MenuOptionRow(
                    icon = Icons.AutoMirrored.Rounded.OpenInNew,
                    title = stringResource(R.string.mini_app_menu_open_in_browser),
                    onClick = {
                        coRunCatching {
                            context.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    url.toUri()
                                )
                            )
                        }
                        onDismiss()
                    }
                )
                MenuOptionRow(
                    icon = Icons.Rounded.AddToHomeScreen,
                    title = stringResource(R.string.mini_app_menu_add_to_home),
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            val shortcutManager = context.getSystemService(ShortcutManager::class.java)
                            if (shortcutManager.isRequestPinShortcutSupported) {
                                val shortcutId = "webapp_$botUserId"
                                val intent = Intent(context, context.javaClass).apply {
                                    action = Intent.ACTION_VIEW
                                    data = "tg://resolve?domain=$botName&startapp=true".toUri()
                                }
                                val icon = if (botAvatarPath != null) {
                                    val bitmap = BitmapFactory.decodeFile(botAvatarPath)
                                    if (bitmap != null) Icon.createWithBitmap(bitmap) else Icon.createWithResource(
                                        context,
                                        R.drawable.outline_web_24
                                    )
                                } else {
                                    Icon.createWithResource(context, R.drawable.outline_web_24)
                                }
                                val pinShortcutInfo = ShortcutInfo.Builder(context, shortcutId)
                                    .setShortLabel(botName)
                                    .setIcon(icon)
                                    .setIntent(intent)
                                    .build()

                                shortcutManager.requestPinShortcut(pinShortcutInfo, null)
                            }
                        }
                        onDismiss()
                    }
                )
            }
        }
    }
}
