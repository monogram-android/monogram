package org.monogram.push

import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.content.Context
import android.graphics.Color
import android.os.Build
import org.monogram.core.common.push.NotificationChannelPlan
import org.monogram.core.common.push.NotificationLocalStore
import org.monogram.core.common.push.PushChannelKind
import org.monogram.core.models.Folder

object NotificationChannels {
    private const val FOLDER_PREFIX = "folder"

    const val PRIVATE = "private_messages"
    const val GROUPS = "group_messages"
    const val CHANNELS = "channel_messages"
    const val STORIES = "story_messages"
    const val REACTIONS = "reaction_messages"
    const val OTHER = "other_messages"
    const val GROUP_CHATS = "chats"
    const val GROUP_PRIVATE = "private"
    const val GROUP_GROUPS = "groups"
    const val GROUP_CHANNELS = "channels"
    const val GROUP_OTHER = "other"
    const val GROUP_FOLDERS = "folders"
    const val GROUP_STORIES = "stories"
    const val GROUP_REACTIONS = "reactions"

    /**
     * @param pruneFolders when true, `folders` is treated as the authoritative server list and
     *   channels of deleted folders are removed. Only call it with a freshly fetched folder list:
     *   the no-arg call must never prune.
     */
    fun ensure(context: Context, folders: List<Folder> = emptyList(), pruneFolders: Boolean = false) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        // One channel group per category, mirroring Telegram's `ensureGroupsCreated` layout.
        manager.createNotificationChannelGroup(NotificationChannelGroup(GROUP_PRIVATE, context.getString(org.monogram.R.string.push_channel_private)))
        manager.createNotificationChannelGroup(NotificationChannelGroup(GROUP_GROUPS, context.getString(org.monogram.R.string.push_channel_groups)))
        manager.createNotificationChannelGroup(NotificationChannelGroup(GROUP_CHANNELS, context.getString(org.monogram.R.string.push_channel_channels)))
        manager.createNotificationChannelGroup(NotificationChannelGroup(GROUP_OTHER, context.getString(org.monogram.R.string.push_channel_other)))
        manager.createNotificationChannelGroup(NotificationChannelGroup(GROUP_FOLDERS, context.getString(org.monogram.R.string.push_group_folders)))
        manager.createNotificationChannelGroup(NotificationChannelGroup(GROUP_STORIES, context.getString(org.monogram.R.string.push_group_stories)))
        manager.createNotificationChannelGroup(NotificationChannelGroup(GROUP_REACTIONS, context.getString(org.monogram.R.string.push_group_reactions)))
        ensureChannel(manager, context, PRIVATE, org.monogram.R.string.push_channel_private, GROUP_PRIVATE)
        ensureChannel(manager, context, GROUPS, org.monogram.R.string.push_channel_groups, GROUP_GROUPS)
        ensureChannel(manager, context, CHANNELS, org.monogram.R.string.push_channel_channels, GROUP_CHANNELS)
        ensureChannel(manager, context, STORIES, org.monogram.R.string.push_channel_stories, GROUP_STORIES)
        ensureChannel(manager, context, REACTIONS, org.monogram.R.string.push_channel_reactions, GROUP_REACTIONS)
        ensureChannel(manager, context, OTHER, org.monogram.R.string.push_channel_other, GROUP_OTHER)
        folders.forEach { folder ->
            ensureChannel(
                manager,
                context,
                folderChannel(folder.id),
                folder.title.ifBlank { context.getString(org.monogram.R.string.push_folder, folder.id) },
                GROUP_FOLDERS,
            )
        }
        if (pruneFolders) {
            val live = folders.mapTo(HashSet()) { it.id }
            manager.notificationChannels
                .filter { it.id.startsWith(FOLDER_PREFIX + "_") && folderIdOf(it.id) !in live }
                .forEach { manager.deleteNotificationChannel(it.id) }
        }
    }

    /**
     * Creates the channel only when it is missing. Re-creating an existing channel would reset the
     * preferences applied by [applyPrefs] on every push (and fight any edit the user made in
     * Android's own channel settings), because channel attributes are immutable after creation.
     */
    private fun ensureChannel(
        manager: NotificationManager,
        context: Context,
        id: String,
        title: Any,
        group: String,
    ) {
        if (Build.VERSION.SDK_INT < 26) return
        if (manager.getNotificationChannel(id) != null) return
        manager.createNotificationChannel(channel(context, id, title, group))
    }

    fun idFor(kind: PushChannelKind, folderId: Int? = null): String {
        if (folderId != null) return folderChannel(folderId)
        return when (kind) {
            PushChannelKind.Private -> PRIVATE
            PushChannelKind.Group -> GROUPS
            PushChannelKind.Channel -> CHANNELS
            PushChannelKind.Stories -> STORIES
            PushChannelKind.Reactions -> REACTIONS
            PushChannelKind.Other -> OTHER
        }
    }

    /**
     * Channel for a chat notification. The base channel is managed by [applyPrefs]; a suffix adds
     * the variants Android cannot express per notification (no popup, no sound), and a chat with a
     * custom mode gets a dedicated channel. Variants are created once: an existing channel keeps
     * user edits made in Android's own settings, so the suffix encodes the mode instead of mutating
     * the channel (the same reason Telegram hashes the sound into its channel ids).
     */
    fun channelFor(
        context: Context,
        kind: PushChannelKind,
        folderId: Int?,
        chatId: Long,
        chatTitle: String,
        custom: Boolean,
        sound: Boolean,
        popup: Boolean,
    ): String {
        val base = idFor(kind, folderId)
        // Vibration/LED of a folder cannot be applied to an existing channel either, so they pick
        // the channel id: a preference change simply routes to a fresh channel.
        val suffix = folderPreferenceSuffix(context, folderId) + suffix(sound, popup)
        val customBase = if (custom && chatId != 0L) "${base}_c$chatId" else base
        val id = customBase + suffix
        if (id == base || Build.VERSION.SDK_INT < 26) return id
        val manager = context.getSystemService(NotificationManager::class.java) ?: return base
        if (manager.getNotificationChannel(id) != null) return id
        val channel = channel(
            context,
            id,
            if (custom && chatId != 0L) {
                chatTitle.ifBlank { context.getString(org.monogram.R.string.push_hidden_title) }
            } else {
                categoryTitle(context, kind, folderId)
            },
            groupFor(kind, folderId),
        )
        val folderVibrate = folderId == null || preference(context, folderId, "vibrate")
        val folderLed = folderId == null || preference(context, folderId, "led")
        channel.importance = importanceFor(sound, popup)
        if (sound) {
            channel.enableVibration(folderVibrate)
            channel.enableLights(folderLed)
            if (folderLed) channel.lightColor = Color.CYAN
        } else {
            channel.setSound(null, null)
            channel.enableVibration(false)
        }
        channel.setShowBadge(true)
        manager.createNotificationChannel(channel)
        return id
    }

    private fun suffix(sound: Boolean, popup: Boolean): String = NotificationChannelPlan.suffix(sound, popup)

    /** Suffix that encodes the folder's vibration/LED choice; empty for the defaults. */
    private fun folderPreferenceSuffix(context: Context, folderId: Int?): String {
        if (folderId == null) return ""
        val vibrate = preference(context, folderId, "vibrate")
        val led = preference(context, folderId, "led")
        return if (vibrate && led) "" else "_v${if (vibrate) 1 else 0}l${if (led) 1 else 0}"
    }

    private fun preference(context: Context, folderId: Int, key: String): Boolean {
        val store = NotificationLocalStore(context)
        val name = "folder_$folderId"
        return when (key) {
            "vibrate" -> store.categoryVibrate(name)
            else -> store.categoryLed(name)
        }
    }

    /** Folder id of any channel id of that folder, including preference variants (`folder_2_v0l1`). */
    private fun folderIdOf(id: String): Int? {
        val prefix = FOLDER_PREFIX + "_"
        if (!id.startsWith(prefix)) return null
        return id.substringAfter(prefix).takeWhile { it.isDigit() }.toIntOrNull()
    }

    /** Category channel groups for the three fixed kinds, or null for folder/preference channels. */
    private fun groupForKind(id: String): String? = when (id) {
        PRIVATE -> GROUP_PRIVATE
        GROUPS -> GROUP_GROUPS
        CHANNELS -> GROUP_CHANNELS
        STORIES -> GROUP_STORIES
        REACTIONS -> GROUP_REACTIONS
        OTHER -> GROUP_OTHER
        else -> null
    }

    /** Channel group of a variant channel: the category's own group, or the folder's. */
    private fun groupFor(kind: PushChannelKind, folderId: Int?): String = when {
        folderId != null -> GROUP_FOLDERS
        kind == PushChannelKind.Private -> GROUP_PRIVATE
        kind == PushChannelKind.Group -> GROUP_GROUPS
        kind == PushChannelKind.Channel -> GROUP_CHANNELS
        kind == PushChannelKind.Stories -> GROUP_STORIES
        kind == PushChannelKind.Reactions -> GROUP_REACTIONS
        else -> GROUP_OTHER
    }

    private fun categoryTitle(context: Context, kind: PushChannelKind, folderId: Int?): String =
        if (folderId != null) {
            context.getString(org.monogram.R.string.push_folder, folderId)
        } else {
            context.getString(
                when (kind) {
                    PushChannelKind.Private -> org.monogram.R.string.push_channel_private
                    PushChannelKind.Group -> org.monogram.R.string.push_channel_groups
                    PushChannelKind.Channel -> org.monogram.R.string.push_channel_channels
                    PushChannelKind.Stories -> org.monogram.R.string.push_channel_stories
                    PushChannelKind.Reactions -> org.monogram.R.string.push_channel_reactions
                    PushChannelKind.Other -> org.monogram.R.string.push_channel_other
                },
            )
        }

    /** Heads-up requires [NotificationManager.IMPORTANCE_HIGH]; default shows sound without a popup. */
    fun importanceFor(sound: Boolean, popup: Boolean): Int =
        when (NotificationChannelPlan.importance(sound, popup)) {
            NotificationChannelPlan.Importance.High -> NotificationManager.IMPORTANCE_HIGH
            NotificationChannelPlan.Importance.Default -> NotificationManager.IMPORTANCE_DEFAULT
            NotificationChannelPlan.Importance.Low -> NotificationManager.IMPORTANCE_LOW
        }

    fun folderChannel(id: Int): String = "${FOLDER_PREFIX}_$id"

    fun applyPrefs(context: Context, store: NotificationLocalStore) {
        if (Build.VERSION.SDK_INT < 26) return
        ensure(context)
        applyKind(context, PRIVATE, store, "users")
        applyKind(context, GROUPS, store, "chats")
        applyKind(context, CHANNELS, store, "broadcasts")
        applyKind(context, STORIES, store, "stories")
        applyKind(context, REACTIONS, store, "reactions")
        // Folder channels keep their own preference key so a folder can differ from its categories.
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.notificationChannels
            .filter { it.id.startsWith(FOLDER_PREFIX + "_") }
            // Preference key is the folder, never the channel id: a variant like `folder_2_v0l1`
            // would otherwise read defaults and re-enable the vibration the user switched off.
            .forEach { channel -> folderIdOf(channel.id)?.let { applyKind(context, channel.id, store, "$FOLDER_PREFIX" + "_" + it) } }
    }

    /**
     * Applies the in-place preferences of a category channel. Importance is deliberately not
     * touched: the platform refuses app-side importance raises on an existing channel (and restores
     * it when the app deletes and recreates the same id), so heads-up behaviour is expressed by
     * channel variants in [channelFor] instead.
     */
    private fun applyKind(context: Context, id: String, store: NotificationLocalStore, kind: String) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val existing = manager.getNotificationChannel(id) ?: return
        val vibrate = store.categoryVibrate(kind)
        val led = store.categoryLed(kind)
        existing.enableVibration(vibrate)
        existing.enableLights(led)
        if (led) existing.lightColor = Color.CYAN
        // Group assignment is mutable, so installs created before the per-category layout migrate
        // here instead of waiting for a reinstall.
        val target = groupForKind(id)
        if (target != null && existing.group != target) existing.group = target
        manager.createNotificationChannel(existing)
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
    private fun channel(context: Context, id: String, title: Any, group: String): NotificationChannel {
        val name = when (title) {
            is Int -> context.getString(title)
            else -> title.toString()
        }
        return NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH).apply {
            this.group = group
            enableVibration(true)
            enableLights(true)
            lightColor = Color.CYAN
            setShowBadge(true)
        }
    }
}
