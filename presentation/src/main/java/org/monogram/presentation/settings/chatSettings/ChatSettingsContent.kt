package org.monogram.presentation.settings.chatSettings

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.StickyNote2
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import org.monogram.presentation.R
import org.monogram.presentation.core.util.EmojiStyle
import org.monogram.presentation.core.util.NightMode
import org.monogram.presentation.features.chats.currentChat.components.chats.getEmojiFontFamily
import org.monogram.presentation.settings.chatSettings.components.ChatListPreview
import org.monogram.presentation.settings.chatSettings.components.ChatSettingsPreview
import org.monogram.presentation.settings.chatSettings.components.WallpaperItem
import org.monogram.presentation.core.ui.ItemPosition
import org.monogram.presentation.core.ui.SettingsSwitchTile
import org.monogram.presentation.core.ui.SettingsTile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatSettingsContent(component: ChatSettingsComponent) {
    val state by component.state.subscribeAsState()

    val blueColor = Color(0xFF4285F4)
    val greenColor = Color(0xFF34A853)
    val orangeColor = Color(0xFFF9AB00)
    val pinkColor = Color(0xFFFF6D66)
    val tealColor = Color(0xFF00BFA5)
    val purpleColor = Color(0xFF9C27B0)
    val redColor = Color(0xFFEA4335)

    var showStartTimePicker by remember { mutableStateOf(false) }
    var showEndTimePicker by remember { mutableStateOf(false) }
    var showThemeEditor by remember { mutableStateOf(false) }

    if (showThemeEditor) {
        ChatThemeEditorScreen(
            state = state,
            component = component,
            onBack = { showThemeEditor = false }
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.chat_settings_header),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                },
                navigationIcon = {
                    IconButton(onClick = component::onBackClicked) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 16.dp
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                ChatSettingsPreview(
                    wallpaper = state.wallpaper,
                    availableWallpapers = state.availableWallpapers,
                    fontSize = state.fontSize,
                    bubbleRadius = state.bubbleRadius,
                    isBlurred = state.isWallpaperBlurred,
                    isMoving = state.isWallpaperMoving,
                    blurIntensity = state.wallpaperBlurIntensity,
                    dimming = state.wallpaperDimming,
                    isGrayscale = state.isWallpaperGrayscale,
                    downloadUtils = component.downloadUtils,
                    videoPlayerPool = component.videoPlayerPool
                )
            }

            item {
                SectionHeader(stringResource(R.string.appearance_header))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .padding(16.dp)
                            .animateContentSize()
                    ) {
                        AppearanceSliderItem(
                            title = stringResource(R.string.message_text_size_title),
                            value = state.fontSize,
                            onValueChange = component::onFontSizeChanged,
                            valueRange = 12f..30f,
                            steps = 18,
                            onReset = { component.onFontSizeChanged(16f) },
                            valueSuffix = "sp",
                            startIcon = {
                                Text(
                                    text = "A",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.Medium
                                )
                            },
                            endIcon = {
                                Text(
                                    text = "A",
                                    fontSize = 22.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        )

                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 12.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )

                        AppearanceSliderItem(
                            title = stringResource(R.string.bubble_rounding_title),
                            value = state.bubbleRadius,
                            onValueChange = component::onBubbleRadiusChanged,
                            valueRange = 0f..30f,
                            steps = 30,
                            onReset = { component.onBubbleRadiusChanged(18f) },
                            valueSuffix = "dp",
                            startIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.Square,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            endIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.Circle,
                                    contentDescription = null,
                                    modifier = Modifier.size(22.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        )
                    }
                }
            }

            item {
                SectionHeader(stringResource(R.string.chat_wallpaper_header))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(16.dp)
                    ) {
                        item {
                            val isSelected = state.wallpaper == null

                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .padding(vertical = 4.dp)
                                    .clickable { component.onWallpaperChanged(null) }
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(80.dp, 120.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                        .then(
                                            if (isSelected) Modifier.background(
                                                MaterialTheme.colorScheme.primary.copy(
                                                    alpha = 0.2f
                                                )
                                            ) else Modifier
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = stringResource(R.string.reset_wallpaper_cd),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )

                                    (this@Column.AnimatedVisibility(
                                        visible = isSelected,
                                        enter = scaleIn() + fadeIn(),
                                        exit = scaleOut() + fadeOut()
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(32.dp)
                                                .background(MaterialTheme.colorScheme.primary, CircleShape),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Check,
                                                contentDescription = stringResource(R.string.chat_settings_selected),
                                                tint = MaterialTheme.colorScheme.onPrimary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    })
                                }
                            }
                        }

                        items(
                            state.availableWallpapers,
                            key = { it.id }) { wallpaper ->
                            val isSelected =
                                state.wallpaper != null && (state.wallpaper == wallpaper.localPath || state.wallpaper == wallpaper.slug)

                            WallpaperItem(
                                wallpaper = wallpaper,
                                isSelected = isSelected,
                                isBlurred = state.isWallpaperBlurred,
                                blurIntensity = state.wallpaperBlurIntensity,
                                isMoving = state.isWallpaperMoving,
                                dimming = state.wallpaperDimming,
                                isGrayscale = state.isWallpaperGrayscale,
                                onClick = { component.onWallpaperSelected(wallpaper) },
                                onBlurClick = { isBlurred ->
                                    if (!isSelected) {
                                        component.onWallpaperSelected(wallpaper)
                                    }
                                    component.onWallpaperBlurChanged(wallpaper, isBlurred)
                                },
                                onBlurIntensityChange = { intensity ->
                                    component.onWallpaperBlurIntensityChanged(intensity)
                                },
                                onMotionClick = { isMoving ->
                                    if (!isSelected) {
                                        component.onWallpaperSelected(wallpaper)
                                    }
                                    component.onWallpaperMotionChanged(wallpaper, isMoving)
                                },
                                onDimmingChange = { dimming ->
                                    if (!isSelected) {
                                        component.onWallpaperSelected(wallpaper)
                                    }
                                    component.onWallpaperDimmingChanged(dimming)
                                },
                                onGrayscaleClick = { isGrayscale ->
                                    if (!isSelected) {
                                        component.onWallpaperSelected(wallpaper)
                                    }
                                    component.onWallpaperGrayscaleChanged(isGrayscale)
                                }
                            )
                        }
                    }
                }
            }

            item {
                SectionHeader(stringResource(R.string.emoji_style_header))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(16.dp)
                    ) {
                        items(EmojiStyle.entries) { style ->
                            Box(modifier = Modifier.width(100.dp)) {
                                EmojiStyleItem(
                                    style = style,
                                    selected = state.emojiStyle == style,
                                    isDownloaded = when (style) {
                                        EmojiStyle.APPLE -> state.isAppleEmojiDownloaded
                                        EmojiStyle.TWITTER -> state.isTwitterEmojiDownloaded
                                        EmojiStyle.WINDOWS -> state.isWindowsEmojiDownloaded
                                        EmojiStyle.CATMOJI -> state.isCatmojiEmojiDownloaded
                                        EmojiStyle.NOTO -> state.isNotoEmojiDownloaded
                                        EmojiStyle.SYSTEM -> true
                                    },
                                    isDownloading = when (style) {
                                        EmojiStyle.APPLE -> state.isAppleEmojiDownloading
                                        EmojiStyle.TWITTER -> state.isTwitterEmojiDownloading
                                        EmojiStyle.WINDOWS -> state.isWindowsEmojiDownloading
                                        EmojiStyle.CATMOJI -> state.isCatmojiEmojiDownloading
                                        EmojiStyle.NOTO -> state.isNotoEmojiDownloading
                                        EmojiStyle.SYSTEM -> false
                                    },
                                    onClick = { component.onEmojiStyleChanged(style) },
                                    onLongClick = { component.onEmojiStyleLongClick(style) }
                                )
                            }
                        }
                    }
                }
            }

            item {
                SectionHeader(stringResource(R.string.theme_header))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .padding(16.dp)
                            .animateContentSize(animationSpec = spring(stiffness = Spring.StiffnessLow))
                    ) {
                        Text(
                            text = stringResource(R.string.night_mode_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(30.dp)
                                        .background(
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                                            CircleShape
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Tune,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                Column {
                                    Text(
                                        text = stringResource(R.string.night_mode_current_label),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = stringResource(
                                            R.string.night_mode_current_format,
                                            nightModeLabel(state.nightMode)
                                        ),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))

                        LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(NightMode.entries.toList()) { mode ->
                                ThemeModeItem(
                                    mode = mode,
                                    selected = state.nightMode == mode,
                                    onClick = { component.onNightModeChanged(mode) },
                                    modifier = Modifier.widthIn(min = 164.dp)
                                )
                            }
                        }

                        AnimatedContent(
                            targetState = state.nightMode,
                            transitionSpec = {
                                fadeIn(animationSpec = tween(400, easing = EaseOutQuint)) +
                                        slideInVertically(
                                            animationSpec = tween(
                                                400,
                                                easing = EaseOutQuint
                                            )
                                        ) { it / 2 } togetherWith
                                        fadeOut(animationSpec = tween(200, easing = EaseInQuint)) +
                                        slideOutVertically(animationSpec = tween(200, easing = EaseInQuint)) { it / 2 }
                            },
                            label = "NightModeSettings"
                        ) { mode ->
                            when (mode) {
                                NightMode.SCHEDULED -> {
                                    Column(modifier = Modifier.padding(top = 20.dp)) {
                                        HorizontalDivider(
                                            modifier = Modifier.padding(bottom = 20.dp),
                                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                        )
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            TimeSettingCard(
                                                label = stringResource(R.string.from_label),
                                                time = state.nightModeStartTime,
                                                icon = Icons.Rounded.LightMode,
                                                modifier = Modifier.weight(1f),
                                                onClick = { showStartTimePicker = true }
                                            )
                                            TimeSettingCard(
                                                label = stringResource(R.string.to_label),
                                                time = state.nightModeEndTime,
                                                icon = Icons.Rounded.DarkMode,
                                                modifier = Modifier.weight(1f),
                                                onClick = { showEndTimePicker = true }
                                            )
                                        }

                                        if (showStartTimePicker) {
                                            val parts = state.nightModeStartTime.split(":")
                                            val timePickerState = rememberTimePickerState(
                                                initialHour = parts.getOrNull(0)?.toIntOrNull() ?: 0,
                                                initialMinute = parts.getOrNull(1)?.toIntOrNull() ?: 0,
                                                is24Hour = true
                                            )
                                            TimePickerDialogWrapper(
                                                onDismissRequest = { showStartTimePicker = false },
                                                onConfirm = {
                                                    component.onNightModeStartTimeChanged(
                                                        String.format("%02d:%02d", timePickerState.hour, timePickerState.minute)
                                                    )
                                                    showStartTimePicker = false
                                                }
                                            ) {
                                                TimePicker(state = timePickerState)
                                            }
                                        }

                                        if (showEndTimePicker) {
                                            val parts = state.nightModeEndTime.split(":")
                                            val timePickerState = rememberTimePickerState(
                                                initialHour = parts.getOrNull(0)?.toIntOrNull() ?: 0,
                                                initialMinute = parts.getOrNull(1)?.toIntOrNull() ?: 0,
                                                is24Hour = true
                                            )
                                            TimePickerDialogWrapper(
                                                onDismissRequest = { showEndTimePicker = false },
                                                onConfirm = {
                                                    component.onNightModeEndTimeChanged(
                                                        String.format("%02d:%02d", timePickerState.hour, timePickerState.minute)
                                                    )
                                                    showEndTimePicker = false
                                                }
                                            ) {
                                                TimePicker(state = timePickerState)
                                            }
                                        }
                                    }
                                }

                                NightMode.BRIGHTNESS -> {
                                    Column(modifier = Modifier.padding(top = 20.dp)) {
                                        HorizontalDivider(
                                            modifier = Modifier.padding(bottom = 20.dp),
                                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                        )
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                Icons.Rounded.BrightnessLow,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Text(
                                                text = stringResource(
                                                    R.string.brightness_threshold_format,
                                                    (state.nightModeBrightnessThreshold * 100).toInt()
                                                ),
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }
                                        Slider(
                                            value = state.nightModeBrightnessThreshold,
                                            onValueChange = component::onNightModeBrightnessThresholdChanged,
                                            valueRange = 0f..1f,
                                            modifier = Modifier.padding(vertical = 8.dp)
                                        )
                                        Text(
                                            text = stringResource(R.string.brightness_threshold_description),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                else -> Spacer(modifier = Modifier.height(0.dp))
                            }
                        }

                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )

                        FilledTonalButton(
                            onClick = { showThemeEditor = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Palette,
                                contentDescription = null
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.chat_settings_edit_custom_theme))
                        }
                    }
                }
            }

            item {
                SectionHeader(stringResource(R.string.data_storage_header))
                SettingsSwitchTile(
                    icon = Icons.Rounded.Photo,
                    title = stringResource(R.string.compress_photos_title),
                    subtitle = stringResource(R.string.compress_photos_subtitle),
                    checked = state.compressPhotos,
                    iconColor = blueColor,
                    position = ItemPosition.TOP,
                    onCheckedChange = component::onCompressPhotosChanged
                )
                SettingsSwitchTile(
                    icon = Icons.Rounded.VideoFile,
                    title = stringResource(R.string.compress_videos_title),
                    subtitle = stringResource(R.string.compress_videos_subtitle),
                    checked = state.compressVideos,
                    iconColor = greenColor,
                    position = ItemPosition.BOTTOM,
                    onCheckedChange = component::onCompressVideosChanged
                )
            }

            item {
                SectionHeader(stringResource(R.string.video_player_header))
                SettingsSwitchTile(
                    icon = Icons.Rounded.Gesture,
                    title = stringResource(R.string.enable_gestures_title),
                    subtitle = stringResource(R.string.enable_gestures_subtitle),
                    checked = state.isPlayerGesturesEnabled,
                    iconColor = blueColor,
                    position = ItemPosition.TOP,
                    onCheckedChange = component::onPlayerGesturesEnabledChanged
                )
                SettingsSwitchTile(
                    icon = Icons.Rounded.Forward10,
                    title = stringResource(R.string.double_tap_seek_title),
                    subtitle = stringResource(R.string.double_tap_seek_subtitle),
                    checked = state.isPlayerDoubleTapSeekEnabled,
                    iconColor = orangeColor,
                    position = ItemPosition.MIDDLE,
                    onCheckedChange = component::onPlayerDoubleTapSeekEnabledChanged
                )

                (AnimatedVisibility(
                    visible = state.isPlayerDoubleTapSeekEnabled,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Column {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                AppearanceSliderItem(
                                    title = stringResource(R.string.seek_duration_title),
                                    value = state.playerSeekDuration.toFloat(),
                                    onValueChange = { component.onPlayerSeekDurationChanged(it.toInt()) },
                                    valueRange = 5f..60f,
                                    steps = 10,
                                    onReset = { component.onPlayerSeekDurationChanged(10) },
                                    valueSuffix = "s",
                                    startIcon = {
                                        Icon(
                                            Icons.Rounded.FastRewind,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    },
                                    endIcon = {
                                        Icon(
                                            Icons.Rounded.FastForward,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                    }
                })

                SettingsSwitchTile(
                    icon = Icons.Rounded.ZoomIn,
                    title = stringResource(R.string.enable_zoom_title),
                    subtitle = stringResource(R.string.enable_zoom_subtitle),
                    checked = state.isPlayerZoomEnabled,
                    iconColor = pinkColor,
                    position = ItemPosition.BOTTOM,
                    onCheckedChange = component::onPlayerZoomEnabledChanged
                )
            }

            item {
                SectionHeader(stringResource(R.string.chat_list_header))
                SettingsSwitchTile(
                    icon = Icons.Rounded.Archive,
                    title = stringResource(R.string.pin_archived_chats_title),
                    subtitle = stringResource(R.string.pin_archived_chats_subtitle),
                    checked = state.isArchivePinned,
                    iconColor = orangeColor,
                    position = ItemPosition.TOP,
                    onCheckedChange = component::onArchivePinnedChanged
                )
                AnimatedVisibility(
                    visible = state.isArchivePinned,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    SettingsSwitchTile(
                        icon = Icons.Rounded.Archive,
                        title = stringResource(R.string.always_show_pinned_archive_title),
                        subtitle = stringResource(R.string.always_show_pinned_archive_subtitle),
                        checked = state.isArchiveAlwaysVisible,
                        iconColor = orangeColor,
                        position = ItemPosition.MIDDLE,
                        onCheckedChange = component::onArchiveAlwaysVisibleChanged
                    )
                }
                SettingsSwitchTile(
                    icon = Icons.Rounded.Link,
                    title = stringResource(R.string.show_link_previews_title),
                    subtitle = stringResource(R.string.show_link_previews_subtitle),
                    checked = state.showLinkPreviews,
                    iconColor = blueColor,
                    position = ItemPosition.MIDDLE,
                    onCheckedChange = component::onShowLinkPreviewsChanged
                )
                SettingsSwitchTile(
                    icon = Icons.Rounded.SwipeLeft,
                    title = stringResource(R.string.drag_to_back_title),
                    subtitle = stringResource(R.string.drag_to_back_subtitle),
                    checked = state.isDragToBackEnabled,
                    iconColor = tealColor,
                    position = ItemPosition.BOTTOM,
                    onCheckedChange = component::onDragToBackChanged
                )
            }

            item {
                ChatListPreview(
                    messageLines = state.chatListMessageLines,
                    showPhotos = state.showChatListPhotos,
                    position = ItemPosition.TOP,
                    videoPlayerPool = component.videoPlayerPool
                )

                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(1, 2).forEach { lines ->
                                val label =
                                    if (lines == 1) stringResource(R.string.two_line_label) else stringResource(R.string.three_line_label)
                                Surface(
                                    onClick = { component.onChatListMessageLinesChanged(lines) },
                                    shape = RoundedCornerShape(16.dp),
                                    color = if (state.chatListMessageLines == lines) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                                    border = if (state.chatListMessageLines == lines) BorderStroke(
                                        2.dp,
                                        MaterialTheme.colorScheme.primary
                                    ) else null,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            text = label,
                                            style = MaterialTheme.typography.labelLarge,
                                            color = if (state.chatListMessageLines == lines) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontWeight = if (state.chatListMessageLines == lines) FontWeight.Bold else FontWeight.Medium
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))

                SettingsSwitchTile(
                    icon = Icons.Rounded.AccountCircle,
                    title = stringResource(R.string.show_photos_title),
                    subtitle = stringResource(R.string.show_photos_subtitle),
                    checked = state.showChatListPhotos,
                    iconColor = pinkColor,
                    position = ItemPosition.BOTTOM,
                    onCheckedChange = component::onShowChatListPhotosChanged
                )
            }

            if (!state.isInstalledFromGooglePlay) {
                item {
                    SectionHeader(stringResource(R.string.experimental_header))
                    SettingsTile(
                        icon = Icons.Rounded.Block,
                        title = stringResource(R.string.adblock_channels_title),
                        subtitle = stringResource(R.string.adblock_channels_subtitle),
                        iconColor = redColor,
                        position = ItemPosition.STANDALONE,
                        onClick = component::onAdBlockClick
                    )
                }
            }

            item {
                SectionHeader(stringResource(R.string.recent_media_header))
                SettingsTile(
                    icon = Icons.AutoMirrored.Rounded.StickyNote2,
                    title = stringResource(R.string.clear_recent_stickers_title),
                    subtitle = stringResource(R.string.clear_recent_stickers_subtitle),
                    iconColor = purpleColor,
                    position = ItemPosition.TOP,
                    onClick = component::onClearRecentStickers
                )
                SettingsTile(
                    icon = Icons.Rounded.EmojiEmotions,
                    title = stringResource(R.string.clear_recent_emojis_title),
                    subtitle = stringResource(R.string.clear_recent_emojis_subtitle),
                    iconColor = tealColor,
                    position = ItemPosition.BOTTOM,
                    onClick = component::onClearRecentEmojis
                )
            }
        }
    }

    if (state.emojiPackToRemove != null) {
        val style = state.emojiPackToRemove!!
        val label = when (style) {
            EmojiStyle.APPLE -> stringResource(R.string.emoji_style_apple)
            EmojiStyle.TWITTER -> stringResource(R.string.emoji_style_twitter)
            EmojiStyle.WINDOWS -> stringResource(R.string.emoji_style_windows)
            EmojiStyle.CATMOJI -> stringResource(R.string.emoji_style_catmoji)
            EmojiStyle.NOTO -> stringResource(R.string.emoji_style_noto)
            else -> ""
        }

        ModalBottomSheet(
            onDismissRequest = component::onDismissRemoveEmojiPack,
            dragHandle = { BottomSheetDefaults.DragHandle() },
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 32.dp)
            ) {
                Text(
                    text = stringResource(R.string.remove_emoji_pack_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .background(MaterialTheme.colorScheme.errorContainer, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Rounded.DeleteOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.remove_pack_confirmation_format, label),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.remove_pack_description),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = component::onDismissRemoveEmojiPack,
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(stringResource(R.string.cancel_button), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = component::onConfirmRemoveEmojiPack,
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        )
                    ) {
                        Text(stringResource(R.string.action_delete), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(start = 12.dp, bottom = 8.dp, top = 16.dp),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun AppearanceSliderItem(
    title: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onReset: () -> Unit,
    valueSuffix: String,
    startIcon: @Composable () -> Unit,
    endIcon: @Composable () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "${value.toInt()} $valueSuffix",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            TextButton(
                onClick = onReset,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                modifier = Modifier.height(32.dp)
            ) {
                Text(
                    stringResource(R.string.reset_button),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                startIcon()
            }
            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = valueRange,
                steps = steps,
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                )
            )
            Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                endIcon()
            }
        }
    }
}

@Composable
private fun ThemeModeItem(
    mode: NightMode,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val icon = when (mode) {
        NightMode.SYSTEM -> Icons.Rounded.BrightnessAuto
        NightMode.LIGHT -> Icons.Rounded.LightMode
        NightMode.DARK -> Icons.Rounded.DarkMode
        NightMode.SCHEDULED -> Icons.Rounded.Schedule
        NightMode.BRIGHTNESS -> Icons.Rounded.Brightness4
    }

    val label = nightModeLabel(mode)

    val containerColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        animationSpec = tween(400, easing = EaseOutQuint),
        label = "containerColor"
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(400, easing = EaseOutQuint),
        label = "contentColor"
    )
    val borderAlpha by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(400, easing = EaseOutQuint),
        label = "borderAlpha"
    )

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = containerColor,
        border = if (selected) BorderStroke(
            2.dp,
            MaterialTheme.colorScheme.primary.copy(alpha = borderAlpha)
        ) else null,
        modifier = Modifier
            .height(84.dp)
            .fillMaxWidth()
            .then(modifier)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .background(contentColor.copy(alpha = if (selected) 0.16f else 0.1f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(20.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = contentColor,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (selected) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun nightModeLabel(mode: NightMode): String = when (mode) {
    NightMode.SYSTEM -> stringResource(R.string.night_mode_system)
    NightMode.LIGHT -> stringResource(R.string.night_mode_light)
    NightMode.DARK -> stringResource(R.string.night_mode_dark)
    NightMode.SCHEDULED -> stringResource(R.string.night_mode_scheduled)
    NightMode.BRIGHTNESS -> stringResource(R.string.night_mode_brightness)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EmojiStyleItem(
    style: EmojiStyle,
    selected: Boolean,
    isDownloaded: Boolean,
    isDownloading: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val label = when (style) {
        EmojiStyle.APPLE -> stringResource(R.string.emoji_style_apple)
        EmojiStyle.TWITTER -> stringResource(R.string.emoji_style_twitter)
        EmojiStyle.WINDOWS -> stringResource(R.string.emoji_style_windows)
        EmojiStyle.CATMOJI -> stringResource(R.string.emoji_style_catmoji)
        EmojiStyle.NOTO -> stringResource(R.string.emoji_style_noto)
        EmojiStyle.SYSTEM -> stringResource(R.string.emoji_style_system)
    }

    val emojiFontFamily = if (isDownloaded) {
        getEmojiFontFamily(style)
    } else {
        FontFamily.Default
    }

    val containerColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        animationSpec = tween(400, easing = EaseOutQuint),
        label = "containerColor"
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(400, easing = EaseOutQuint),
        label = "contentColor"
    )
    val borderAlpha by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(400, easing = EaseOutQuint),
        label = "borderAlpha"
    )

    Surface(
        modifier = Modifier
            .height(100.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(20.dp),
        color = containerColor,
        border = if (selected) BorderStroke(
            2.dp,
            MaterialTheme.colorScheme.primary.copy(alpha = borderAlpha)
        ) else null,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(8.dp)
            ) {
                Text(
                    text = "\uD83D\uDE0A\uD83D\uDE0D\uD83D\uDE0E",
                    fontSize = 24.sp,
                    fontFamily = emojiFontFamily,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = contentColor,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (!isDownloaded && style != EmojiStyle.SYSTEM) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(Color.Black.copy(alpha = 0.3f), RoundedCornerShape(20.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (isDownloading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Download,
                            contentDescription = stringResource(R.string.action_download),
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TimeSettingCard(
    label: String,
    time: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    time,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialogWrapper(
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
    content: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.ok_button))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.cancel_button))
            }
        },
        text = {
            content()
        }
    )
}