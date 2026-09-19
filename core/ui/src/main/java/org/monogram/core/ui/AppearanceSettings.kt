package org.monogram.core.ui

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import androidx.core.content.edit

enum class ThemePreference {
    System,
    Light,
    Dark,
}

enum class WallpaperMode { Monogram, None, Image }
enum class AccentPreset { Monogram, Sakura, Ocean, Forest, Sunset, Violet, Amber, Rose, Slate, Teal }
enum class ComposerStyle { IOS, Material }

internal fun restoreWallpaperMode(raw: String?, path: String?): WallpaperMode {
    val mode = WallpaperMode.entries.firstOrNull { it.name == raw }
        ?: if (path != null) WallpaperMode.Image else WallpaperMode.Monogram
    return if (mode == WallpaperMode.Image && path == null) WallpaperMode.Monogram else mode
}

data class AppearanceState(
    val theme: ThemePreference = ThemePreference.System,
    val dynamicColor: Boolean = true,
    val accentPreset: AccentPreset = AccentPreset.Monogram,
    val showMutedCounter: Boolean = false,
    val foldersAtBottom: Boolean = false,
    val showAllChats: Boolean = true,
    val previewLines: Int = 2,
    val showChatAvatars: Boolean = true,
    val openProfileOnAvatarTap: Boolean = true,
    val showReadStatus: Boolean = true,
    val wallpaperPath: String? = null,
    val wallpaperDim: Float = 0.15f,
    val wallpaperMode: WallpaperMode = WallpaperMode.Monogram,
    val composerStyle: ComposerStyle = ComposerStyle.Material,
    val showEmojiButton: Boolean = true,
    val sendByEnter: Boolean = false,
    val ivFontSize: Int = AppearanceSettings.DEFAULT_IV_FONT_SIZE,
    val messageTextSize: Int = AppearanceSettings.DEFAULT_MESSAGE_TEXT_SIZE,
    val lineSpacing: Float = AppearanceSettings.DEFAULT_LINE_SPACING,
    val letterSpacing: Float = AppearanceSettings.DEFAULT_LETTER_SPACING,
    /** List-detail pane width in dp; 0 means "use the responsive default". */
    val listPaneWidth: Int = AppearanceSettings.DEFAULT_LIST_PANE_WIDTH,
) {
    /** Preview body lines under the chat title (2-line row -> 1, 3-line row -> 2). */
    val previewTextLines: Int get() = (previewLines - 1).coerceAtLeast(1)
}

object AppearanceSettings {
    const val DEFAULT_PREVIEW_LINES = 2
    const val MIN_PREVIEW_LINES = 2
    const val MAX_PREVIEW_LINES = 3
    const val DEFAULT_LIST_PANE_WIDTH = 0
    const val MIN_LIST_PANE_WIDTH = 320
    const val MAX_LIST_PANE_WIDTH = 600
    const val DEFAULT_IV_FONT_SIZE = 16
    const val MIN_IV_FONT_SIZE = 12
    const val MAX_IV_FONT_SIZE = 30
    const val DEFAULT_MESSAGE_TEXT_SIZE = 16
    const val MIN_MESSAGE_TEXT_SIZE = 12
    const val MAX_MESSAGE_TEXT_SIZE = 30
    const val DEFAULT_LINE_SPACING = 1f
    const val MIN_LINE_SPACING = 0.9f
    const val MAX_LINE_SPACING = 1.5f
    const val DEFAULT_LETTER_SPACING = 0f
    const val MIN_LETTER_SPACING = 0f
    const val MAX_LETTER_SPACING = 0.08f

    private const val PREFS = "monogram_appearance"
    private const val KEY_THEME = "theme"
    private const val KEY_DYNAMIC = "dynamic_color"
    private const val KEY_MUTED_COUNTER = "show_muted_counter"
    private const val KEY_FOLDERS_AT_BOTTOM = "folders_at_bottom"
    private const val KEY_SHOW_ALL_CHATS = "show_all_chats"
    private const val KEY_MESSAGE_TEXT_SIZE = "message_text_size"
    private const val KEY_LINE_SPACING = "line_spacing"
    private const val KEY_LETTER_SPACING = "letter_spacing"
    private const val KEY_PREVIEW_LINES = "preview_lines"
    private const val KEY_CHAT_AVATARS = "show_chat_avatars"
    private const val KEY_PROFILE_ON_AVATAR_TAP = "open_profile_on_avatar_tap"
    private const val KEY_READ_STATUS = "show_read_status"
    private const val KEY_COMPOSER_STYLE = "composer_style"
    private const val KEY_SHOW_EMOJI_BUTTON = "show_emoji_button"
    private const val KEY_SEND_BY_ENTER = "send_by_enter"
    private const val KEY_IV_FONT_SIZE = "iv_font_size"
    private const val KEY_LIST_PANE_WIDTH = "list_pane_width"

