package org.monogram.core.common.push

/**
 * Local per-chat notification mode. Mirrors the parts of Telegram's chat notification screen that
 * Android can apply without server round-trips: mute duration, preview, sound and popup.
 *
 * A chat without an entry uses the category defaults, so this type is only stored when the user
 * changes something. Extend by adding fields with defaults; [PeerNotificationModeCodec] keeps the
 * stored form forward compatible.
 */
data class PeerNotificationMode(
    val muteUntil: Int = 0,
    val preview: Boolean = true,
    val sound: Boolean = true,
    val popup: Boolean = true,
) {
    fun isMuted(nowSeconds: Int): Boolean = muteUntil > nowSeconds

    val isDefault: Boolean
        get() = this == Default

    companion object {
        val Default = PeerNotificationMode()
    }
}

/**
 * Compact storage form for [PeerNotificationMode]: `peerId|muteUntil|preview|sound|popup`.
 * Fields are positional and flags are `1`/`0` so an older string still decodes when a trailing
 * field is added.
 */
object PeerNotificationModeCodec {
    private const val SEPARATOR = '|'

    fun encode(peerId: Long, mode: PeerNotificationMode): String = buildString {
        append(peerId).append(SEPARATOR)
        append(mode.muteUntil).append(SEPARATOR)
        append(flag(mode.preview)).append(SEPARATOR)
        append(flag(mode.sound)).append(SEPARATOR)
        append(flag(mode.popup))
    }

    fun decode(raw: String?): Pair<Long, PeerNotificationMode>? {
        val parts = raw?.trim()?.split(SEPARATOR) ?: return null
        if (parts.size < 2) return null
        val peerId = parts[0].toLongOrNull() ?: return null
        val model = PeerNotificationMode(
            muteUntil = parts.getOrNull(1)?.toIntOrNull() ?: 0,
            preview = flag(parts.getOrNull(2)),
            sound = flag(parts.getOrNull(3)),
            popup = flag(parts.getOrNull(4)),
        )
        return peerId to model
    }

    private fun flag(value: Boolean): String = if (value) "1" else "0"

    /**
     * Adds, replaces or removes the entry for [peerId] in the stored set. Passing `null` or a
     * default mode removes the entry, so only chats the user actually customised are persisted.
     */
    fun upsert(entries: Set<String>, peerId: Long, mode: PeerNotificationMode?): Set<String> {
        val kept = entries
            .mapNotNull { decode(it) }
            .filterNot { it.first == peerId }
            .toMutableList()
        if (mode != null && !mode.isDefault) kept.add(peerId to mode)
        return kept.map { encode(it.first, it.second) }.toSet()
    }

    private fun flag(raw: String?): Boolean = raw != "0"
}
