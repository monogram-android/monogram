package org.monogram.core.common.push

import org.monogram.core.models.NotifySettings

/**
 * Compact storage form for [NotifySettings]: `muteUntil|showPreviews|silent|storiesMuted|`
 * `storiesHideSender|sound`.
 *
 * Fields are positional and flags are `1`/`0`, so an older string still decodes when a trailing
 * field is added. [sound][NotifySettings.sound] is written last because it is free-form text.
 */
object NotifySettingsCodec {
    private const val SEPARATOR = '|'
    private const val FIELD_COUNT = 6

    fun encode(settings: NotifySettings): String = buildString {
        append(settings.muteUntil).append(SEPARATOR)
        append(flag(settings.showPreviews)).append(SEPARATOR)
        append(flag(settings.silent)).append(SEPARATOR)
        append(flag(settings.storiesMuted)).append(SEPARATOR)
        append(flag(settings.storiesHideSender)).append(SEPARATOR)
        append(settings.sound)
    }

    fun decode(raw: String?): NotifySettings? {
        val parts = raw?.trim()?.split(SEPARATOR, limit = FIELD_COUNT) ?: return null
        if (parts.isEmpty() || parts[0].isBlank()) return null
        val muteUntil = parts[0].toIntOrNull() ?: return null
        return NotifySettings(
            muteUntil = muteUntil,
            showPreviews = flag(parts.getOrNull(1)),
            silent = flag(parts.getOrNull(2)),
            storiesMuted = flag(parts.getOrNull(3)),
            storiesHideSender = flag(parts.getOrNull(4)),
            sound = parts.getOrNull(5)?.takeIf { it.isNotBlank() } ?: NotifySettings().sound,
        )
    }

    private fun flag(value: Boolean): String = if (value) "1" else "0"

    private fun flag(raw: String?): Boolean = raw != "0"
}