    private val mutable = MutableStateFlow(AppearanceState())
    val state: StateFlow<AppearanceState> = mutable.asStateFlow()

    @Volatile
    private var appContext: Context? = null

    fun install(context: Context) {
        val app = context.applicationContext
        appContext = app
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val wallpaperPath = prefs.getString("wallpaper_path", null)?.takeIf { java.io.File(it).isFile }
        mutable.value = AppearanceState(
            theme = parseTheme(prefs.getString(KEY_THEME, null)),
            dynamicColor = prefs.getBoolean(KEY_DYNAMIC, true),
            accentPreset = parseAccent(prefs.getString("accent_preset", null)),
            showMutedCounter = prefs.getBoolean(KEY_MUTED_COUNTER, false),
            foldersAtBottom = prefs.getBoolean(KEY_FOLDERS_AT_BOTTOM, false),
            showAllChats = prefs.getBoolean(KEY_SHOW_ALL_CHATS, true),
            previewLines = parsePreviewLines(prefs.getInt(KEY_PREVIEW_LINES, DEFAULT_PREVIEW_LINES)),
            showChatAvatars = prefs.getBoolean(KEY_CHAT_AVATARS, true),
            openProfileOnAvatarTap = prefs.getBoolean(KEY_PROFILE_ON_AVATAR_TAP, true),
            showReadStatus = prefs.getBoolean(KEY_READ_STATUS, true),
            wallpaperPath = wallpaperPath,
            wallpaperMode = restoreWallpaperMode(prefs.getString("wallpaper_mode", null), wallpaperPath),
            wallpaperDim = prefs.getFloat("wallpaper_dim", 0.15f).coerceIn(0f, 0.8f),
            composerStyle = parseComposerStyle(prefs.getString(KEY_COMPOSER_STYLE, null)),
            showEmojiButton = prefs.getBoolean(KEY_SHOW_EMOJI_BUTTON, true),
            sendByEnter = prefs.getBoolean(KEY_SEND_BY_ENTER, false),
            ivFontSize = parseIvFontSize(prefs.getInt(KEY_IV_FONT_SIZE, DEFAULT_IV_FONT_SIZE)),
            messageTextSize = parseMessageTextSize(
                prefs.getInt(KEY_MESSAGE_TEXT_SIZE, DEFAULT_MESSAGE_TEXT_SIZE),
            ),
            lineSpacing = parseLineSpacing(
                prefs.getFloat(KEY_LINE_SPACING, DEFAULT_LINE_SPACING),
            ),
            letterSpacing = parseLetterSpacing(
                prefs.getFloat(KEY_LETTER_SPACING, DEFAULT_LETTER_SPACING),
            ),
            listPaneWidth = parseListPaneWidth(prefs.getInt(KEY_LIST_PANE_WIDTH, DEFAULT_LIST_PANE_WIDTH)),
        )
    }

    fun setTheme(theme: ThemePreference) {
        mutable.update { it.copy(theme = theme) }
        persist()
    }

    fun setWallpaper(path: String?, dim: Float, mode: WallpaperMode) {
        require(mode != WallpaperMode.Image || path != null)
        mutable.update { it.copy(
            wallpaperPath = if (mode == WallpaperMode.Image) path else null,
            wallpaperDim = dim.coerceIn(0f, 0.8f),
            wallpaperMode = mode,
        ) }
        persist()
    }

    fun setDynamicColor(enabled: Boolean) {
        mutable.update { it.copy(dynamicColor = enabled) }
        persist()
    }

    fun setAccentPreset(preset: AccentPreset) {
        mutable.update { it.copy(accentPreset = preset, dynamicColor = false) }
        persist()
    }

    fun setShowMutedCounter(enabled: Boolean) {
        mutable.update { it.copy(showMutedCounter = enabled) }
        persist()
    }

    fun setFoldersAtBottom(enabled: Boolean) {
        mutable.update { it.copy(foldersAtBottom = enabled) }
        persist()
    }

    fun setShowAllChats(enabled: Boolean) {
        mutable.update { it.copy(showAllChats = enabled) }
        persist()
    }

    fun setPreviewLines(lines: Int) {
        mutable.update { it.copy(previewLines = parsePreviewLines(lines)) }
        persist()
    }

    fun setShowChatAvatars(enabled: Boolean) {
        mutable.update { it.copy(showChatAvatars = enabled) }
        persist()
    }

    fun setOpenProfileOnAvatarTap(enabled: Boolean) {
        mutable.update { it.copy(openProfileOnAvatarTap = enabled) }
        persist()
    }

    fun setShowReadStatus(enabled: Boolean) {
        mutable.update { it.copy(showReadStatus = enabled) }
        persist()
    }

