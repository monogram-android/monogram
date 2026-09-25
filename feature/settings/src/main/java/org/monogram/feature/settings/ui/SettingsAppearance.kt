package org.monogram.feature.settings.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.BrightnessAuto
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.FormatLineSpacing
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.monogram.core.ui.AccentPreset
import org.monogram.core.ui.AppearanceSettings
import org.monogram.core.ui.AppearanceState
import org.monogram.core.ui.ComposerStyle
import org.monogram.core.ui.ThemePreference
import org.monogram.core.ui.components.ChatComposerLayout
import org.monogram.core.ui.components.ItemPosition
import org.monogram.core.ui.components.SectionHeader
import org.monogram.core.ui.components.SettingsCard
import org.monogram.core.ui.components.SettingsCardRow
import org.monogram.core.ui.components.SettingsChoice
import org.monogram.core.ui.components.SettingsChoiceGroup
import org.monogram.core.ui.components.SettingsTile
import org.monogram.core.ui.theme.accentSwatch
import org.monogram.feature.settings.R

internal fun LazyListScope.appearanceItems(
    appearance: AppearanceState,
    onOpenWallpaper: () -> Unit,
) {
    item { SectionHeader(stringResource(R.string.settings_section_look)) }
    item {
        SettingsCard(position = ItemPosition.STANDALONE) {
            WallpaperPreview(
                appearance.wallpaperPath,
                appearance.wallpaperDim,
                appearance.wallpaperMode,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp)
                    .heightIn(min = 180.dp)
                    .animateContentSize()
                    .clickable(onClick = onOpenWallpaper),
                messageTextSize = appearance.messageTextSize,
                lineSpacing = appearance.lineSpacing,
                letterSpacing = appearance.letterSpacing,
            )
            SettingsCardRow(
                icon = Icons.Outlined.Image,
                iconColor = MaterialTheme.colorScheme.primary,
                title = stringResource(R.string.settings_wallpaper),
                subtitle = stringResource(R.string.settings_wallpaper_hero_sub),
                onClick = onOpenWallpaper,
                trailingContent = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
            SettingsCardDivider()
            MessageTextSizeRow(appearance)
        }
    }

    item { SectionHeader(stringResource(R.string.settings_theme)) }
    item {
        SettingsCard(position = ItemPosition.STANDALONE) {
            SettingsChoiceGroup(
                options = ThemePreference.entries.map { preference ->
                    SettingsChoice(
                        label = themeLabel(preference),
                        icon = when (preference) {
                            ThemePreference.System -> Icons.Outlined.BrightnessAuto
                            ThemePreference.Light -> Icons.Outlined.LightMode
                            ThemePreference.Dark -> Icons.Outlined.DarkMode
                        },
                    )
                },
                selectedIndex = ThemePreference.entries.indexOf(appearance.theme),
                onSelect = { index -> AppearanceSettings.setTheme(ThemePreference.entries[index]) },
            )
            SettingsCardDivider()
            DynamicColorRow(appearance)
        }
    }

    item { SectionHeader(stringResource(R.string.settings_accent)) }
    item {
        val dynamicSupported =
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
        val effectiveDynamic = appearance.dynamicColor && dynamicSupported
        SettingsCard(position = ItemPosition.STANDALONE) {
            AccentPicker(
                preset = appearance.accentPreset,
                dynamic = effectiveDynamic,
                onSelect = AppearanceSettings::setAccentPreset,
            )
        }
        androidx.compose.animation.AnimatedVisibility(visible = effectiveDynamic) {
            Text(
                text = stringResource(R.string.settings_dynamic_color_on),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
            )
        }
    }

    item { SectionHeader(stringResource(R.string.settings_chat_list)) }
    item {
        SettingsCard(position = ItemPosition.STANDALONE) {
            SettingsChoiceGroup(
                options = listOf(
                    SettingsChoice(stringResource(R.string.settings_preview_lines_two)),
                    SettingsChoice(stringResource(R.string.settings_preview_lines_three)),
                ),
                selectedIndex = (appearance.previewLines - 2).coerceIn(0, 1),
                onSelect = { index -> AppearanceSettings.setPreviewLines(index + 2) },
            )
            ChatListPreview(
                lines = appearance.previewLines,
                showAvatar = appearance.showChatAvatars,
            )
            SettingsCardDivider()
            SwitchRow(
                icon = Icons.Outlined.Person,
                iconColor = MaterialTheme.colorScheme.primary,
                title = stringResource(R.string.settings_show_avatars),
                subtitle = stringResource(R.string.settings_show_avatars_sub),
                checked = appearance.showChatAvatars,
                onCheckedChange = AppearanceSettings::setShowChatAvatars,
            )
            SwitchRow(
                icon = Icons.Outlined.AccountCircle,
                iconColor = MaterialTheme.colorScheme.tertiary,
                title = stringResource(R.string.settings_avatar_profile_tap),
                subtitle = stringResource(
                    if (appearance.showChatAvatars) {
                        R.string.settings_avatar_profile_tap_sub
                    } else {
                        R.string.settings_avatar_profile_tap_unavailable
                    },
                ),
                checked = appearance.openProfileOnAvatarTap,
                enabled = appearance.showChatAvatars,
                onCheckedChange = AppearanceSettings::setOpenProfileOnAvatarTap,
            )
            SwitchRow(
                icon = Icons.AutoMirrored.Outlined.VolumeOff,
                iconColor = MaterialTheme.colorScheme.secondary,
                title = stringResource(R.string.settings_muted_counter),
                subtitle = stringResource(R.string.settings_muted_counter_sub),
                checked = appearance.showMutedCounter,
                onCheckedChange = AppearanceSettings::setShowMutedCounter,
            )
            SwitchRow(
                icon = Icons.Outlined.FilterList,
                iconColor = MaterialTheme.colorScheme.primary,
                title = stringResource(R.string.settings_folders_at_bottom),
                subtitle = stringResource(R.string.settings_folders_at_bottom_sub),
                checked = appearance.foldersAtBottom,
                onCheckedChange = AppearanceSettings::setFoldersAtBottom,
            )
        }
    }

    item { SectionHeader(stringResource(R.string.settings_input_bar)) }
    item {
        SettingsCard(position = ItemPosition.STANDALONE) {
            SettingsChoiceGroup(
                options = listOf(
                    SettingsChoice(stringResource(R.string.settings_input_bar_ios)),
                    SettingsChoice(stringResource(R.string.settings_input_bar_material)),
                ),
                selectedIndex = if (appearance.composerStyle == ComposerStyle.IOS) 0 else 1,
                onSelect = { index ->
                    AppearanceSettings.setComposerStyle(
                        if (index == 0) ComposerStyle.IOS else ComposerStyle.Material,
                    )
                },
            )
            InputBarPreview(appearance)
            SettingsCardDivider()
            SwitchRow(
                icon = Icons.Outlined.EmojiEmotions,
                iconColor = MaterialTheme.colorScheme.tertiary,
                title = stringResource(R.string.settings_input_bar_emoji_shortcut),
                subtitle = stringResource(R.string.settings_input_bar_emoji_shortcut_sub),
                checked = appearance.showEmojiButton,
                onCheckedChange = AppearanceSettings::setShowEmojiButton,
            )
            SwitchRow(
                icon = Icons.Outlined.Keyboard,
                iconColor = MaterialTheme.colorScheme.secondary,
                title = stringResource(R.string.settings_input_bar_send_by_enter),
                subtitle = stringResource(R.string.settings_input_bar_send_by_enter_sub),
                checked = appearance.sendByEnter,
                onCheckedChange = AppearanceSettings::setSendByEnter,
            )
            SwitchRow(
                icon = Icons.Outlined.Link,
                iconColor = MaterialTheme.colorScheme.primary,
                title = stringResource(R.string.settings_input_bar_fix_previews),
                subtitle = stringResource(R.string.settings_input_bar_fix_previews_sub),
                checked = appearance.fixLinkPreviews,
                onCheckedChange = AppearanceSettings::setFixLinkPreviews,
            )
        }
    }
}

