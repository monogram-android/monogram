package org.monogram.feature.dialog

/** Bottom bar shown instead of the composer when the user cannot write. */
enum class ReadOnlyBarAction {
    Unblock,
    Join,
    Mute,
    Unmute,
    Restricted,
}

fun readOnlyBarAction(
    isChannel: Boolean,
    isGroup: Boolean,
    left: Boolean,
    muted: Boolean,
    blockedByMe: Boolean,
    accessKnown: Boolean = false,
    canView: Boolean = true,
    canSendPlain: Boolean = true,
    canSendPhotos: Boolean = true,
    canSendStructured: Boolean = false,
): ReadOnlyBarAction {
    val privateChat = !isChannel && !isGroup
    if (privateChat && blockedByMe) return ReadOnlyBarAction.Unblock
    if ((isChannel || isGroup) && left) return ReadOnlyBarAction.Join
    if (accessKnown && canView && !canSendPlain && !canSendPhotos && !canSendStructured) {
        return ReadOnlyBarAction.Restricted
    }
    return if (muted) ReadOnlyBarAction.Unmute else ReadOnlyBarAction.Mute
}