    fun setComposerStyle(style: ComposerStyle) {
        mutable.update { it.copy(composerStyle = style) }
        persist()
    }

    fun setShowEmojiButton(enabled: Boolean) {
        mutable.update { it.copy(showEmojiButton = enabled) }
        persist()
    }

    fun setSendByEnter(enabled: Boolean) {
        mutable.update { it.copy(sendByEnter = enabled) }
        persist()
    }

    fun setListPaneWidth(width: Int) {
        mutable.update { it.copy(listPaneWidth = parseListPaneWidth(width)) }
        persist()
    }

    fun setIvFontSize(size: Int) {
        mutable.update { it.copy(ivFontSize = parseIvFontSize(size)) }
        persist()
    }

    fun setMessageTextSize(size: Int) {
        mutable.update { it.copy(messageTextSize = parseMessageTextSize(size)) }
        persist()
    }

    fun setLineSpacing(spacing: Float) {
        mutable.update { it.copy(lineSpacing = parseLineSpacing(spacing)) }
        persist()
    }

    fun setLetterSpacing(spacing: Float) {
        mutable.update { it.copy(letterSpacing = parseLetterSpacing(spacing)) }
        persist()
    }

    private fun persist() {
        val prefs = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE) ?: return
        val current = mutable.value
        prefs.edit {
            putString(KEY_THEME, current.theme.name)
                .putBoolean(KEY_DYNAMIC, current.dynamicColor)
                .putString("accent_preset", current.accentPreset.name)
                .putBoolean(KEY_MUTED_COUNTER, current.showMutedCounter)
                .putBoolean(KEY_FOLDERS_AT_BOTTOM, current.foldersAtBottom)
                .putBoolean(KEY_SHOW_ALL_CHATS, current.showAllChats)
                .putInt(KEY_PREVIEW_LINES, current.previewLines)
                .putBoolean(KEY_CHAT_AVATARS, current.showChatAvatars)
                .putBoolean(KEY_PROFILE_ON_AVATAR_TAP, current.openProfileOnAvatarTap)
                .putBoolean(KEY_READ_STATUS, current.showReadStatus)
                .putString("wallpaper_path", current.wallpaperPath)
                .putFloat("wallpaper_dim", current.wallpaperDim)
                .putString("wallpaper_mode", current.wallpaperMode.name)
                .putString(KEY_COMPOSER_STYLE, current.composerStyle.name)
                .putBoolean(KEY_SHOW_EMOJI_BUTTON, current.showEmojiButton)
                .putBoolean(KEY_SEND_BY_ENTER, current.sendByEnter)
                .putInt(KEY_IV_FONT_SIZE, current.ivFontSize)
                .putInt(KEY_MESSAGE_TEXT_SIZE, current.messageTextSize)
                .putFloat(KEY_LINE_SPACING, current.lineSpacing)
                .putFloat(KEY_LETTER_SPACING, current.letterSpacing)
                .putInt(KEY_LIST_PANE_WIDTH, current.listPaneWidth)
        }
    }

    internal fun parseListPaneWidth(raw: Int): Int =
        if (raw <= 0) DEFAULT_LIST_PANE_WIDTH else raw.coerceIn(MIN_LIST_PANE_WIDTH, MAX_LIST_PANE_WIDTH)

    internal fun parseTheme(raw: String?): ThemePreference =
        ThemePreference.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
            ?: ThemePreference.System

    internal fun parseAccent(raw: String?): AccentPreset =
        AccentPreset.entries.firstOrNull { it.name == raw } ?: AccentPreset.Monogram

    internal fun parsePreviewLines(raw: Int): Int =
        raw.coerceIn(MIN_PREVIEW_LINES, MAX_PREVIEW_LINES)

    internal fun parseIvFontSize(raw: Int): Int =
        raw.coerceIn(MIN_IV_FONT_SIZE, MAX_IV_FONT_SIZE)

    internal fun parseMessageTextSize(raw: Int): Int =
        raw.coerceIn(MIN_MESSAGE_TEXT_SIZE, MAX_MESSAGE_TEXT_SIZE)

    internal fun parseLineSpacing(raw: Float): Float {
        val clamped = raw.coerceIn(MIN_LINE_SPACING, MAX_LINE_SPACING)
        return kotlin.math.round(clamped * 20f) / 20f
    }

    internal fun parseLetterSpacing(raw: Float): Float {
        val clamped = raw.coerceIn(MIN_LETTER_SPACING, MAX_LETTER_SPACING)
        return kotlin.math.round(clamped * 100f) / 100f
    }

    internal fun parseComposerStyle(raw: String?): ComposerStyle = when {
        raw.equals(ComposerStyle.IOS.name, ignoreCase = true) -> ComposerStyle.IOS
        else -> ComposerStyle.Material
    }
}
