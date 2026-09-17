package org.monogram.core.common.push

/** Badge-related notification settings: show a count at all, and count messages instead of chats. */
data class BadgeSettings(
    val enabled: Boolean = true,
    val countMessages: Boolean = true,
)

/**
 * Notification `number` for a collapsed notification. Android derives the launcher badge from the
 * notification number (and the shortcut), so "no badge" is expressed as 0 and "count chats" as 1
 * per chat with something unread.
 */
object NotificationBadgePlan {
    fun number(messages: Int, settings: BadgeSettings): Int = when {
        !settings.enabled -> 0
        settings.countMessages -> messages.coerceAtLeast(0)
        else -> if (messages > 0) 1 else 0
    }
}