@Composable
private fun SettingsCardDivider() {
    Box(
        modifier = Modifier
            .padding(start = 16.dp)
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
    )
}

@Composable
private fun MessageTextSizeRow(appearance: AppearanceState) {
    val sizeTitle = stringResource(R.string.settings_message_text_size)
    val sizeLabel = stringResource(
        R.string.settings_message_text_size_value,
        appearance.messageTextSize,
    )
    val spacingTitle = stringResource(R.string.settings_line_spacing)
    val spacingPercent = (appearance.lineSpacing * 100f).toInt()
    val defaultLabel = stringResource(R.string.settings_line_spacing_default)
    val spacingLabel = if (spacingPercent == 100) {
        defaultLabel
    } else {
        stringResource(R.string.settings_line_spacing_value, spacingPercent)
    }
    val letterTitle = stringResource(R.string.settings_letter_spacing)
    val letterHundredths = (appearance.letterSpacing * 100f).toInt()
    val letterLabel = if (letterHundredths == 0) {
        defaultLabel
    } else {
        stringResource(R.string.settings_letter_spacing_value, letterHundredths)
    }
    Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 12.dp)) {
        TypographySliderRow(
            icon = Icons.Outlined.FormatSize,
            title = sizeTitle,
            valueLabel = sizeLabel,
            value = appearance.messageTextSize.toFloat(),
            valueRange = AppearanceSettings.MIN_MESSAGE_TEXT_SIZE.toFloat()..
                AppearanceSettings.MAX_MESSAGE_TEXT_SIZE.toFloat(),
            steps = AppearanceSettings.MAX_MESSAGE_TEXT_SIZE -
                AppearanceSettings.MIN_MESSAGE_TEXT_SIZE - 1,
            onValueChange = { AppearanceSettings.setMessageTextSize(it.toInt()) },
            isDefault = appearance.messageTextSize == AppearanceSettings.DEFAULT_MESSAGE_TEXT_SIZE,
            resetLabel = stringResource(R.string.settings_reset_control, sizeTitle),
            onReset = { AppearanceSettings.setMessageTextSize(AppearanceSettings.DEFAULT_MESSAGE_TEXT_SIZE) },
        )
        TypographySliderRow(
            icon = Icons.Outlined.FormatLineSpacing,
            title = spacingTitle,
            valueLabel = spacingLabel,
            value = appearance.lineSpacing,
            valueRange = AppearanceSettings.MIN_LINE_SPACING..AppearanceSettings.MAX_LINE_SPACING,
            steps = ((AppearanceSettings.MAX_LINE_SPACING - AppearanceSettings.MIN_LINE_SPACING) /
                0.05f).toInt() - 1,
            onValueChange = AppearanceSettings::setLineSpacing,
            isDefault = appearance.lineSpacing == AppearanceSettings.DEFAULT_LINE_SPACING,
            resetLabel = stringResource(R.string.settings_reset_control, spacingTitle),
            onReset = { AppearanceSettings.setLineSpacing(AppearanceSettings.DEFAULT_LINE_SPACING) },
        )
        TypographySliderRow(
            icon = Icons.Outlined.TextFields,
            title = letterTitle,
            valueLabel = letterLabel,
            value = appearance.letterSpacing,
            valueRange = AppearanceSettings.MIN_LETTER_SPACING..AppearanceSettings.MAX_LETTER_SPACING,
            steps = ((AppearanceSettings.MAX_LETTER_SPACING - AppearanceSettings.MIN_LETTER_SPACING) /
                0.01f).toInt() - 1,
            onValueChange = AppearanceSettings::setLetterSpacing,
            isDefault = appearance.letterSpacing == AppearanceSettings.DEFAULT_LETTER_SPACING,
            resetLabel = stringResource(R.string.settings_reset_control, letterTitle),
            onReset = {
                AppearanceSettings.setLetterSpacing(AppearanceSettings.DEFAULT_LETTER_SPACING)
            },
        )
    }
}

