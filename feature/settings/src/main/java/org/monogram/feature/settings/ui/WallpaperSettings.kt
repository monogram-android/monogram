package org.monogram.feature.settings.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.LocalActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import org.monogram.core.ui.AppearanceSettings
import org.monogram.core.ui.WallpaperMode
import org.monogram.core.ui.theme.scaledToMessageSize
import org.monogram.core.ui.components.ChatWallpaper
import org.monogram.core.ui.components.ItemPosition
import org.monogram.core.ui.components.SettingsTile as SettingsRow
import org.monogram.feature.settings.R
import org.monogram.feature.settings.SettingsComponent
import org.monogram.feature.settings.importWallpaper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.monogram.core.ui.loading.MonogramLinearProgress
import org.monogram.core.ui.loading.MonogramLoading
import org.monogram.core.ui.loading.MonogramLoadingListSize
import org.monogram.core.ui.loading.MonogramBusyButton
import org.monogram.core.ui.loading.MonogramStateSwap
import androidx.compose.foundation.background
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import org.monogram.core.ui.components.SectionHeader
import org.monogram.core.ui.components.SettingsCard
import org.monogram.core.ui.components.SettingsTile
import androidx.compose.material3.SheetValue

@Composable
internal fun WallpaperPreview(
    path: String?,
    dim: Float,
    mode: WallpaperMode,
    modifier: Modifier = Modifier,
    messageTextSize: Int = AppearanceSettings.DEFAULT_MESSAGE_TEXT_SIZE,
    lineSpacing: Float = AppearanceSettings.DEFAULT_LINE_SPACING,
    letterSpacing: Float = AppearanceSettings.DEFAULT_LETTER_SPACING,
) {
    val bubbleStyle = MaterialTheme.typography.bodyLarge.scaledToMessageSize(
        messageTextSize,
        lineSpacing,
        letterSpacing,
    )
    Box(modifier.clip(MaterialTheme.shapes.extraLarge)) {
        ChatWallpaper(path, dim, mode, Modifier.matchParentSize())
        // Scrim so the bubbles stay legible over a faint pattern instead of blending into it.
        Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.surface.copy(alpha = 0.32f)))
        Column(
            Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(0.82f),
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                shape = MaterialTheme.shapes.extraLarge,
                shadowElevation = 1.dp,
            ) {
                Text(
                    text = stringResource(R.string.settings_wallpaper_sample_in),
                    modifier = Modifier.padding(12.dp),
                    style = bubbleStyle,
                )
            }
            Surface(
                modifier = Modifier.align(Alignment.End),
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = MaterialTheme.shapes.medium,
                shadowElevation = 1.dp,
            ) {
                Text(
                    text = stringResource(R.string.settings_wallpaper_sample_out),
                    modifier = Modifier.padding(12.dp),
                    style = bubbleStyle,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WallpaperSettings(component: SettingsComponent, modifier: Modifier = Modifier) {
    val current by AppearanceSettings.state.collectAsState()
    var path by rememberSaveable { mutableStateOf(current.wallpaperPath) }
    var mode by rememberSaveable { mutableStateOf(current.wallpaperMode) }
    val activity = LocalActivity.current
    DisposableEffect(path, activity) {
        val candidate = path
        onDispose {
            if (activity?.isChangingConfigurations != true && candidate != null &&
                candidate != AppearanceSettings.state.value.wallpaperPath) {
                java.io.File(candidate).delete()
            }
        }
    }
    var dim by rememberSaveable { mutableFloatStateOf(current.wallpaperDim) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var telegram by rememberSaveable { mutableStateOf(false) }
    var applied by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    fun select(uri: Uri) {
        scope.launch {
            busy = true
            error = false
            applied = false
            try {
                path = importWallpaper(context, uri)
                mode = WallpaperMode.Image
            }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { error = true }
            finally { busy = false }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(::select)
    }
    // The imported candidate is deleted when the picker is disposed, but its state is restored
    // when the page is revisited, so a restored selection must not point at a missing file.
    val previewPath = remember(path) { path?.takeIf { java.io.File(it).isFile } }
    val snackbar = remember { SnackbarHostState() }
    val appliedAll = stringResource(R.string.settings_wallpaper_applied_all)
    Box(modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .wrapContentWidth(Alignment.CenterHorizontally)
                .widthIn(max = 720.dp)
                .imePadding(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                WallpaperPreview(
                    previewPath,
                    dim,
                    mode,
                    Modifier.fillMaxWidth().heightIn(min = 220.dp),
                    messageTextSize = current.messageTextSize,
                    lineSpacing = current.lineSpacing,
                    letterSpacing = current.letterSpacing,
                )
            }

            item {
                SectionHeader(
                    text = stringResource(R.string.settings_wallpaper_sources),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                NavigationRow(
                    icon = Icons.Outlined.Image,
                    title = stringResource(R.string.settings_wallpaper_device),
                    enabled = !busy,
                    position = ItemPosition.TOP,
                    onClick = { picker.launch(arrayOf("image/*")) },
                )
                NavigationRow(
                    icon = Icons.Outlined.CloudDownload,
                    title = stringResource(R.string.settings_wallpaper_telegram),
                    enabled = !busy,
                    position = ItemPosition.BOTTOM,
                    onClick = { telegram = true },
                )
            }

            item { Spacer(Modifier.height(4.dp)) }
            item {
                if (previewPath != null) {
                    SettingsTile(
                        icon = Icons.Outlined.Image,
                        title = stringResource(R.string.settings_wallpaper_selected),
                        iconColor = MaterialTheme.colorScheme.primary,
                        position = ItemPosition.TOP,
                        enabled = !busy,
                        selected = mode == WallpaperMode.Image,
                        onClick = { mode = WallpaperMode.Image },
                    )
                }
                SettingsTile(
                    icon = Icons.Outlined.RestartAlt,
                    title = stringResource(R.string.settings_wallpaper_reset),
                    iconColor = MaterialTheme.colorScheme.tertiary,
                    position = if (previewPath == null) ItemPosition.TOP else ItemPosition.MIDDLE,
                    enabled = !busy,
                    selected = mode == WallpaperMode.Monogram,
                    onClick = { mode = WallpaperMode.Monogram },
                )
                SettingsTile(
                    icon = Icons.Outlined.HideImage,
                    title = stringResource(R.string.settings_wallpaper_none),
                    iconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    position = ItemPosition.BOTTOM,
                    enabled = !busy,
                    selected = mode == WallpaperMode.None,
                    onClick = { mode = WallpaperMode.None },
                )
            }

            if (mode == WallpaperMode.Image) item {
                SettingsCard(position = ItemPosition.STANDALONE) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(
                            text = stringResource(R.string.settings_wallpaper_dim, (dim * 100).toInt()),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Slider(
                            value = dim,
                            onValueChange = { dim = it },
                            valueRange = 0f..0.8f,
                            enabled = !busy,
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(4.dp)) }
            item {
                MonogramLinearProgress(
                    visible = busy,
                    modifier = Modifier.fillMaxWidth(),
                    height = 3.dp,
                )
                if (error) {
                    Text(
                        text = stringResource(R.string.settings_wallpaper_error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
        Surface(color = MaterialTheme.colorScheme.surface) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
            ) {
                MonogramBusyButton(
                    onClick = {
                        AppearanceSettings.setWallpaper(previewPath, dim, mode)
                        scope.launch { snackbar.showSnackbar(appliedAll) }
                    },
                    busy = busy,
                    enabled = mode != WallpaperMode.Image || previewPath != null,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge,
                ) {
                    Text(stringResource(R.string.settings_wallpaper_apply))
                }
            }
        }
    }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
    if (telegram) TelegramWallpapers(component, onDismiss = { telegram = false }, onSelect = { file ->
        telegram = false
        dim = 0f
        select(Uri.fromFile(file))
    })
}

@Composable
private fun NavigationRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    enabled: Boolean,
    position: ItemPosition,
    onClick: () -> Unit,
) {
    SettingsTile(
        icon = icon,
        title = title,
        iconColor = MaterialTheme.colorScheme.primary,
        position = position,
        onClick = onClick,
        enabled = enabled,
        trailingContent = {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TelegramWallpapers(component: SettingsComponent, onDismiss: () -> Unit, onSelect: (java.io.File) -> Unit) {
    val state by component.wallpapers.collectAsState()
    val context = LocalContext.current
    DisposableEffect(component) {
        component.onOpenWallpapers(context.cacheDir)
        onDispose { component.onCloseWallpapers() }
    }
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.9f).padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.settings_wallpaper_telegram),
                    style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, stringResource(R.string.settings_back)) }
            }
            MonogramLinearProgress(
                visible = state.loading,
                modifier = Modifier.fillMaxWidth(),
                height = 3.dp,
            )
            if (state.error) {
                Text(stringResource(R.string.settings_wallpaper_catalog_error), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = component::onRetryWallpapers) { Text(stringResource(R.string.settings_wallpaper_retry)) }
            }
            if (!state.loading && !state.error && state.wallpapers.isEmpty()) {
                Text(stringResource(R.string.settings_wallpaper_empty), Modifier.padding(vertical = 24.dp))
            }
            LazyVerticalGrid(columns = GridCells.Adaptive(104.dp), modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 24.dp)) {
                itemsIndexed(state.wallpapers, key = { _, wallpaper -> wallpaper.id }) { index, wallpaper ->
                    val file = state.previews[wallpaper.id]
                    val failed = wallpaper.id in state.failedPreviews
                    LaunchedEffect(wallpaper.id) { component.onPreviewWallpaper(wallpaper) }
                    val label = stringResource(R.string.settings_wallpaper_item, index + 1)
                    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth().aspectRatio(.58f)
                            .clip(MaterialTheme.shapes.medium)
                            .clickable(enabled = file != null, onClickLabel = label) { file?.let(onSelect) }) {
                        Box(contentAlignment = Alignment.Center) {
                            MonogramStateSwap(
                                state = when {
                                    file != null -> "ready"
                                    failed -> "failed"
                                    else -> "loading"
                                },
                                label = "wallpaperPreview",
                            ) { previewState -> when (previewState) {
                                "ready" -> AsyncImage(file, label,
                                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                "failed" -> IconButton(onClick = { component.onPreviewWallpaper(wallpaper) }) {
                                    Icon(Icons.Outlined.Refresh, stringResource(R.string.settings_wallpaper_retry))
                                }
                                else -> MonogramLoading(size = MonogramLoadingListSize)
                            } }
                        }
                    }
                }
            }
        }
    }
}
