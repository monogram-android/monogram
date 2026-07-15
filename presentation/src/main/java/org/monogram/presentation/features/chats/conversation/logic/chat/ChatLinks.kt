package org.monogram.presentation.features.chats.conversation.logic

import org.monogram.core.telegram.TelegramLinkDomains
import org.monogram.domain.repository.TelegramLinkRepository
import org.monogram.presentation.features.chats.conversation.ChatComponent

internal fun ChatComponent.State.preferredTelegramUsername(): String? {
    chatUsername.normalizeTelegramUsername()?.let { return it }
    if (!isGroup && !isChannel) {
        otherUser?.username.normalizeTelegramUsername()?.let { return it }
    }
    return null
}

internal fun ChatComponent.State.preferredTelegramInviteLink(): String? {
    return chatInviteLink?.trim()?.takeIf { it.isNotBlank() }
}

internal fun ChatComponent.State.hasCopyableTelegramLink(): Boolean {
    return preferredTelegramUsername() != null || preferredTelegramInviteLink() != null
}

internal suspend fun TelegramLinkRepository.rewriteTelegramLink(link: String): String {
    val normalized = link.trim()
    val path = TelegramLinkDomains.extractPathAndQuery(normalized) ?: return normalized
    return buildUrl(path)
}

internal fun String?.normalizeTelegramUsername(): String? {
    return this?.trim()?.removePrefix("@")?.takeIf { it.isNotBlank() }
}
