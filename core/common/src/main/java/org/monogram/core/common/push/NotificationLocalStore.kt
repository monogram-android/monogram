package org.monogram.core.common.push

import android.content.Context
import android.content.SharedPreferences
import org.monogram.core.models.NotifyDefaults
import org.monogram.core.models.NotifyException
import org.monogram.core.models.NotifySettings
import org.monogram.core.models.PushDebugState
import org.monogram.core.models.PushTokenType

class NotificationLocalStore(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var inAppSound: Boolean
        get() = prefs.getBoolean(INAPP_SOUND, true)
        set(value) { prefs.edit().putBoolean(INAPP_SOUND, value).apply() }
    var inAppVibrate: Boolean
        get() = prefs.getBoolean(INAPP_VIBRATE, true)
        set(value) { prefs.edit().putBoolean(INAPP_VIBRATE, value).apply() }
    var inAppPreview: Boolean
        get() = prefs.getBoolean(INAPP_PREVIEW, true)
        set(value) { prefs.edit().putBoolean(INAPP_PREVIEW, value).apply() }
    var inChatSound: Boolean
        get() = prefs.getBoolean(INCHAT_SOUND, true)
        set(value) { prefs.edit().putBoolean(INCHAT_SOUND, value).apply() }
    var inAppPriority: Boolean
        get() = prefs.getBoolean(INAPP_PRIORITY, true)
        set(value) { prefs.edit().putBoolean(INAPP_PRIORITY, value).apply() }
    var pinnedEnabled: Boolean
        get() = prefs.getBoolean(PINNED, true)
        set(value) { prefs.edit().putBoolean(PINNED, value).apply() }
    var contactJoinedEnabled: Boolean
        get() = prefs.getBoolean(CONTACT_JOINED, true)
        set(value) { prefs.edit().putBoolean(CONTACT_JOINED, value).apply() }
    var storiesEnabled: Boolean
        get() = prefs.getBoolean(STORIES, true)
        set(value) { prefs.edit().putBoolean(STORIES, value).apply() }
    var reactionsEnabled: Boolean
        get() = prefs.getBoolean(REACTIONS, true)
        set(value) { prefs.edit().putBoolean(REACTIONS, value).apply() }
    var giftsEnabled: Boolean
        get() = prefs.getBoolean(GIFTS, true)
        set(value) { prefs.edit().putBoolean(GIFTS, value).apply() }
    var badgeEnabled: Boolean
        get() = prefs.getBoolean(BADGE, true)
        set(value) { prefs.edit().putBoolean(BADGE, value).apply() }
    var badgeMuted: Boolean
        get() = prefs.getBoolean(BADGE_MUTED, true)
        set(value) { prefs.edit().putBoolean(BADGE_MUTED, value).apply() }
    var badgeMessages: Boolean
        get() = prefs.getBoolean(BADGE_MESSAGES, true)
        set(value) { prefs.edit().putBoolean(BADGE_MESSAGES, value).apply() }
    var repeatMinutes: Int
        get() = prefs.getInt(REPEAT, 0)
        set(value) { prefs.edit().putInt(REPEAT, value).apply() }
    var callsVibrate: String
        get() = prefs.getString(CALLS_VIBRATE, "default").orEmpty()
        set(value) { prefs.edit().putString(CALLS_VIBRATE, value).apply() }
    var callsRingtone: String
        get() = prefs.getString(CALLS_RINGTONE, "default").orEmpty()
        set(value) { prefs.edit().putString(CALLS_RINGTONE, value).apply() }
    var noMuted: Boolean
        get() = prefs.getBoolean(NO_MUTED, true)
        set(value) { prefs.edit().putBoolean(NO_MUTED, value).apply() }
    var appSandbox: Boolean
        get() = prefs.getBoolean(APP_SANDBOX, false)
        set(value) { prefs.edit().putBoolean(APP_SANDBOX, value).apply() }
    var lastShownChatId: Long
        get() = prefs.getLong(LAST_SHOWN_CHAT, 0L)
        set(value) { prefs.edit().putLong(LAST_SHOWN_CHAT, value).apply() }

    fun categoryVibrate(kind: String): Boolean = prefs.getBoolean("${kind}_vibrate", true)
    fun setCategoryVibrate(kind: String, value: Boolean) {
        prefs.edit().putBoolean("${kind}_vibrate", value).apply()
    }
    fun categoryLed(kind: String): Boolean = prefs.getBoolean("${kind}_led", true)
    fun setCategoryLed(kind: String, value: Boolean) {
        prefs.edit().putBoolean("${kind}_led", value).apply()
    }
    fun categoryPriorityHigh(kind: String): Boolean = prefs.getBoolean("${kind}_priority", true)
    fun setCategoryPriorityHigh(kind: String, value: Boolean) {
        prefs.edit().putBoolean("${kind}_priority", value).apply()
    }
    /** Whether notifications of this category may be shown as a heads-up popup. */
    fun categoryPopup(kind: String): Boolean = prefs.getBoolean("${kind}_popup", true)
    fun setCategoryPopup(kind: String, value: Boolean) {
        prefs.edit().putBoolean("${kind}_popup", value).apply()
    }

    fun mutedFolders(): Set<Int> =
        prefs.getStringSet(MUTED_FOLDERS, emptySet())
            ?.mapNotNull { it.toIntOrNull() }
            ?.toSet()
            .orEmpty()

    /** Badge settings used for notification numbers (badge show/count, from the settings screen). */
    fun badgeSettings(): BadgeSettings = BadgeSettings(enabled = badgeEnabled, countMessages = badgeMessages)

    /** Local per-chat notification modes; chats without an entry follow their category. */
    fun peerModes(): Map<Long, PeerNotificationMode> =
        prefs.getStringSet(PEER_MODES, emptySet())
            .orEmpty()
            .mapNotNull { PeerNotificationModeCodec.decode(it) }
            .toMap()

    fun peerMode(chatId: Long): PeerNotificationMode? = peerModes()[chatId]

    fun setPeerMode(chatId: Long, mode: PeerNotificationMode?) {
        val next = PeerNotificationModeCodec.upsert(prefs.getStringSet(PEER_MODES, emptySet()).orEmpty(), chatId, mode)
        prefs.edit().putStringSet(PEER_MODES, next).apply()
    }

    fun setFolderMuted(folderId: Int, muted: Boolean) {
        val next = mutedFolders().toMutableSet()
        if (muted) next.add(folderId) else next.remove(folderId)
        prefs.edit().putStringSet(MUTED_FOLDERS, next.map { it.toString() }.toSet()).apply()
    }

    /**
     * Account-level notification defaults (`account.getNotifySettings` for users / chats /
     * broadcasts). Cached so the chat list and push decisions keep the right mute state when the app
     * starts without a network round-trip; `null` means they were never loaded yet.
     */
    var notifyDefaults: NotifyDefaults?
        get() {
            val users = NotifySettingsCodec.decode(prefs.getString(NOTIFY_USERS, null))
            val chats = NotifySettingsCodec.decode(prefs.getString(NOTIFY_CHATS, null))
            val broadcasts = NotifySettingsCodec.decode(prefs.getString(NOTIFY_BROADCASTS, null))
            if (users == null && chats == null && broadcasts == null) return null
            return NotifyDefaults(
                users = users ?: NotifySettings(),
                chats = chats ?: NotifySettings(),
                broadcasts = broadcasts ?: NotifySettings(),
            )
        }
        set(value) {
            prefs.edit().apply {
                if (value == null) {
                    remove(NOTIFY_USERS)
                    remove(NOTIFY_CHATS)
                    remove(NOTIFY_BROADCASTS)
                } else {
                    putString(NOTIFY_USERS, NotifySettingsCodec.encode(value.users))
                    putString(NOTIFY_CHATS, NotifySettingsCodec.encode(value.chats))
                    putString(NOTIFY_BROADCASTS, NotifySettingsCodec.encode(value.broadcasts))
                }
            }.apply()
        }

    /** Cached `account.getNotifyExceptions` rows. `null` means they were never stored. */
    var notifyExceptions: List<NotifyException>?
        get() {
            if (!prefs.contains(NOTIFY_EXCEPTIONS)) return null
            return NotifyExceptionCodec.decodeAll(prefs.getString(NOTIFY_EXCEPTIONS, null))
        }
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(NOTIFY_EXCEPTIONS)
                else putString(NOTIFY_EXCEPTIONS, NotifyExceptionCodec.encodeAll(value))
            }.apply()
        }

    /**
     * Snapshot for `decideNotification`. Popup flags fold in the category priority switch: on
     * Android the heads-up decision is the channel's importance, and this app never mutates an
     * existing channel's importance (the platform blocks app-side raises), so priority and popup
     * both select a channel variant through the same decision.
     */
    fun policy(users: NotifySettings, chats: NotifySettings, broadcasts: NotifySettings, exceptions: Map<Long, NotifySettings>, folderMutedChatIds: Set<Long>): NotificationPolicyState =
        NotificationPolicyState(
            users = users,
            chats = chats,
            broadcasts = broadcasts,
            exceptions = exceptions,
            peerModes = peerModes(),
            folderMutedChatIds = folderMutedChatIds,
            storiesEnabled = storiesEnabled,
            reactionsEnabled = reactionsEnabled,
            pinnedEnabled = pinnedEnabled,
            contactJoinedEnabled = contactJoinedEnabled,
            giftsEnabled = giftsEnabled,
            inAppSound = inAppSound,
            inAppVibrate = inAppVibrate,
            inAppPreview = inAppPreview,
            inChatSound = inChatSound,
            inAppPriority = inAppPriority,
            popupUsers = categoryPopup("users") && categoryPriorityHigh("users"),
            popupChats = categoryPopup("chats") && categoryPriorityHigh("chats"),
            popupBroadcasts = categoryPopup("broadcasts") && categoryPriorityHigh("broadcasts"),
            popupStories = categoryPopup("stories"),
            popupReactions = categoryPopup("reactions"),
            badgeMuted = badgeMuted,
            showPreview = inAppPreview,
        )

    fun secret(): ByteArray {
        val stored = prefs.getString(SECRET, null)
        if (!stored.isNullOrBlank()) {
            return stored.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }
        val generated = ByteArray(256).also { java.security.SecureRandom().nextBytes(it) }
        prefs.edit().putString(SECRET, generated.joinToString("") { "%02x".format(it) }).apply()
        return generated
    }

    fun token(): String = prefs.getString(TOKEN, "").orEmpty()
    fun tokenType(): Int = prefs.getInt(TOKEN_TYPE, 0)
    fun setToken(type: PushTokenType, token: String) {
        prefs.edit()
            .putInt(TOKEN_TYPE, type.code)
            .putString(TOKEN, token)
            .apply()
    }

    fun setLastRegister(result: String) {
        prefs.edit().putString(LAST_REGISTER, result.take(120)).apply()
    }

    fun recordPayload(payload: PushPayload) {
        prefs.edit()
            .putString(LAST_LOC, payload.locKey)
            .putString(LAST_IDS, payload.customIds)
            .apply()
    }

    fun debugState(gms: Boolean, distributor: String, permission: Boolean): PushDebugState {
        val type = PushTokenType.fromCode(tokenType())?.name?.lowercase() ?: "none"
        return PushDebugState(
            transport = type,
            tokenRedacted = redactToken(token()),
            gmsAvailable = gms,
            distributor = distributor,
            lastRegister = prefs.getString(LAST_REGISTER, "").orEmpty(),
            lastLocKey = prefs.getString(LAST_LOC, "").orEmpty(),
            lastCustomIds = prefs.getString(LAST_IDS, "").orEmpty(),
            permissionGranted = permission,
        )
    }

    fun clearPushIdentity() {
        prefs.edit()
            .remove(TOKEN)
            .remove(TOKEN_TYPE)
            .remove(LAST_REGISTER)
            .remove(LAST_LOC)
            .remove(LAST_IDS)
            .apply()
    }

    companion object {
        private const val PREFS = "monogram_notifications"
        private const val INAPP_SOUND = "inapp_sound"
        private const val INAPP_VIBRATE = "inapp_vibrate"
        private const val INAPP_PREVIEW = "inapp_preview"
        private const val INCHAT_SOUND = "inchat_sound"
        private const val INAPP_PRIORITY = "inapp_priority"
        private const val PINNED = "pinned"
        private const val CONTACT_JOINED = "contact_joined"
        private const val STORIES = "stories"
        private const val REACTIONS = "reactions"
        private const val GIFTS = "gifts"
        private const val LAST_SHOWN_CHAT = "last_shown_chat"
        private const val BADGE = "badge"
        private const val BADGE_MUTED = "badge_muted"
        private const val BADGE_MESSAGES = "badge_messages"
        private const val REPEAT = "repeat"
        private const val CALLS_VIBRATE = "calls_vibrate"
        private const val CALLS_RINGTONE = "calls_ringtone"
        private const val NO_MUTED = "no_muted"
        private const val APP_SANDBOX = "app_sandbox"
        private const val MUTED_FOLDERS = "muted_folders"
        private const val PEER_MODES = "peer_modes"
        private const val NOTIFY_USERS = "notify_defaults_users"
        private const val NOTIFY_CHATS = "notify_defaults_chats"
        private const val NOTIFY_BROADCASTS = "notify_defaults_broadcasts"
        private const val NOTIFY_EXCEPTIONS = "notify_exceptions"
        private const val SECRET = "push_secret_hex"
        private const val TOKEN = "push_token"
        private const val TOKEN_TYPE = "push_token_type"
        private const val LAST_REGISTER = "last_register"
        private const val LAST_LOC = "last_loc"
        private const val LAST_IDS = "last_ids"
    }
}

interface PushRegistration {
    fun debugState(): PushDebugState
    suspend fun reregister()
    fun simulate(locKey: String)
    fun requestPermission()
    fun applyChannels()
    fun gmsAvailable(): Boolean
    fun distributor(): String
    fun onVisibleChat(chatId: Long?) {}
    fun onChatRead(chatId: Long) {}
    fun onLogout() {}
}
