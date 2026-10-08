package org.monogram.feature.dialog

/** Bottom bar shown instead of the composer when the user cannot write. */
enum class ReadOnlyBarAction {
    Unblock,
    Join,
    Mute,
    Unmute,
}

fun readOnlyBarAction(
    isChannel: Boolean,
    isGroup: Boolean,
    left: Boolean,
    muted: Boolean,
    blockedByMe: Boolean,
): ReadOnlyBarAction {
    val privateChat = !isChannel && !isGroup
    if (privateChat && blockedByMe) return ReadOnlyBarAction.Unblock
    if ((isChannel || isGroup) && left) return ReadOnlyBarAction.Join
    return if (muted) ReadOnlyBarAction.Unmute else ReadOnlyBarAction.Mute
}
