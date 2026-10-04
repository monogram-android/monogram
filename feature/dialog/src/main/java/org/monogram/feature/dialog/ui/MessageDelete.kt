package org.monogram.feature.dialog.ui

import org.monogram.core.models.ForumIo
import org.monogram.core.models.Message
import org.monogram.feature.dialog.DialogStore

internal const val DeleteRevokeLimitSeconds = 172_800L

internal data class DeleteOffer(
    val forMe: Boolean,
    val forEveryone: Boolean,
) {
    val visible: Boolean get() = forMe || forEveryone
}

internal fun deleteRevoke(forEveryone: Boolean): Boolean = forEveryone

internal fun deleteOffer(
    state: DialogStore.State,
    messages: List<Message>,
    nowSeconds: Long,
): DeleteOffer {
    if (messages.isEmpty()) return DeleteOffer(forMe = false, forEveryone = false)
    val offers = messages.map { deleteOffer(state, it, nowSeconds) }
    if (offers.any { !it.visible }) return DeleteOffer(forMe = false, forEveryone = false)
    return DeleteOffer(
        forMe = offers.all { it.forMe },
        forEveryone = offers.all { it.forEveryone },
    )
}

internal fun deleteOffer(
    state: DialogStore.State,
    message: Message,
    nowSeconds: Long,
): DeleteOffer {
    if (!canDeleteMessage(state, message)) return DeleteOffer(forMe = false, forEveryone = false)
    // Broadcasts and megagroups both use channels.deleteMessages, which has no revoke flag
    if (channelDelete(state)) return DeleteOffer(forMe = false, forEveryone = true)
    if (state.isSelf) return DeleteOffer(forMe = true, forEveryone = false)
    val fresh = message.date > 0L && nowSeconds - message.date < DeleteRevokeLimitSeconds
    val forEveryone = fresh && !state.isBot && (message.outgoing || (state.isGroup && state.canDeleteOthers))
    return DeleteOffer(forMe = true, forEveryone = forEveryone)
}

internal fun canDeleteMessage(state: DialogStore.State, message: Message): Boolean {
    if (message.pending || message.id.id <= 0) return false
    if (!channelDelete(state)) return true
    if (message.id.id == 1) return false
    if (message.mediaKind == "service") return state.canDeleteOthers
    return message.outgoing || state.canDeleteOthers
}

private fun channelDelete(state: DialogStore.State): Boolean =
    state.isChannel || ForumIo.isChannelPeer(state.chatId)