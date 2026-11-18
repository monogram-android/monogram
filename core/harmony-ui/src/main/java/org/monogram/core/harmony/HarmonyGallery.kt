package org.monogram.core.harmony

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Brightness4
import androidx.compose.material.icons.filled.Brightness7
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

private enum class GalleryScreen {
    Chats,
    Dialog,
    Widgets,
}

@Composable
fun HarmonyGallery() {
    val systemDark = isSystemInDarkTheme()
    var dark by remember { mutableStateOf(systemDark) }
    var accent by remember { mutableStateOf(HarmonyAccent.HarmonyBlue) }
    var screen by remember { mutableStateOf(GalleryScreen.Chats) }

    HarmonyTheme(darkTheme = dark, accent = accent) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            GlassBackdrop(Modifier.fillMaxSize())
            Column(Modifier.fillMaxSize()) {
                GalleryTopBar(
                    dark = dark,
                    accent = accent,
                    onDarkChange = { dark = it },
                    onAccentChange = { accent = it },
                )
                Box(Modifier.weight(1f)) {
                    when (screen) {
                        GalleryScreen.Chats -> ChatsScreen()
                        GalleryScreen.Dialog -> DialogScreen()
                        GalleryScreen.Widgets -> WidgetsScreen()
                    }
                }
                GalleryBottomBar(screen) { screen = it }
            }
        }
    }
}

@Composable
private fun GalleryTopBar(
    dark: Boolean,
    accent: HarmonyAccent,
    onDarkChange: (Boolean) -> Unit,
    onAccentChange: (HarmonyAccent) -> Unit,
) {
    ImmersiveGlass(
        scope = HarmonyGlassScope.Navigation,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = HarmonyTokens.Spacing.lg, vertical = HarmonyTokens.Spacing.sm),
        shape = RoundedCornerShape(HarmonyTokens.Radius.capsule),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(HarmonyTokens.Spacing.md, HarmonyTokens.Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.harmony_gallery_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    stringResource(R.string.harmony_preview),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { onDarkChange(!dark) }) {
                Icon(
                    if (dark) Icons.Filled.Brightness7 else Icons.Filled.Brightness4,
                    contentDescription = stringResource(
                        if (dark) R.string.harmony_theme_light else R.string.harmony_theme_dark,
                    ),
                )
            }
            IconButton(
                onClick = {
                    onAccentChange(
                        if (accent == HarmonyAccent.HarmonyBlue) HarmonyAccent.DnaNeon
                        else HarmonyAccent.HarmonyBlue,
                    )
                },
            ) {
                Icon(
                    Icons.Filled.AutoAwesome,
                    contentDescription = stringResource(R.string.harmony_accent),
                    tint = HarmonyTheme.palette.primary,
                )
            }
        }
    }
}

@Composable
private fun GalleryBottomBar(screen: GalleryScreen, onSelect: (GalleryScreen) -> Unit) {
    ImmersiveGlass(
        scope = HarmonyGlassScope.Navigation,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = HarmonyTokens.Spacing.lg, vertical = HarmonyTokens.Spacing.sm),
        shape = RoundedCornerShape(HarmonyTokens.Radius.capsule),
    ) {
        Row(Modifier.fillMaxWidth().padding(HarmonyTokens.Spacing.sm)) {
            GalleryTab(
                icon = Icons.AutoMirrored.Filled.Chat,
                label = stringResource(R.string.harmony_tab_chats),
                selected = screen == GalleryScreen.Chats,
                modifier = Modifier.weight(1f),
                onClick = { onSelect(GalleryScreen.Chats) },
            )
            GalleryTab(
                icon = Icons.AutoMirrored.Filled.Send,
                label = stringResource(R.string.harmony_tab_dialog),
                selected = screen == GalleryScreen.Dialog,
                modifier = Modifier.weight(1f),
                onClick = { onSelect(GalleryScreen.Dialog) },
            )
            GalleryTab(
                icon = Icons.Filled.Settings,
                label = stringResource(R.string.harmony_tab_widgets),
                selected = screen == GalleryScreen.Widgets,
                modifier = Modifier.weight(1f),
                onClick = { onSelect(GalleryScreen.Widgets) },
            )
        }
    }
}

