package org.monogram.core.common.push

/**
 * Channel variants Android needs for notification behaviour that is not expressible per
 * notification: on API 26+ the channel owns sound and importance, and importance decides whether a
 * notification peeks (heads-up). The variant id is derived from the behaviour, so the same
 * behaviour always maps to the same channel and an existing channel is never mutated (channel
 * settings are immutable after creation, and user edits in Android's settings stay untouched).
 */
object NotificationChannelPlan {
    const val SILENT = "_sil"
    const val NO_POPUP = "_np"
    const val SILENT_NO_POPUP = "_silnp"

    /**
     * Suffix appended to the base channel id. The empty suffix is the category channel managed by
     * `NotificationChannels.applyPrefs`, which already carries sound and popup behaviour.
     */
    fun suffix(sound: Boolean, popup: Boolean): String = when {
        !sound && !popup -> SILENT_NO_POPUP
        !sound -> SILENT
        !popup -> NO_POPUP
        else -> ""
    }

    /**
     * Importance for a channel variant: heads-up needs `High`; sound without a popup stays
     * `Default`; a fully silent notification drops to `Low` so it never peeks.
     */
    enum class Importance { High, Default, Low }

    fun importance(sound: Boolean, popup: Boolean): Importance = when {
        popup -> Importance.High
        sound -> Importance.Default
        else -> Importance.Low
    }
}
