package org.monogram.presentation.features.webview.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.webkit.WebView
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.monogram.presentation.core.ui.ItemPosition
import org.monogram.presentation.core.ui.SettingsSwitchTile
import org.monogram.presentation.settings.sessions.SectionHeader

@Composable
fun OptionsSheet(
    webView: WebView?,
    currentUrl: String,
    canGoBack: Boolean,
    canGoForward: Boolean,
    isDesktopMode: Boolean,
    isAdBlockEnabled: Boolean,
    textZoom: Int,
    onDesktopModeChange: (Boolean) -> Unit,
    onAdBlockChange: (Boolean) -> Unit,
    onTextZoomChange: (Int) -> Unit,
    onFindInPage: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 48.dp)
    ) {
        Text(
            text = "Options",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                NavigationItem(
                    icon = Icons.AutoMirrored.Rounded.ArrowBack,
                    label = "Back",
                    enabled = canGoBack,
                    onClick = { webView?.goBack() }
                )
                NavigationItem(
                    icon = Icons.AutoMirrored.Rounded.ArrowForward,
                    label = "Forward",
                    enabled = canGoForward,
                    onClick = { webView?.goForward() }
                )
                NavigationItem(
                    icon = Icons.Rounded.Refresh,
                    label = "Refresh",
                    onClick = {
                        webView?.reload()
                        onDismiss()
                    }
                )
            }
        }

        SectionHeader("Actions")

        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                ActionItem(Icons.Rounded.ContentCopy, "Copy") {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("URL", currentUrl))
                    Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
                    onDismiss()
                }
                ActionItem(Icons.Rounded.Share, "Share") {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, currentUrl)
                    }
                    context.startActivity(Intent.createChooser(shareIntent, "Share link via"))
                    onDismiss()
                }
                ActionItem(Icons.AutoMirrored.Rounded.OpenInNew, "Browser") {
                    uriHandler.openUri(currentUrl)
                    onDismiss()
                }
                ActionItem(Icons.Rounded.Search, "Find") {
                    onDismiss()
                    onFindInPage()
                }
            }
        }

        SectionHeader("Settings")

        SettingsSwitchTile(
            icon = Icons.Rounded.DesktopWindows,
            title = "Desktop Site",
            checked = isDesktopMode,
            iconColor = MaterialTheme.colorScheme.primary,
            position = ItemPosition.TOP,
            onCheckedChange = onDesktopModeChange
        )

        SettingsSwitchTile(
            icon = Icons.Rounded.Block,
            title = "Block Ads",
            checked = isAdBlockEnabled,
            iconColor = MaterialTheme.colorScheme.primary,
            position = ItemPosition.MIDDLE,
            onCheckedChange = onAdBlockChange
        )

        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(
                bottomStart = 24.dp,
                bottomEnd = 24.dp,
                topStart = 4.dp,
                topEnd = 4.dp
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(
                                color = MaterialTheme.colorScheme.primary.copy(0.15f),
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.TextFormat, null, tint = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        "Text Size: $textZoom%",
                        style = MaterialTheme.typography.titleMedium,
                        fontSize = 18.sp
                    )
                }
                Slider(
                    value = textZoom.toFloat(),
                    onValueChange = { onTextZoomChange(it.toInt()) },
                    valueRange = 50f..200f,
                    steps = 14,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}