@Composable
private fun GalleryTab(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val accent = HarmonyTheme.palette.primary
    Column(
        modifier
            .clip(RoundedCornerShape(HarmonyTokens.Radius.card))
            .clickable(onClick = onClick)
            .padding(vertical = HarmonyTokens.Spacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(HarmonyTokens.Size.controlHeight)
                .clip(CircleShape)
                .background(if (selected) accent.copy(alpha = 0.20f) else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ChatsScreen() {
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
        SpatialHeader(
            scroll = scroll.value.toFloat(),
            title = stringResource(R.string.harmony_greeting),
            subtitle = stringResource(R.string.harmony_greeting_subtitle),
        )
        Spacer(Modifier.height(HarmonyTokens.Spacing.sm))
        ImmersiveGlass(
            scope = HarmonyGlassScope.Control,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = HarmonyTokens.Spacing.lg),
            shape = RoundedCornerShape(HarmonyTokens.Radius.capsule),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(HarmonyTokens.Spacing.md, HarmonyTokens.Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = stringResource(R.string.harmony_search),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(HarmonyTokens.Spacing.md))
                Text(
                    stringResource(R.string.harmony_search),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(HarmonyTokens.Spacing.lg))
        ServiceCard(
            title = stringResource(R.string.harmony_widget_messages),
            subtitle = stringResource(R.string.harmony_widget_messages_sub),
            glyph = Icons.AutoMirrored.Filled.Chat,
            tint = HarmonyTheme.palette.primary,
            badge = stringResource(R.string.harmony_unread),
            modifier = Modifier.padding(horizontal = HarmonyTokens.Spacing.lg),
        )
        Spacer(Modifier.height(HarmonyTokens.Spacing.lg))
        ChatCard(
            title = stringResource(R.string.harmony_chat_1),
            preview = stringResource(R.string.harmony_chat_1_preview),
            time = stringResource(R.string.harmony_time),
            unread = stringResource(R.string.harmony_unread),
            modifier = Modifier.padding(horizontal = HarmonyTokens.Spacing.lg),
        )
        Spacer(Modifier.height(HarmonyTokens.Spacing.sm))
        ChatCard(
            title = stringResource(R.string.harmony_chat_2),
            preview = stringResource(R.string.harmony_chat_2_preview),
            time = stringResource(R.string.harmony_time),
            modifier = Modifier.padding(horizontal = HarmonyTokens.Spacing.lg),
        )
        Spacer(Modifier.height(HarmonyTokens.Spacing.sm))
        ChatCard(
            title = stringResource(R.string.harmony_chat_3),
            preview = stringResource(R.string.harmony_chat_3_preview),
            time = stringResource(R.string.harmony_time),
            modifier = Modifier.padding(horizontal = HarmonyTokens.Spacing.lg),
        )
        Spacer(Modifier.height(HarmonyTokens.Spacing.sm))
        ChatCard(
            title = stringResource(R.string.harmony_chat_4),
            preview = stringResource(R.string.harmony_chat_4_preview),
            time = stringResource(R.string.harmony_time),
            modifier = Modifier.padding(horizontal = HarmonyTokens.Spacing.lg),
        )
        Spacer(Modifier.height(HarmonyTokens.Spacing.xxl))
    }
}

@Composable
private fun DialogScreen() {
    Column(Modifier.fillMaxSize()) {
        ImmersiveGlass(
            scope = HarmonyGlassScope.Navigation,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = HarmonyTokens.Spacing.lg, vertical = HarmonyTokens.Spacing.sm),
            shape = RoundedCornerShape(HarmonyTokens.Radius.capsule),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(HarmonyTokens.Spacing.md, HarmonyTokens.Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                Spacer(Modifier.width(HarmonyTokens.Spacing.md))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.harmony_dialog_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        stringResource(R.string.harmony_dialog_status),
                        style = MaterialTheme.typography.labelSmall,
                        color = HarmonyTheme.palette.primary,
                    )
                }
            }
        }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .fillMaxWidth()
                .padding(HarmonyTokens.Spacing.lg),
            verticalArrangement = Arrangement.Bottom,
        ) {
            MessageLine(stringResource(R.string.harmony_message_in), outgoing = false)
            MessageLine(stringResource(R.string.harmony_message_out), outgoing = true)
            MessageLine(stringResource(R.string.harmony_message_reply), outgoing = false)
        }
        ImmersiveGlass(
            scope = HarmonyGlassScope.Control,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = HarmonyTokens.Spacing.lg, vertical = HarmonyTokens.Spacing.sm),
            shape = RoundedCornerShape(HarmonyTokens.Radius.capsule),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(HarmonyTokens.Spacing.sm, HarmonyTokens.Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.harmony_type_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).padding(horizontal = HarmonyTokens.Spacing.md),
                )
                IconButton(onClick = {}) {
                    Icon(Icons.Filled.Mic, contentDescription = stringResource(R.string.harmony_type_message))
                }
                IconButton(onClick = {}) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringResource(R.string.harmony_send),
                        tint = HarmonyTheme.palette.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageLine(text: String, outgoing: Boolean) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start,
    ) {
        MessageBubble(text, outgoing, Modifier.widthIn(max = 280.dp))
    }
}

