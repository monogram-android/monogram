@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package org.monogram.presentation.features.viewers.components

import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Forward
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PictureInPicture
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.monogram.presentation.R
import org.monogram.presentation.features.stickers.ui.menu.MenuInfoRow
import org.monogram.presentation.features.stickers.ui.menu.MenuOptionRow
import org.monogram.presentation.features.stickers.ui.menu.MenuToggleRow

@Composable
fun YouTubePlayerControlsUI(
    visible: Boolean,
    isPlaying: Boolean,
    currentPosition: Long,
    totalDuration: Long,
    bufferedPosition: Long = 0,
    videoTitle: String = "",
    currentTimeStr: String,
    isSettingsOpen: Boolean,
    caption: String? = null,
    onPlayPauseToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onRewind: () -> Unit,
    onSettingsToggle: () -> Unit
) {
    var isDragging by remember { mutableStateOf(false) }
    var sliderPosition by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(currentPosition, totalDuration, isDragging) {
        if (!isDragging && totalDuration > 0) {
            sliderPosition = currentPosition.toFloat() / totalDuration.toFloat()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Column(
                modifier = Modifier.background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.8f),
                            Color.Black.copy(alpha = 0.4f),
                            Color.Transparent
                        )
                    )
                )
            ) {
                ViewerTopBar(
                    onBack = onBack,
                    onActionClick = onSettingsToggle,
                    isActionActive = isSettingsOpen,
                    modifier = Modifier.background(Color.Transparent)
                )
                if (videoTitle.isNotEmpty()) {
                    Text(
                        text = videoTitle,
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 16.dp)
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = visible,
            enter = scaleIn() + fadeIn(),
            exit = scaleOut() + fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(48.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onRewind, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Rounded.Replay10, stringResource(R.string.viewer_seek_rewind_cd), tint = Color.White, modifier = Modifier.fillMaxSize())
                }

                val interactionSource = remember { MutableInteractionSource() }
                val isPressed by interactionSource.collectIsPressedAsState()
                val scale by animateFloatAsState(targetValue = if (isPressed) 0.9f else 1f, label = "scale")

                Box(
                    modifier = Modifier
                        .scale(scale)
                        .size(84.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f))
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null
                        ) { onPlayPauseToggle() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (isPlaying) stringResource(R.string.action_pause) else stringResource(R.string.action_play),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(48.dp)
                    )
                }

                IconButton(onClick = onForward, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Rounded.Forward10, stringResource(R.string.viewer_seek_forward_cd), tint = Color.White, modifier = Modifier.fillMaxSize())
                }
            }
        }

        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.5f),
                                Color.Black.copy(alpha = 0.9f)
                            )
                        )
                    )
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 24.dp)
                    .padding(top = 32.dp, bottom = 24.dp)
            ) {
                if (!caption.isNullOrBlank()) {
                    ViewerCaption(caption = caption, showGradient = false)
                    Spacer(modifier = Modifier.height(8.dp))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${formatDuration(if (isDragging) (sliderPosition * totalDuration).toLong() else currentPosition)} / ${
                            formatDuration(
                                totalDuration
                            )
                        }",
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        text = currentTimeStr,
                        color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.labelMedium
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                    // Buffered track
                    if (totalDuration > 0) {
                        LinearWavyProgressIndicator(
                            progress = { bufferedPosition.toFloat() / totalDuration.toFloat() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .padding(horizontal = 2.dp),
                            color = Color.White.copy(alpha = 0.2f),
                            trackColor = Color.Transparent,
                        )
                    }

                    Slider(
                        value = sliderPosition,
                        onValueChange = { isDragging = true; sliderPosition = it },
                        onValueChangeFinished = {
                            isDragging = false; onSeek((sliderPosition * totalDuration).toLong())
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

private enum class YouTubeSettingsScreen {
    MAIN, SPEED, QUALITY
}

private enum class YouTubeQualityPreset {
    AUTO, HIGH, MEDIUM, LOW
}

private data class YouTubeQualityPresetOption(
    val preset: YouTubeQualityPreset,
    val mappedQuality: String
)

internal fun normalizeYouTubeQualityCode(quality: String): String {
    return quality.trim().lowercase()
}

internal fun youtubeQualityRank(quality: String): Int {
    return when (normalizeYouTubeQualityCode(quality)) {
        "highres" -> 9
        "hd4320" -> 8
        "hd2880" -> 7
        "hd2160" -> 6
        "hd1440" -> 5
        "hd1080" -> 4
        "hd720" -> 3
        "large" -> 2
        "medium" -> 1
        "small" -> 0
        "tiny" -> -1
        else -> Int.MIN_VALUE
    }
}

internal fun baseQualityLabel(quality: String): String {
    return when (normalizeYouTubeQualityCode(quality)) {
        "hd4320" -> "4320p"
        "hd2880" -> "2880p"
        "hd2160" -> "2160p"
        "hd1440" -> "1440p"
        "hd1080" -> "1080p"
        "hd720" -> "720p"
        "large" -> "480p"
        "medium" -> "360p"
        "small" -> "240p"
        "tiny" -> "144p"
        "auto" -> "Auto"
        "highres" -> "High"
        else -> quality.ifBlank { "unknown" }
    }
}

@Composable
fun YouTubeSettingsMenu(
    playbackSpeed: Float,
    isMuted: Boolean,
    isLooping: Boolean = false,
    isCaptionsEnabled: Boolean = false,
    availableQualities: List<String> = emptyList(),
    currentQuality: String = "auto",
    appliedQuality: String = "auto",
    qualityStatusMessage: String? = null,
    showRetryStream: Boolean = false,
    showSwitchToAuto: Boolean = false,
    showUseLowQuality: Boolean = false,
    showOpenInBrowser: Boolean = false,
    onQualitySelected: (String) -> Unit = {},
    onSpeedSelected: (Float) -> Unit,
    onMuteToggle: () -> Unit,
    onLoopToggle: () -> Unit = {},
    onCaptionsToggle: () -> Unit = {},
    onLockToggle: () -> Unit,
    onRotationToggle: () -> Unit,
    onCopyLink: () -> Unit,
    onCopyLinkWithTime: () -> Unit = {},
    onEnterPip: (() -> Unit)? = null,
    onCopyText: (() -> Unit)? = null,
    onForward: () -> Unit = {},
    onRetryStream: (() -> Unit)? = null,
    onSwitchToAuto: (() -> Unit)? = null,
    onUseLowQuality: (() -> Unit)? = null,
    onOpenInBrowser: (() -> Unit)? = null
) {
    var currentScreen by remember { mutableStateOf(YouTubeSettingsScreen.MAIN) }

    ViewerSettingsDropdown {
        AnimatedContent(
            targetState = currentScreen,
            transitionSpec = {
                if (targetState != YouTubeSettingsScreen.MAIN) {
                    slideInHorizontally { width -> width } + fadeIn() togetherWith
                            slideOutHorizontally { width -> -width } + fadeOut()
                } else {
                    slideInHorizontally { width -> -width } + fadeIn() togetherWith
                            slideOutHorizontally { width -> width } + fadeOut()
                }
            },
            label = "YouTubeSettingsNavigation"
        ) { screen ->
            when (screen) {
                YouTubeSettingsScreen.MAIN -> {
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        MenuOptionRow(
                            icon = Icons.Rounded.HighQuality,
                            title = stringResource(R.string.settings_quality),
                            value = buildCurrentQualityValue(
                                currentQuality = currentQuality,
                                appliedQuality = appliedQuality,
                                availableQualities = availableQualities
                            ),
                            onClick = { currentScreen = YouTubeSettingsScreen.QUALITY },
                            trailingIcon = Icons.AutoMirrored.Rounded.KeyboardArrowRight
                        )

                        qualityStatusMessage?.let { status ->
                            MenuInfoRow(
                                icon = Icons.Rounded.Info,
                                title = stringResource(R.string.settings_quality),
                                value = status
                            )
                        }

                        if (showRetryStream && onRetryStream != null) {
                            MenuOptionRow(
                                icon = Icons.Rounded.Refresh,
                                title = stringResource(R.string.action_retry_stream),
                                onClick = onRetryStream
                            )
                        }
                        if (showSwitchToAuto && onSwitchToAuto != null) {
                            MenuOptionRow(
                                icon = Icons.Rounded.AutoAwesome,
                                title = stringResource(R.string.action_switch_to_auto),
                                onClick = onSwitchToAuto
                            )
                        }
                        if (showUseLowQuality && onUseLowQuality != null) {
                            MenuOptionRow(
                                icon = Icons.Rounded.SignalCellularAlt,
                                title = stringResource(R.string.action_use_low_quality),
                                onClick = onUseLowQuality
                            )
                        }
                        if (showOpenInBrowser && onOpenInBrowser != null) {
                            MenuOptionRow(
                                icon = Icons.AutoMirrored.Rounded.OpenInNew,
                                title = stringResource(R.string.action_open_in_browser),
                                onClick = onOpenInBrowser
                            )
                        }

                        MenuOptionRow(
                            icon = Icons.Rounded.Speed,
                            title = stringResource(R.string.settings_playback_speed),
                            value = "${playbackSpeed}x",
                            onClick = { currentScreen = YouTubeSettingsScreen.SPEED },
                            trailingIcon = Icons.AutoMirrored.Rounded.KeyboardArrowRight
                        )

                        MenuOptionRow(
                            icon = Icons.Rounded.ScreenRotation,
                            title = stringResource(R.string.settings_rotate_screen),
                            onClick = onRotationToggle
                        )

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && onEnterPip != null) {
                            MenuOptionRow(
                                icon = Icons.Rounded.PictureInPicture,
                                title = stringResource(R.string.settings_pip),
                                onClick = onEnterPip
                            )
                        }

                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                        )

                        MenuToggleRow(
                            icon = if (isMuted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
                            title = stringResource(R.string.settings_mute_audio),
                            isChecked = isMuted,
                            onCheckedChange = { onMuteToggle() }
                        )

                        MenuToggleRow(
                            icon = Icons.Rounded.Repeat,
                            title = stringResource(R.string.settings_loop_video),
                            isChecked = isLooping,
                            onCheckedChange = { onLoopToggle() }
                        )

                        MenuToggleRow(
                            icon = Icons.Rounded.ClosedCaption,
                            title = stringResource(R.string.settings_subtitles),
                            isChecked = isCaptionsEnabled,
                            onCheckedChange = { onCaptionsToggle() }
                        )

                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                        )

                        if (onCopyText != null) {
                            MenuOptionRow(
                                icon = Icons.Rounded.ContentCopy,
                                title = stringResource(R.string.action_copy_text),
                                onClick = onCopyText
                            )
                        }

                        MenuOptionRow(
                            icon = Icons.Rounded.Link,
                            title = stringResource(R.string.action_copy_link),
                            onClick = onCopyLink
                        )

                        MenuOptionRow(
                            icon = Icons.Rounded.Schedule,
                            title = stringResource(R.string.action_copy_link_time),
                            onClick = onCopyLinkWithTime
                        )

                        MenuOptionRow(
                            icon = Icons.AutoMirrored.Rounded.Forward,
                            title = stringResource(R.string.action_forward),
                            onClick = onForward
                        )

                        MenuOptionRow(
                            icon = Icons.Rounded.Lock,
                            title = stringResource(R.string.settings_lock_controls),
                            onClick = onLockToggle,
                            iconTint = MaterialTheme.colorScheme.primary,
                            textColor = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                YouTubeSettingsScreen.SPEED -> {
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { currentScreen = YouTubeSettingsScreen.MAIN }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Rounded.ArrowBack,
                                stringResource(R.string.viewer_back),
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                stringResource(R.string.settings_playback_speed),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                        val speeds = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
                        speeds.forEach { speed ->
                            SpeedSelectionRow(
                                speed = speed,
                                isSelected = playbackSpeed == speed,
                                onClick = { onSpeedSelected(speed) })
                        }
                    }
                }

                YouTubeSettingsScreen.QUALITY -> {
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        val presetOptions = remember(availableQualities) {
                            buildQualityPresetOptions(availableQualities)
                        }
                        val currentPreset = resolveCurrentQualityPreset(
                            currentQuality = currentQuality,
                            availableQualities = availableQualities
                        )

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { currentScreen = YouTubeSettingsScreen.MAIN }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Rounded.ArrowBack,
                                stringResource(R.string.viewer_back),
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                stringResource(R.string.settings_video_quality),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))

                        presetOptions.forEach { option ->
                            val iconRes = getQualityPresetIcon(option.preset)
                            val title = formatQualityPresetLabel(option.preset)
                            val value = option.mappedQuality
                                .takeIf { option.preset != YouTubeQualityPreset.AUTO }
                                ?.let { formatQualityLabel(it) }

                            if (iconRes != null) {
                                MenuOptionRow(
                                    iconRes = iconRes,
                                    title = title,
                                    value = value,
                                    onClick = {
                                        onQualitySelected(option.mappedQuality)
                                        currentScreen = YouTubeSettingsScreen.MAIN
                                    },
                                    trailingIcon = if (currentPreset == option.preset) Icons.Rounded.Check else null,
                                    iconTint = if (currentPreset == option.preset) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                            } else {
                                MenuOptionRow(
                                    icon = Icons.Rounded.HighQuality,
                                    title = title,
                                    value = value,
                                    onClick = {
                                        onQualitySelected(option.mappedQuality)
                                        currentScreen = YouTubeSettingsScreen.MAIN
                                    },
                                    trailingIcon = if (currentPreset == option.preset) Icons.Rounded.Check else null,
                                    iconTint = if (currentPreset == option.preset) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun formatQualityPresetLabel(preset: YouTubeQualityPreset): String {
    return when (preset) {
        YouTubeQualityPreset.AUTO -> stringResource(R.string.settings_quality_auto)
        YouTubeQualityPreset.HIGH -> stringResource(R.string.settings_quality_high_res)
        YouTubeQualityPreset.MEDIUM -> stringResource(R.string.settings_quality_medium)
        YouTubeQualityPreset.LOW -> stringResource(R.string.settings_quality_low)
    }
}

private fun buildQualityPresetOptions(
    availableQualities: List<String>
): List<YouTubeQualityPresetOption> {
    val normalized = availableQualities
        .map(::normalizeYouTubeQualityCode)
        .distinct()
    val selectableQualities = normalized.filter { it == "large" || it == "medium" || it == "small" }

    val options = mutableListOf(
        YouTubeQualityPresetOption(YouTubeQualityPreset.AUTO, "auto")
    )

    if (selectableQualities.isEmpty()) {
        return options
    }

    resolvePresetQuality(
        selectableQualities = selectableQualities,
        preferredOrder = listOf("large", "medium", "small")
    )?.let { options += YouTubeQualityPresetOption(YouTubeQualityPreset.HIGH, it) }

    resolvePresetQuality(
        selectableQualities = selectableQualities,
        preferredOrder = listOf("medium", "large", "small")
    )?.let { options += YouTubeQualityPresetOption(YouTubeQualityPreset.MEDIUM, it) }

    resolvePresetQuality(
        selectableQualities = selectableQualities,
        preferredOrder = listOf("small", "medium", "large")
    )?.let { options += YouTubeQualityPresetOption(YouTubeQualityPreset.LOW, it) }

    return options
}

private fun resolvePresetQuality(
    selectableQualities: List<String>,
    preferredOrder: List<String>
): String? {
    return preferredOrder.firstOrNull { it in selectableQualities }
}

@Composable
private fun buildCurrentQualityValue(
    currentQuality: String,
    appliedQuality: String,
    availableQualities: List<String>
): String {
    val preset = formatQualityPresetLabel(
        resolveCurrentQualityPreset(
            currentQuality = currentQuality,
            availableQualities = availableQualities
        )
    )
    val applied = formatQualityLabel(appliedQuality)
    return "$preset • $applied"
}

private fun resolveCurrentQualityPreset(
    currentQuality: String,
    availableQualities: List<String>
): YouTubeQualityPreset {
    val presetOptions = buildQualityPresetOptions(availableQualities)
    val normalizedCurrentQuality = normalizeYouTubeQualityCode(currentQuality)
    return presetOptions.firstOrNull { normalizeYouTubeQualityCode(it.mappedQuality) == normalizedCurrentQuality }?.preset
        ?: YouTubeQualityPreset.AUTO
}

@Composable
internal fun formatQualityLabel(quality: String): String {
    return when (normalizeYouTubeQualityCode(quality)) {
        "auto" -> stringResource(R.string.settings_quality_auto)
        "highres" -> stringResource(R.string.settings_quality_high_res)
        else -> baseQualityLabel(quality)
    }
}

private fun getQualityPresetIcon(preset: YouTubeQualityPreset): Int? {
    return when (preset) {
        YouTubeQualityPreset.AUTO -> R.drawable.ic_quality_auto
        YouTubeQualityPreset.HIGH -> null
        YouTubeQualityPreset.MEDIUM -> R.drawable.ic_quality_hd
        YouTubeQualityPreset.LOW -> R.drawable.ic_quality_sd
    }
}