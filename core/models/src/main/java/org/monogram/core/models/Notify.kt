package org.monogram.core.models

enum class PushTokenType(val code: Int) {
    Fcm(2),
    Simple(4),
    WebPush(10),
    ;

    companion object {
        fun fromCode(code: Int): PushTokenType? = entries.firstOrNull { it.code == code }
    }
}

data class NotifySettings(
    val showPreviews: Boolean = true,
    val silent: Boolean = false,
    val muteUntil: Int = 0,
    val storiesMuted: Boolean = false,
    val storiesHideSender: Boolean = false,
    val sound: String = "default",
) {
    /** `mute_until` at or before [now] is unmuted; 0 is unmuted and `Int.MAX_VALUE` is forever. */
    fun isMuted(now: Int): Boolean = muteUntil > now
}

/**
 * Per-type notification defaults from `account.getNotifySettings`
 * (`users` / `chats` / `broadcasts`).
 */
data class NotifyDefaults(
    val users: NotifySettings = NotifySettings(),
    val chats: NotifySettings = NotifySettings(),
    val broadcasts: NotifySettings = NotifySettings(),
) {
    /**
     * Whether [chat] inherits a mute from its peer type. Telegram treats a dialog without its own
     * `mute_until` as inheriting the default for its type, and this is what the chat list icon and
     * the `exclude_muted` folder filter must reflect.
     */
    fun mutesInheritedBy(chat: Chat): Boolean = when {
        chat.isChannel && !chat.isGroup -> broadcasts.isMuted(nowSeconds())
        chat.isGroup -> chats.isMuted(nowSeconds())
        else -> users.isMuted(nowSeconds())
    }

    /** Effective mute: an explicit dialog setting wins, otherwise the type default applies. */
    fun mutes(chat: Chat): Boolean = if (chat.muteOverride) chat.muted else mutesInheritedBy(chat)
}

/** `mute_until` in the past or 0 means unmuted; `Int.MAX_VALUE` is Telegram's "forever". */
internal fun nowSeconds(): Int =
    (System.currentTimeMillis() / 1000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

data class NotifyException(
    val peerKind: String,
    val chatId: PeerId,
    val settings: NotifySettings,
)

data class PushDebugState(
    val transport: String = "none",
    val tokenRedacted: String = "",
    val gmsAvailable: Boolean = false,
    val distributor: String = "",
    val lastRegister: String = "",
    val lastLocKey: String = "",
    val lastCustomIds: String = "",
    val permissionGranted: Boolean = false,
)