@Composable
private fun WidgetsScreen() {
    var immersive by remember { mutableStateOf(true) }
    var spatial by remember { mutableStateOf(true) }
    var intensity by remember { mutableStateOf(0.65f) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(HarmonyTokens.Spacing.lg),
    ) {
        Text(
            stringResource(R.string.harmony_widgets_title),
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(HarmonyTokens.Spacing.lg))
        Row {
            ServiceCard(
                title = stringResource(R.string.harmony_widget_calls),
                subtitle = stringResource(R.string.harmony_widget_calls_sub),
                glyph = Icons.Filled.Call,
                tint = HarmonyTheme.palette.secondary,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(HarmonyTokens.Spacing.sm))
            ServiceCard(
                title = stringResource(R.string.harmony_widget_files),
                subtitle = stringResource(R.string.harmony_widget_files_sub),
                glyph = Icons.Filled.Folder,
                tint = HarmonyTheme.palette.primary,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(HarmonyTokens.Spacing.sm))
        Row {
            ServiceCard(
                title = stringResource(R.string.harmony_widget_media),
                subtitle = stringResource(R.string.harmony_widget_media_sub),
                glyph = Icons.Filled.Image,
                tint = HarmonyTheme.palette.secondary,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(HarmonyTokens.Spacing.sm))
            ServiceCard(
                title = stringResource(R.string.harmony_widget_messages),
                subtitle = stringResource(R.string.harmony_widget_messages_sub),
                glyph = Icons.AutoMirrored.Filled.Chat,
                tint = HarmonyTheme.palette.primary,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(HarmonyTokens.Spacing.xl))
        Text(
            stringResource(R.string.harmony_appearance),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(HarmonyTokens.Spacing.md))
        SettingSwitchCard(
            label = stringResource(R.string.harmony_immersive_light),
            checked = immersive,
            onCheckedChange = { immersive = it },
        )
        Spacer(Modifier.height(HarmonyTokens.Spacing.sm))
        SettingSwitchCard(
            label = stringResource(R.string.harmony_spatial_depth),
            checked = spatial,
            onCheckedChange = { spatial = it },
        )
        Spacer(Modifier.height(HarmonyTokens.Spacing.sm))
        ImmersiveGlass(
            scope = HarmonyGlassScope.Control,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(HarmonyTokens.Radius.card),
            strength = if (immersive) 1f else 0f,
        ) {
            Column(Modifier.fillMaxWidth().padding(HarmonyTokens.Spacing.lg)) {
                Text(
                    stringResource(R.string.harmony_accent),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Slider(value = intensity, onValueChange = { intensity = it })
            }
        }
        Spacer(Modifier.height(HarmonyTokens.Spacing.lg))
        DepthBox(depth = if (spatial) 2 else 0) {
            ServiceCard(
                title = stringResource(R.string.harmony_gallery_title),
                subtitle = stringResource(R.string.harmony_preview),
                glyph = Icons.Filled.AutoAwesome,
                tint = HarmonyTheme.palette.primary,
            )
        }
        Spacer(Modifier.height(HarmonyTokens.Spacing.xxl))
    }
}

@Preview(name = "Harmony light", showBackground = true, widthDp = 420, heightDp = 900)
@Composable
private fun HarmonyGalleryLightPreview() {
    HarmonyGallery()
}

@Preview(
    name = "Harmony dark",
    showBackground = true,
    widthDp = 420,
    heightDp = 900,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun HarmonyGalleryDarkPreview() {
    HarmonyGallery()
}
