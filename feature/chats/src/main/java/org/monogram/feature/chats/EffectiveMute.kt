package org.monogram.feature.chats

import org.monogram.core.models.Chat
import org.monogram.core.models.NotifyDefaults
import org.monogram.core.models.NotifySettings

/** `account.updateNotifySettings` peer kind that targets a single dialog. */
internal const val PEER_NOTIFY_KIND = "peer"

/**
 * Applies mute state to dialog rows.
 *
 * Room stores the *effective* flag (`chats.muted`) next to `muteOverride`, which says whether the
 * dialog carries its own setting. Before the account defaults arrive the cached effective value is
 * the only truth available, so those rows are passed through untouched; once the defaults are known
 * the inherited value is recomputed. A dialog's own setting always wins.
 */
internal fun withEffectiveMutes(
    chats: List<Chat>,
    defaults: NotifyDefaults,
    defaultsLoaded: Boolean,
): List<Chat> = if (!defaultsLoaded) chats else chats.map { it.withEffectiveMute(defaults) }

private fun Chat.withEffectiveMute(defaults: NotifyDefaults): Chat {
    val muted = defaults.mutes(this)
    return if (muted == this.muted) this else copy(muted = muted)
}

/**
 * The dialog's own setting after `account.updateNotifySettings` for one peer: the row shows the new
 * bell immediately and is marked as carrying an override, matching what the server reports next.
 */
internal fun Chat.withOwnMute(muteUntil: Int, nowSeconds: Int): Chat {
    val muted = NotifySettings(muteUntil = muteUntil).isMuted(nowSeconds)
    return if (muted == this.muted && muteOverride) this else copy(muted = muted, muteOverride = true)
}

/** `mute_until` is compared against Telegram seconds, so the local clock is enough here. */
internal fun nowEpochSeconds(): Int =
    (System.currentTimeMillis() / 1000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
