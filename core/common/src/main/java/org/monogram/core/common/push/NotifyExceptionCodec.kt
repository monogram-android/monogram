package org.monogram.core.common.push

import org.monogram.core.models.NotifyException
import org.monogram.core.models.PeerId

/**
 * Compact storage for [NotifyException] rows: `peerKind<TAB>chatId<TAB>settings`.
 * Settings reuse [NotifySettingsCodec], so a tab separator cannot collide with `|`.
 */
object NotifyExceptionCodec {
    private const val SEPARATOR = '\t'

    fun encode(item: NotifyException): String =
        item.peerKind + SEPARATOR + item.chatId.value + SEPARATOR +
            NotifySettingsCodec.encode(item.settings)

    fun encodeAll(items: List<NotifyException>): String =
        items.joinToString("\n") { encode(it) }

    fun decode(raw: String?): NotifyException? {
        val parts = raw?.split(SEPARATOR, limit = 3) ?: return null
        if (parts.size < 3) return null
        val chatId = parts[1].toLongOrNull() ?: return null
        val settings = NotifySettingsCodec.decode(parts[2]) ?: return null
        val kind = parts[0].trim()
        if (kind.isEmpty()) return null
        return NotifyException(peerKind = kind, chatId = PeerId(chatId), settings = settings)
    }

    fun decodeAll(raw: String?): List<NotifyException> =
        raw?.lineSequence()?.mapNotNull { decode(it) }?.toList().orEmpty()
}
