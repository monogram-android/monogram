package org.monogram.core.models

/** True when the row has a server id and is not a pending or service message. */
fun Message.isForwardSourceShape(): Boolean =
    id.id > 0 && !pending && mediaKind != "service"

/**
 * Whether this message can leave its chat via `messages.forwardMessages`.
 * Chat-wide content protection is [chatCanForward] (`Chat.canForward` / `noforwards`);
 * Telegram does not set [Message.noforwards] for that case.
 */
fun Message.canForwardFrom(chatCanForward: Boolean): Boolean =
    chatCanForward && isForwardSourceShape() && !noforwards

/** Destination needs `send_photos` when the payload is visual media. */
fun Message.requiresForwardPhotoRight(): Boolean = when (mediaKind) {
    "photo", "video", "gif" -> true
    else -> false
}

/**
 * Whether [this] chat can receive a forward or share.
 * Saved Messages stays eligible even when send flags are stale.
 */
fun Chat.canReceiveForward(
    requiresPhotos: Boolean,
    savedMessages: Boolean = false,
): Boolean {
    if (!canView) return false
    if (savedMessages) return true
    if (left) return false
    if (!canSendPlain) return false
    if (requiresPhotos && !canSendPhotos) return false
    return true
}

/** Production recipient-picker predicate, including Saved Messages identity. */
fun Chat.canSelectAsForwardRecipient(
    requiresPhotos: Boolean,
    selfPeerId: PeerId?,
): Boolean = canReceiveForward(
    requiresPhotos = requiresPhotos,
    savedMessages = selfPeerId != null && id == selfPeerId,
)

fun looksLikeFilesystemPath(value: String): Boolean {
    val text = value.trim()
    if (text.isEmpty() || '\n' in text || '\r' in text) return false
    val lower = text.lowercase()
    if (lower.startsWith("content://") || lower.startsWith("file:")) return true
    if (text.startsWith("\\\\")) return true
    if (text.length >= 3 && text[0].isLetter() && text[1] == ':' &&
        (text[2] == '\\' || text[2] == '/')
    ) {
        return true
    }
    if (text.startsWith("/")) {
        val name = text.substringAfterLast('/')
        return '/' in text.drop(1) && '.' in name
    }
    return false
}

/** Drop share/comment text that is only a local path so it is never sent as a message. */
fun userFacingShareText(value: String): String =
    value.trim().takeUnless(::looksLikeFilesystemPath).orEmpty()