@Composable
private fun TypographySliderRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    valueLabel: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
    isDefault: Boolean,
    resetLabel: String,
    onReset: () -> Unit,
) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
            )
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(
                onClick = onReset,
                enabled = !isDefault,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.RestartAlt,
                    contentDescription = resetLabel,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.semantics { contentDescription = "$title, $valueLabel" },
        )
    }
}

@Composable
private fun SwitchRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconColor: androidx.compose.ui.graphics.Color,
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    SettingsCardRow(
        icon = icon,
        iconColor = iconColor,
        title = title,
        subtitle = subtitle,
        enabled = enabled,
        toggle = checked,
        onToggle = onCheckedChange,
        trailingContent = {
            Switch(checked = checked, onCheckedChange = null, enabled = enabled)
        },
    )
}

@Composable
private fun DynamicColorRow(appearance: AppearanceState) {
    val available = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
    SettingsCardRow(
        icon = Icons.Outlined.Palette,
        iconColor = MaterialTheme.colorScheme.tertiary,
        title = stringResource(R.string.settings_dynamic_color),
        subtitle = stringResource(
            if (!available) {
                R.string.settings_dynamic_color_unavailable
            } else if (appearance.dynamicColor) {
                R.string.settings_dynamic_color_on
            } else {
                R.string.settings_dynamic_color_sub
            },
        ),
        enabled = available,
        toggle = appearance.dynamicColor && available,
        onToggle = AppearanceSettings::setDynamicColor,
        trailingContent = {
            Switch(
                checked = appearance.dynamicColor && available,
                onCheckedChange = null,
                enabled = available,
            )
        },
    )
}
@Composable
private fun InputBarSettings(appearance: AppearanceState) {
    Column {
        Surface(
            modifier = Modifier.fillMaxWidth().selectableGroup(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Row(Modifier.padding(4.dp).height(64.dp)) {
                listOf(ComposerStyle.IOS, ComposerStyle.Material).forEach { style ->
                    val selected = appearance.composerStyle == style
                    val shape = MaterialTheme.shapes.medium
                    Surface(
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerLow,
                        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        shape = shape,
                        modifier = Modifier.weight(1f).fillMaxHeight().clip(shape)
                            .selectable(selected, role = Role.RadioButton) {
                                AppearanceSettings.setComposerStyle(style)
                            },
                    ) {
                        Row(
                            Modifier.fillMaxSize().padding(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = composerStyleLabel(style),
                                style = MaterialTheme.typography.labelLarge,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                            if (selected) {
                                Icon(Icons.Outlined.Check, null, Modifier.padding(start = 8.dp).size(16.dp))
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        InputBarPreview(appearance)
        Spacer(Modifier.height(10.dp))
        SettingsTile(
            icon = Icons.Outlined.EmojiEmotions,
            title = stringResource(R.string.settings_input_bar_emoji_shortcut),
            subtitle = stringResource(R.string.settings_input_bar_emoji_shortcut_sub),
            iconColor = MaterialTheme.colorScheme.tertiary,
            position = ItemPosition.TOP,
            onClick = { AppearanceSettings.setShowEmojiButton(!appearance.showEmojiButton) },
            trailingContent = {
                Switch(
                    checked = appearance.showEmojiButton,
                    onCheckedChange = AppearanceSettings::setShowEmojiButton,
                )
            },
        )
        SettingsTile(
            icon = Icons.Outlined.Keyboard,
            title = stringResource(R.string.settings_input_bar_send_by_enter),
            subtitle = stringResource(R.string.settings_input_bar_send_by_enter_sub),
            iconColor = MaterialTheme.colorScheme.primary,
            position = ItemPosition.MIDDLE,
            onClick = { AppearanceSettings.setSendByEnter(!appearance.sendByEnter) },
            trailingContent = {
                Switch(
                    checked = appearance.sendByEnter,
                    onCheckedChange = AppearanceSettings::setSendByEnter,
                )
            },
        )
        SettingsTile(
            icon = Icons.Outlined.Link,
            title = stringResource(R.string.settings_input_bar_fix_previews),
            subtitle = stringResource(R.string.settings_input_bar_fix_previews_sub),
            iconColor = MaterialTheme.colorScheme.tertiary,
            position = ItemPosition.BOTTOM,
            onClick = { AppearanceSettings.setFixLinkPreviews(!appearance.fixLinkPreviews) },
            trailingContent = {
                Switch(
                    checked = appearance.fixLinkPreviews,
                    onCheckedChange = AppearanceSettings::setFixLinkPreviews,
                )
            },
        )
    }
}

@Composable
private fun InputBarPreview(appearance: AppearanceState) {
    val surface by animateColorAsState(
        targetValue = MaterialTheme.colorScheme.surfaceContainerLow,
        label = "inputPreviewSurface",
    )
    Surface(
        color = surface,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(
                        stringResource(R.string.settings_input_bar_reply),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        stringResource(R.string.settings_input_bar_reply_text),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            ChatComposerLayout(
                style = appearance.composerStyle,
                modifier = Modifier.fillMaxWidth(),
                showEmoji = appearance.showEmojiButton,
                attachEnabled = true,
                emojiEnabled = true,
                sendEnabled = true,
                sending = false,
                editing = false,
                attachDescription = stringResource(R.string.settings_input_bar_attach),
                emojiDescription = stringResource(R.string.settings_input_bar_emoji),
                sendDescription = stringResource(R.string.settings_input_bar_send),
                onAttach = {},
                onEmoji = {},
                onSend = {},
            ) {
                    BasicTextField(
                        value = stringResource(R.string.settings_input_bar_message),
                        onValueChange = {},
                        readOnly = true,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                    )
            }
        }
    }
}

@Composable
private fun composerStyleLabel(style: ComposerStyle): String = when (style) {
    ComposerStyle.IOS -> stringResource(R.string.settings_input_bar_ios)
    ComposerStyle.Material -> stringResource(R.string.settings_input_bar_material)
}

@Composable
private fun ChatListPreview(
    lines: Int,
    showAvatar: Boolean,
) {
    val bodyLines = (lines - 1).coerceAtLeast(1)
    val surface by animateColorAsState(
        targetValue = MaterialTheme.colorScheme.surface,
        label = "previewSurface",
    )
    val avatar by animateColorAsState(
        targetValue = MaterialTheme.colorScheme.primaryContainer,
        label = "previewAvatar",
    )
    Surface(
        color = surface,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .animateContentSize(),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnimatedVisibility(showAvatar) {
                Row {
                    Box(
                        modifier = Modifier.size(54.dp).clip(CircleShape).background(avatar),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Outlined.BookmarkBorder,
                            contentDescription = null,
                            modifier = Modifier.size(28.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.settings_preview_sample_title),
                        style = MaterialTheme.typography.titleMediumEmphasized,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.settings_preview_sample_time),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AnimatedContent(targetState = bodyLines, label = "chat-preview-lines") { count ->
                    Text(
                        text = stringResource(R.string.settings_preview_sample_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = count,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
@Composable
private fun AccentPicker(preset: AccentPreset, dynamic: Boolean, onSelect: (AccentPreset) -> Unit) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().selectableGroup(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(AccentPreset.entries, key = { it.name }) { accent ->
            val active = accent == preset
            val selected = !dynamic && active
            val ringWidth by animateDpAsState(if (selected) 2.5.dp else 0.dp, label = "accent-ring")
            val name = stringResource(
                when (accent) {
                    AccentPreset.Monogram -> R.string.settings_accent_monogram
                    AccentPreset.Sakura -> R.string.settings_accent_sakura
                    AccentPreset.Ocean -> R.string.settings_accent_ocean
                    AccentPreset.Forest -> R.string.settings_accent_forest
                    AccentPreset.Sunset -> R.string.settings_accent_sunset
                    AccentPreset.Violet -> R.string.settings_accent_violet
                    AccentPreset.Amber -> R.string.settings_accent_amber
                    AccentPreset.Rose -> R.string.settings_accent_rose
                    AccentPreset.Slate -> R.string.settings_accent_slate
                    AccentPreset.Teal -> R.string.settings_accent_teal
                },
            )
            Column(
                modifier = Modifier
                    .width(68.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .selectable(
                        selected = selected,
                        enabled = !dynamic,
                        role = Role.RadioButton,
                        onClick = { onSelect(accent) },
                    )
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .then(
                            if (ringWidth > 0.dp) {
                                Modifier.border(
                                    width = ringWidth,
                                    color = MaterialTheme.colorScheme.primary,
                                    shape = CircleShape,
                                )
                            } else {
                                Modifier
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(if (selected) 38.dp else 44.dp)
                            .clip(CircleShape)
                            .background(
                                color = if (dynamic) accentSwatch(accent).copy(alpha = 0.45f) else accentSwatch(accent),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        androidx.compose.animation.AnimatedVisibility(selected) {
                            Icon(
                                imageVector = Icons.Outlined.Check,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = Color.White,
                            )
                        }
                    }
                }
                Text(
                    text = name,
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    color = if (dynamic) {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    } else if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }
    }
}
@Composable
private fun themeLabel(pref: ThemePreference): String = when (pref) {
    ThemePreference.System -> stringResource(R.string.settings_theme_system)
    ThemePreference.Light -> stringResource(R.string.settings_theme_light)
    ThemePreference.Dark -> stringResource(R.string.settings_theme_dark)
}

