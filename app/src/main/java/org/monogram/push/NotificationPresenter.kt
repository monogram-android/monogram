package org.monogram.push

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.LocusIdCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.drawable.toBitmap
import org.monogram.BubbleActivity
import org.monogram.MainActivity
import org.monogram.R
import org.monogram.core.common.push.BadgeSettings
import org.monogram.core.common.push.NotificationBadgePlan
import org.monogram.core.common.push.NotificationBatch
import org.monogram.core.common.push.NotificationConversation
import org.monogram.core.common.push.NotificationDecision
import org.monogram.core.common.push.NotificationLocalStore
import org.monogram.core.common.push.NotificationMessage
import org.monogram.core.common.push.PeerNotificationMode
import org.monogram.core.common.push.PushPayload
import org.monogram.core.common.push.bubbleFromShortcut
import org.monogram.core.common.push.callNotice
import org.monogram.core.common.push.conversationStyle
import org.monogram.core.common.push.evictedShareChatIds
import org.monogram.core.common.push.mayPostNotifications
import org.monogram.core.common.push.notificationHttpUrl
import org.monogram.core.common.push.rankedShareChatIds
import org.monogram.core.common.push.shadeText
import org.monogram.core.common.push.shouldAttachBubble
import org.monogram.core.common.push.shouldAutoExpandBubble
import org.monogram.core.common.push.shouldRetryPostWithoutBubble
import java.io.File

internal fun isLegacyNotification(owner: Long, child: Boolean, legacySummary: Boolean): Boolean =
    owner <= 0L && (child || legacySummary)

object NotificationPresenter {
    const val ACTION_OPEN_CHAT = "org.monogram.push.OPEN_CHAT"
    const val ACTION_REPLY = "org.monogram.push.REPLY"
    const val ACTION_MARK_READ = "org.monogram.push.MARK_READ"
    const val ACTION_MUTE = "org.monogram.push.MUTE"
    const val ACTION_OPEN_NOTIFICATIONS = "org.monogram.push.OPEN_NOTIFICATIONS"

    /** Fired when the user clears a chat notification from the shade. */
    const val ACTION_DISMISS = "org.monogram.push.DISMISS"
    const val EXTRA_CHAT_ID = "chat_id"
    const val EXTRA_MESSAGE_ID = "monogram_message_id"
    const val EXTRA_MAX_ID = "max_id"
    const val EXTRA_ACCOUNT_USER_ID = "account_user_id"
    const val KEY_TEXT_REPLY = "push_reply_text"

    const val GROUP_KEY = NotificationConversation.GROUP_KEY

    private fun groupKey(accountUserId: Long?): String =
        accountUserId?.let { "$GROUP_KEY.account.$it" } ?: GROUP_KEY

    /** Chat ids keep the sign bit clear, so the group summary can never collide with a chat id. */
    private const val SUMMARY_ID = Int.MIN_VALUE

    private const val MAX_SUMMARY_LINES = 5
    private const val EXTRA_BATCH_MESSAGE_ID = "org.monogram.push.batch_msg_id"
    private const val EXTRA_BATCH_EDITED = "org.monogram.push.batch_edited"
    private const val EXTRA_BATCH_TOPIC_ID = "org.monogram.push.batch_topic_id"

    private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    private const val BUBBLE_HEIGHT_DP = 600

    /** Accent Android tints the small icon and app name with, matching the launcher mark. */
    private const val NOTIFICATION_COLOR = 0xFF3E6F87.toInt()

    /** Last alert time per notification id, so a burst updates without beeping per message. */
    private val lastAlertAt = java.util.concurrent.ConcurrentHashMap<Int, Long>()

    /** Marks a notification that only counts hidden messages instead of listing their bodies. */
    private const val HIDDEN_MESSAGE_ID = -1

    private const val EXTRA_CHILD = "org.monogram.push.child"
    private val summariesPosted = mutableSetOf<Long?>()
    private const val EXTRA_UNREAD_COUNT = "org.monogram.push.unread_count"
    private const val EXTRA_REVISION = "org.monogram.push.revision"
    private val pendingPosts = mutableMapOf<Int, Pair<Notification, Long>>()

    @Synchronized
    internal fun clearLegacyNotifications(context: Context) {
        val prefs = context.getSharedPreferences("notification_migrations", Context.MODE_PRIVATE)
        if (prefs.getBoolean("account_scoped_shade", false)) return
        try {
            val manager = NotificationManagerCompat.from(context)
            val old = manager.activeNotifications.filter { active ->
                active.tag == null && isLegacyNotification(
                    active.notification.extras.getLong(EXTRA_ACCOUNT_USER_ID, 0L),
                    active.notification.extras.getBoolean(EXTRA_CHILD),
                    active.id == SUMMARY_ID && active.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0,
                )
            }
            old.forEach {
                manager.cancel(it.id)
                pendingPosts.remove(it.id)
                lastAlertAt.remove(it.id)
            }
            removeShareShortcuts(context, NotificationLocalStore(context).shareChatIds)
            summariesPosted.remove(null)
            prefs.edit().putBoolean("account_scoped_shade", true).commit()
        } catch (_: Exception) {
            org.monogram.core.common.AppLog.warn("notify", "legacy notification cleanup deferred")
        }
    }

    @Synchronized
    fun show(
        context: Context,
        payload: PushPayload,
        decision: NotificationDecision,
        folderId: Int? = null,
        avatarFile: File? = null,
        mode: PeerNotificationMode? = null,
        badge: BadgeSettings = BadgeSettings(),
        quiet: Boolean = false,
        pictureFile: File? = null,
        accountUserId: Long? = null,
    ) {
        if (!decision.show) return
        val local = NotificationLocalStore(context, accountUserId)
        if (!local.notificationsEnabled || !notificationsEnabled(context)) return
        if (!mayPostNotifications(
                Build.VERSION.SDK_INT,
                ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.POST_NOTIFICATIONS
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED,
            )
        ) {
            return
        }
        val chatId = payload.chatId ?: 0L
        val id = notificationIdFor(payload, accountUserId)
        val active = activeBatch(context, id)
        var ledger = local.notificationLedger(chatId)
        if (!quiet && active == null && ledger.pending.isNotEmpty()) ledger = ledger.dismiss()
        val messageId = payload.messageId ?: 0
        if (!quiet && chatId != 0L && !ledger.accepts(messageId)) return
        val previousLedger = ledger
        if (!quiet && messageId in ledger.pending && active?.messages?.none { it.messageId == messageId } == true) return
        if (!quiet && chatId != 0L) ledger = ledger.append(messageId, payload.topicId)
        val preview = decision.preview && (!quiet || active?.preview != false)
        // A quiet repaint only refreshes a notification that is still in the shade.
        if (quiet && (active == null || active.messages.none { it.messageId == payload.messageId } || (messageId > 0 && messageId !in ledger.pending))) return
        val accountLabel = accountLabel(context, accountUserId)
        val baseTitle =
            if (preview) payload.title else context.getString(R.string.push_hidden_title)
        val title =
            accountQualifiedNotificationTitle(baseTitle, accountLabel, accountCount(context))
        val body = shadeText(preview, payload.body, context.getString(R.string.push_hidden_body))
        val channel = NotificationChannels.channelFor(
            context = context,
            kind = decision.channelKind,
            folderId = folderId,
            chatId = chatId,
            chatTitle = payload.title,
            custom = mode != null && !mode.isDefault,
            sound = decision.sound,
            popup = decision.popup,
            store = local,
        )
        if (!channelEnabled(context, channel)) return
        val avatarBitmap = if (preview) avatarBitmap(avatarFile) else null
        val avatar = avatarBitmap?.let { IconCompat.createWithBitmap(it) }
        val now = System.currentTimeMillis()
        val repeated = payload.messageId?.takeIf { it > 0 }?.let { messageId ->
            messageId in previousLedger.pending || active?.messages?.any { it.messageId == messageId } == true
        } == true
        val rawStyle = conversationStyle(payload, quiet || repeated, lastAlertAt[id] ?: 0L, now)
        val style = if (preview) rawStyle else rawStyle.copy(
            personName = title, conversationTitle = null, shortcutLabel = title,
            messageSenderName = null,
        )
        val call = callNotice(payload.locKey, canAnswer = false, fullScreenAllowed = false)
        val person = senderPerson(
            style.personName.ifBlank { title },
            style.personKey ?: NotificationConversation.peerPersonKey(chatId),
            avatar,
        )
        val shortcutId = style.shortcutId?.let { shortcutId(chatId, accountUserId) }
        val demoted = shortcutId != null && isConversationDemoted(context, shortcutId)
        val bubbleIcon = if (shouldAttachBubble(
                enabled = NotificationLocalStore(context, accountUserId).bubblesEnabled,
                conversation = shortcutId != null,
                demoted = demoted,
            )
        ) {
            bubbleIcon(context, chatId, avatarBitmap)
        } else {
            null
        }
        val shortcutPublished = shortcutId != null && !demoted && publishConversationShortcut(
            context = context,
            shortcutId = shortcutId,
            chatId = chatId,
            label = accountQualifiedNotificationTitle(
                style.shortcutLabel ?: title,
                accountLabel,
                accountCount(context),
            ),
            locusId = style.locusId ?: shortcutId,
            person = person,
            icon = bubbleIcon ?: avatar ?: IconCompat.createWithResource(
                context, R.mipmap.ic_launcher
            ),
            messageId = payload.messageId ?: 0,
            bubble = bubbleIcon != null,
            accountUserId = accountUserId,
        )
        val incoming = NotificationMessage(
            payload.messageId ?: 0, body, System.currentTimeMillis(),
            outgoing = payload.scheduled, senderName = style.messageSenderName,
            senderKey = style.messagePersonKey, topicId = payload.topicId,
            pictureUri = if (preview) pictureUri(context, pictureFile)?.toString() else null,
            pictureMimeType = pictureFile?.let { file ->
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, options)
                options.outMimeType
            } ?: "image/jpeg",
        )
        val previous = active?.messages.orEmpty().filter {
            it.messageId != HIDDEN_MESSAGE_ID && (chatId == 0L || it.messageId <= 0 || it.messageId in ledger.pending)
        }
        val hidden = !preview
        val batch = if (quiet) {
            incoming.pictureUri?.let { uri ->
                NotificationBatch.attachPicture(
                    previous, incoming.messageId, uri, incoming.pictureMimeType
                )
            } ?: previous
        } else {
            NotificationBatch.append(previous, incoming)
        }
        val count = maxOf(batch.size, ledger.pending.size)
        val shown = if (hidden) batch.map {
            it.copy(text = body, senderName = null, senderKey = null, pictureUri = null)
        } else batch
        if (shown.isEmpty()) return
        val silent =
            !style.countsAsNewAlert || payload.silent || (!decision.sound && mode?.sound != false)
        if (style.countsAsNewAlert && !payload.silent && (decision.sound || mode?.sound == false)) {
            lastAlertAt[id] = now
        }
        val builder = builderFor(
            context = context,
            id = id,
            channel = channel,
            title = title,
            chatId = chatId,
            person = person,
            messages = shown,
            count = if (decision.badge) {
                NotificationBadgePlan.number(count, badge)
            } else {
                0
            },
            unreadCount = count,
            shortcutId = shortcutId,
            largeIcon = avatarBitmap?.let { Icon.createWithBitmap(it) },
            pictureUri = null,
            pictureBitmap = null,
            conversationTitle = style.conversationTitle,
            messageSenderName = style.messageSenderName,
            allowReply = style.reply,
            silent = silent,
            onlyAlertOnce = style.onlyAlertOnce || silent,
            maxId = payload.maxId ?: 0,
            category = if (call.categoryCall) {
                NotificationCompat.CATEGORY_CALL
            } else if (call.call) {
                NotificationCompat.CATEGORY_MISSED_CALL
            } else {
                NotificationCompat.CATEGORY_MESSAGE
            },
            stayUntilOpened = call.staysUntilOpened,
            accountUserId = accountUserId,
        )
        builder.extras.putBoolean("org.monogram.push.preview", preview)
        val bubbleShortcut = shortcutId?.takeIf {
            bubbleFromShortcut(Build.VERSION.SDK_INT, it) && shortcutPublished && bubbleIcon != null
        }
        if (bubbleShortcut != null) {
            builder.setBubbleMetadata(
                shortcutBubble(
                    bubbleShortcut,
                    suppressNotification = false,
                    expand = shouldAutoExpandBubble(true),
                ),
            )
        }
        var posted = runCatching {
            NotificationManagerCompat.from(context).notify(id, builder.build())
        }
        if (shouldRetryPostWithoutBubble(bubbleShortcut != null, posted.isFailure)) {
            builder.setBubbleMetadata(null)
            posted = runCatching {
                NotificationManagerCompat.from(context).notify(id, builder.build())
            }
        }
        if (posted.isFailure) {
            org.monogram.core.common.AppLog.warn("notify", "post failed")
        } else {
            pendingPosts[id] = builder.build() to android.os.SystemClock.elapsedRealtime()
            if (chatId != 0L && !quiet) local.saveNotificationLedger(chatId, ledger)
            refreshSummary(context, badge, id, builder.build(), accountUserId)
        }
    }

    /**
     * Drops the conversation shortcut of a chat once the notification is cleared so a read chat
     * does not keep occupying a conversation slot.
     */
    /**
     * URI for a notification picture plus a read grant to SystemUI, which renders the notification.
     * A grant failure returns null so the notification is posted without the image instead of
     * showing a broken attachment.
     */
    private fun pictureUri(context: Context, file: File?): Uri? {
        if (file == null || !file.isFile) return null
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        }.getOrNull() ?: return null
        val granted = runCatching {
            context.grantUriPermission(
                SYSTEM_UI_PACKAGE, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }.isSuccess
        return uri.takeIf { granted }
    }

    fun removeShortcut(context: Context, chatId: Long, accountUserId: Long? = null) {
        if (chatId == 0L) return
        removeShareShortcuts(context, listOf(chatId), accountUserId)
    }

    private fun rememberShareChat(
        context: Context,
        chatId: Long,
        accountUserId: Long? = null,
    ): Int {
        val local = NotificationLocalStore(context, accountUserId)
        val maxCount = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context)
        val previous = local.shareChatIds
        val next = rankedShareChatIds(previous, chatId, maxCount)
        removeShareShortcuts(context, evictedShareChatIds(previous, next), accountUserId)
        if (next != previous) local.shareChatIds = next
        return next.indexOf(chatId).coerceAtLeast(0)
    }

    private fun removeShareShortcuts(
        context: Context,
        chatIds: List<Long>,
        accountUserId: Long? = null,
    ) {
        val ids = chatIds.filter { it != 0L }.map { shortcutId(it, accountUserId) }
        if (ids.isEmpty()) return
        runCatching {
            ShortcutManagerCompat.removeDynamicShortcuts(context, ids)
            if (Build.VERSION.SDK_INT >= 30) ShortcutManagerCompat.removeLongLivedShortcuts(
                context, ids
            )
        }
    }

    /** A demoted conversation stays cached and is no longer dynamic. Do not push it back. */
    private fun isConversationDemoted(context: Context, shortcutId: String): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        val cached = runCatching {
            ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_CACHED)
        }.getOrDefault(emptyList())
        val match = cached.firstOrNull { it.id == shortcutId } ?: return false
        return match.isCached && !match.isDynamic
    }

    /**
     * Drops every notification and conversation shortcut. Called on logout/session revoke so a
     * signed-out account leaves no chat names, avatars or reply actions behind in the shade.
     */
    @Synchronized
    fun clear(context: Context, accountUserId: Long? = null) {
        val local = NotificationLocalStore(context, accountUserId)
        val chatIds = local.notificationChatIds().toList()
        if (accountUserId == null) {
            pendingPosts.clear()
            summariesPosted.clear()
        } else {
            summariesPosted.remove(accountUserId)
            chatIds.forEach { chatId ->
                pendingPosts.remove(notificationId(chatId, accountUserId))
            }
        }
        local.clearNotificationLedgers()
        if (accountUserId == null) {
            runCatching { NotificationManagerCompat.from(context).cancelAll() }
        } else {
            val manager = NotificationManagerCompat.from(context)
            chatIds.forEach { chatId -> manager.cancel(notificationId(chatId, accountUserId)) }
            manager.cancel(notificationId(SUMMARY_ID.toLong(), accountUserId))
            removeShareShortcuts(context, chatIds, accountUserId)
        }
        runCatching {
            if (accountUserId == null) lastAlertAt.clear()
        }
    }

    fun clearDisabled(context: Context, accountUserId: Long? = null) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = NotificationManagerCompat.from(context)
        manager.activeNotifications.forEach { active ->
            val owner =
                active.notification.extras.getLong(EXTRA_ACCOUNT_USER_ID, 0L).takeIf { it > 0L }
            if (owner != accountUserId && !(owner == null && accountUserId == null)) return@forEach
            val channel = manager.getNotificationChannel(active.notification.channelId)
            if (channel?.importance == android.app.NotificationManager.IMPORTANCE_NONE) {
                manager.cancel(active.id)
            }
        }
    }

    @Synchronized
    fun cancel(
        context: Context,
        chatId: Long,
        badge: BadgeSettings = BadgeSettings(),
        accountUserId: Long? = null,
    ) {
        pendingPosts.remove(notificationId(chatId, accountUserId))
        val local = NotificationLocalStore(context, accountUserId)
        local.saveNotificationLedger(chatId, local.notificationLedger(chatId).dismiss())
        NotificationManagerCompat.from(context).cancel(notificationId(chatId, accountUserId))
        if (accountUserId == null) {
            refreshSummary(
                context, badge, notificationId(chatId, accountUserId), null, accountUserId
            )
        }
    }

    fun suppressVisibleBubble(
        context: Context,
        chatId: Long,
        bubblesEnabled: Boolean,
        accountUserId: Long? = null,
    ): Boolean {
        if (!bubblesEnabled || Build.VERSION.SDK_INT < 30) return false
        val id = notificationId(chatId, accountUserId)
        val active =
            NotificationManagerCompat.from(context).activeNotifications.firstOrNull { it.id == id }
                ?: return false
        val post = active.notification
        if (post.flags and Notification.FLAG_BUBBLE == 0) return false
        val metadata = post.bubbleMetadata ?: return false
        val shortcutId = metadata.shortcutId ?: return false
        if (metadata.isNotificationSuppressed && !metadata.autoExpandBubble) return true
        return runCatching {
            val builder = Notification.Builder.recoverBuilder(context, post).setOnlyAlertOnce(true)
                .setNumber(0).setBubbleMetadata(
                    Notification.BubbleMetadata.Builder(shortcutId)
                        .setDesiredHeight(BUBBLE_HEIGHT_DP).setSuppressNotification(true)
                        .setAutoExpandBubble(false).build(),
                )
            NotificationManagerCompat.from(context).notify(id, builder.build())
        }.isSuccess
    }

    /**
     * Re-posts the collapsed chat batch so the repeat timer alerts again. Returns false when the
     * notification is no longer in the shade.
     */
    @Synchronized
    fun realert(context: Context, chatId: Long, accountUserId: Long? = null): Boolean {
        val local = NotificationLocalStore(context, accountUserId)
        if (!local.notificationsEnabled || !notificationsEnabled(context)) return false
        val manager = NotificationManagerCompat.from(context)
        val id = notificationId(chatId, accountUserId)
        val post = activeNotification(context, id) ?: return false
        val title =
            post.extras.getCharSequence(NotificationCompat.EXTRA_TITLE)?.toString().orEmpty()
        val messages = batchMessages(post).ifEmpty { return false }
        val icon = post.getLargeIcon()
        val iconCompat = icon?.let { candidate ->
            runCatching { candidate.loadDrawable(context)?.toBitmap() }.getOrNull()
                ?.let { IconCompat.createWithBitmap(it) }
        }
        val person = senderPerson(title, NotificationConversation.peerPersonKey(chatId), iconCompat)
        val builder = builderFor(
            context = context,
            id = id,
            channel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) post.channelId
            else NotificationChannels.OTHER,
            title = title,
            chatId = chatId,
            person = person,
            messages = messages,
            count = NotificationBadgePlan.number(
                post.extras.getInt(EXTRA_UNREAD_COUNT, messages.size),
                local.badgeSettings(),
            ),
            unreadCount = post.extras.getInt(EXTRA_UNREAD_COUNT, messages.size),
            shortcutId = if (chatId != 0L) shortcutId(chatId, accountUserId) else null,
            largeIcon = icon,
            pictureUri = null,
            pictureBitmap = null,
            conversationTitle = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(
                post
            )?.conversationTitle?.toString(),
            messageSenderName = null,
            allowReply = chatId != 0L,
            silent = false,
            onlyAlertOnce = false,
            maxId = 0,
            category = NotificationCompat.CATEGORY_MESSAGE,
            stayUntilOpened = false,
            accountUserId = accountUserId,
        )
        builder.extras.putBoolean(
            "org.monogram.push.preview", post.extras.getBoolean("org.monogram.push.preview", true)
        )
        if (!(Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED)
        ) return false
        val replacement = builder.build()
        if (!channelEnabled(context, replacement.channelId)) return false
        return runCatching { manager.notify(id, replacement) }.isSuccess.also { success ->
            if (success) pendingPosts[id] = replacement to android.os.SystemClock.elapsedRealtime()
        }
    }

    private fun summaryId(accountUserId: Long?): Int =
        accountUserId?.let { notificationId(SUMMARY_ID.toLong(), it) } ?: SUMMARY_ID

    private fun accountLabel(context: Context, accountUserId: Long?): String? {
        if (accountUserId == null) return null
        val app = context.applicationContext as? org.monogram.MonogramApp ?: return null
        return runCatching {
            val accounts = app.accountRegistry.all()
            if (accounts.size < 2 || accounts.none { it.userId == accountUserId }) null
            else "${context.getString(org.monogram.feature.settings.R.string.settings_account)} $accountUserId"
        }.getOrNull()
    }

    private fun accountCount(context: Context): Int = runCatching {
        (context.applicationContext as? org.monogram.MonogramApp)?.accountRegistry?.all()?.size ?: 0
    }.getOrDefault(0)

    internal fun accountQualifiedNotificationTitle(
        title: String,
        accountLabel: String?,
        accountCount: Int,
    ): String = if (accountCount > 1 && accountLabel != null) "$title · $accountLabel" else title

    internal fun shortcutId(chatId: Long, accountUserId: Long?): String =
        accountUserId?.let { "account:$it:chat:$chatId" } ?: "chat:$chatId"

    private fun notificationsEnabled(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun channelEnabled(context: Context, channelId: String): Boolean {
        if (!notificationsEnabled(context)) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        val manager =
            context.getSystemService(android.app.NotificationManager::class.java) ?: return false
        return manager.getNotificationChannel(channelId)?.importance != android.app.NotificationManager.IMPORTANCE_NONE
    }


    fun notificationId(chatId: Long, accountUserId: Long? = null): Int =
        if (accountUserId == null) {
            (chatId xor (chatId ushr 32)).toInt() and Int.MAX_VALUE
        } else {
            var hash = accountUserId xor (accountUserId ushr 32)
            hash = hash * 31 + chatId
            (hash xor (hash ushr 32)).toInt() and Int.MAX_VALUE
        }

    fun notificationActionsEnabled(
        context: Context,
        chatId: Long,
        accountUserId: Long? = null,
    ): Boolean {
        if (!notificationsEnabled(context)) return false
        val notification =
            activeNotification(context, notificationId(chatId, accountUserId)) ?: return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        return NotificationManagerCompat.from(context)
            .getNotificationChannel(notification.channelId)?.importance != android.app.NotificationManager.IMPORTANCE_NONE
    }

    /**
     * Payloads without a chat (contact joined, pinned, authorization) still need a stable id, so
     * different kinds do not overwrite each other in the shade.
     */
    fun notificationIdFor(payload: PushPayload, accountUserId: Long? = null): Int =
        payload.chatId?.let { notificationId(it, accountUserId) } ?: if (accountUserId == null) {
            "loc:${payload.locKey}".hashCode() and Int.MAX_VALUE
        } else {
            notificationId("loc:${payload.locKey}".hashCode().toLong(), accountUserId)
        }

    private fun builderFor(
        context: Context,
        id: Int,
        channel: String,
        title: String,
        chatId: Long,
        person: Person,
        messages: List<NotificationMessage>,
        count: Int,
        shortcutId: String?,
        largeIcon: Icon?,
        pictureUri: Uri?,
        pictureBitmap: Bitmap?,
        conversationTitle: String?,
        messageSenderName: String?,
        allowReply: Boolean,
        silent: Boolean,
        onlyAlertOnce: Boolean,
        maxId: Int,
        category: String,
        stayUntilOpened: Boolean,
        unreadCount: Int = count,
        accountUserId: Long? = null,
    ): NotificationCompat.Builder {
        val last = messages.last()
        val messageId = last.messageId.takeIf { it > 0 } ?: 0
        val self = selfPerson(context)
        val style = NotificationCompat.MessagingStyle(self)
        messages.forEach { message ->
            val senderName = message.senderName
            val author = when {
                message.outgoing -> self
                senderName != null -> senderPerson(
                    senderName,
                    message.senderKey ?: NotificationConversation.senderPersonKey(senderName),
                    null,
                )

                messageSenderName != null -> senderPerson(
                    messageSenderName,
                    person.key ?: NotificationConversation.peerPersonKey(chatId),
                    null,
                )

                else -> person
            }
            val entry =
                NotificationCompat.MessagingStyle.Message(message.text, message.timestamp, author)
            if (message.messageId != 0) entry.extras.putInt(
                EXTRA_BATCH_MESSAGE_ID, message.messageId
            )
            if (message.edited) entry.extras.putBoolean(EXTRA_BATCH_EDITED, true)
            message.topicId?.let { entry.extras.putInt(EXTRA_BATCH_TOPIC_ID, it) }
            // Keep attachments with their message across thumbnail and avatar refreshes.
            message.pictureUri?.let { entry.setData(message.pictureMimeType, Uri.parse(it)) }
            style.addMessage(entry)
        }
        conversationTitle?.let { style.setConversationTitle(it) }
        val pictureStyle = pictureBitmap?.let {
            NotificationCompat.BigPictureStyle().bigPicture(it).setSummaryText(last.text)
        }
        val builder = NotificationCompat.Builder(context, channel)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setSmallIcon(R.drawable.ic_notification).setColor(NOTIFICATION_COLOR)
            .setContentTitle(title).setWhen(last.timestamp).setContentText(last.text)
            .setStyle(pictureStyle ?: style).setNumber(count)
            // Telegram shows the unread count for a collapsed chat (NotificationsController:5657).
            .apply {
                if (unreadCount > 1) {
                    setSubText(
                        context.resources.getQuantityString(
                            R.plurals.push_new_messages, unreadCount, unreadCount
                        )
                    )
                }
            }.setCategory(category).setGroup(groupKey(accountUserId))
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setAutoCancel(!stayUntilOpened).setOnlyAlertOnce(onlyAlertOnce).apply {
                accountUserId?.let { extras.putLong(EXTRA_ACCOUNT_USER_ID, it) }
            }.setContentIntent(
                pendingActivity(
                    context, id, openChatIntent(context, chatId, messageId, accountUserId)
                )
            ).setPriority(
                if (silent) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_HIGH,
            )
        if (shortcutId != null) builder.setShortcutId(shortcutId)
        builder.addPerson(person)
        if (largeIcon != null) builder.setLargeIcon(largeIcon)
        builder.setDeleteIntent(
            dismissIntent(
                context, id, chatId, messages.maxOf { it.messageId }, accountUserId
            )
        )
        if (allowReply && chatId != 0L) {
            openLinkAction(context, id, last.text)?.let { builder.addAction(it) }
            builder.addAction(replyAction(context, id, chatId, messageId, maxId, accountUserId))
                .addAction(markReadAction(context, id, chatId, messageId, maxId, accountUserId))
                .addAction(muteAction(context, id, chatId, accountUserId))
                .addAction(notificationSettingsAction(context, id, accountUserId))
        }
        if (silent) builder.setSilent(true)
        builder.extras.putBoolean(EXTRA_CHILD, true)
        builder.extras.putInt(EXTRA_UNREAD_COUNT, unreadCount)
        builder.extras.putLong(EXTRA_REVISION, android.os.SystemClock.elapsedRealtimeNanos())
        return builder
    }

    private class ActiveBatch(
        val messages: List<NotificationMessage>, val count: Int, val preview: Boolean
    )

    private fun activeNotification(context: Context, id: Int): Notification? {
        val active =
            NotificationManagerCompat.from(context).activeNotifications.firstOrNull { it.id == id }?.notification
        val cached = pendingPosts[id] ?: return active
        if (active == null) {
            if (android.os.SystemClock.elapsedRealtime() - cached.second < 500) return cached.first
        } else if (cached.first.extras.getLong(EXTRA_REVISION) > active.extras.getLong(
                EXTRA_REVISION
            )
        ) {
            return cached.first
        }
        pendingPosts.remove(id)
        return active
    }

    private fun activeBatch(context: Context, id: Int): ActiveBatch? {
        val active = activeNotification(context, id) ?: return null
        return ActiveBatch(
            batchMessages(active),
            active.extras.getInt(EXTRA_UNREAD_COUNT, active.number.coerceAtLeast(0)),
            active.extras.getBoolean("org.monogram.push.preview", true)
        )
    }

    private fun batchMessages(notification: Notification): List<NotificationMessage> {
        val style =
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
        val userKey = style?.user?.key
        return style?.messages.orEmpty().mapNotNull { message ->
            val text = message.text?.toString() ?: return@mapNotNull null
            NotificationMessage(
                messageId = message.extras.getInt(EXTRA_BATCH_MESSAGE_ID, 0),
                text = text,
                timestamp = message.timestamp,
                outgoing = NotificationConversation.isOutgoing(message.person?.key, userKey),
                senderName = message.person?.name?.toString(),
                senderKey = message.person?.key,
                edited = message.extras.getBoolean(EXTRA_BATCH_EDITED),
                topicId = message.extras.getInt(EXTRA_BATCH_TOPIC_ID, 0).takeIf { it > 0 },
                pictureUri = message.dataUri?.toString(),
                pictureMimeType = message.dataMimeType ?: "image/jpeg",
            )
        }
    }

    private fun selfPerson(context: Context): Person =
        Person.Builder().setName(context.getString(R.string.push_you))
            .setKey(NotificationConversation.SELF_PERSON_KEY).build()

    private fun senderPerson(name: String, key: String, avatar: IconCompat?): Person =
        Person.Builder().setName(name).setKey(key).apply { if (avatar != null) setIcon(avatar) }
            .build()

    /** Re-posts the collapsed batch summary, or clears it when a chat notification left the shade. */
    @Synchronized
    fun refreshSummary(
        context: Context,
        badge: BadgeSettings = BadgeSettings(),
        accountUserId: Long? = null,
    ) = refreshSummary(context, badge, null, null, accountUserId)

    private fun refreshSummary(
        context: Context,
        badge: BadgeSettings,
        changedId: Int?,
        replacement: Notification?,
        accountUserId: Long? = null,
    ) {
        if (!NotificationLocalStore(
                context, accountUserId
            ).notificationsEnabled || !notificationsEnabled(context)
        ) return
        val manager = NotificationManagerCompat.from(context)
        val summaryId = summaryId(accountUserId)
        val groupKey = groupKey(accountUserId)
        val active = manager.activeNotifications.filter { active ->
            val owner =
                active.notification.extras.getLong(EXTRA_ACCOUNT_USER_ID, 0L).takeIf { it > 0L }
            owner == accountUserId || (owner == null && accountUserId == null)
        }
        val posts = active.associate { it.id to (it.notification to it.postTime) }.toMutableMap()
        pendingPosts.keys.toList().forEach { id ->
            val current = activeNotification(context, id)
            if (current == null) posts.remove(id)
            else posts[id] = current to current.`when`
        }
        val children = (posts.filterKeys { it != changedId }
            .map { (id, value) -> Triple(id, value.first, value.second) } + listOfNotNull(
            replacement?.let {
                Triple(
                    requireNotNull(changedId), it, System.currentTimeMillis()
                )
            })).filter { (_, notification, _) ->
            notification.extras.getLong(EXTRA_ACCOUNT_USER_ID, 0L)
                .takeIf { it > 0L } == accountUserId && (notification.group == GROUP_KEY || notification.group == groupKey || notification.extras.getBoolean(
                EXTRA_CHILD
            )) && notification.flags and Notification.FLAG_GROUP_SUMMARY == 0
        }.sortedByDescending { it.third }
        if (children.size < 2) {
            val hadSummary =
                summariesPosted.remove(accountUserId) || active.any { it.id == summaryId }
            manager.cancel(summaryId)
            if (hadSummary) children.singleOrNull()?.let { child ->
                val detached =
                    NotificationCompat.Builder(context, child.second).setGroup(null).setSilent(true)
                        .setOnlyAlertOnce(true).build()
                detached.extras.putLong(
                    EXTRA_REVISION, android.os.SystemClock.elapsedRealtimeNanos()
                )
                if (runCatching { manager.notify(child.first, detached) }.isSuccess) {
                    pendingPosts[child.first] = detached to android.os.SystemClock.elapsedRealtime()
                }
            }
            return
        }
        children.filter { it.second.group != groupKey }.forEach { child ->
            val grouped =
                NotificationCompat.Builder(context, child.second).setGroup(groupKey).setSilent(true)
                    .setOnlyAlertOnce(true).build()
            grouped.extras.putLong(EXTRA_REVISION, android.os.SystemClock.elapsedRealtimeNanos())
            if (runCatching { manager.notify(child.first, grouped) }.isSuccess) {
                pendingPosts[child.first] = grouped to android.os.SystemClock.elapsedRealtime()
            }
        }
        val channel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            active.firstOrNull { it.id == summaryId }?.notification?.channelId
                ?: children.first().second.channelId
        } else {
            NotificationChannels.OTHER
        }
        val total = children.sumOf {
            it.second.extras.getInt(
                EXTRA_UNREAD_COUNT, it.second.number.coerceAtLeast(1)
            )
        }
        val lines = NotificationCompat.InboxStyle()
        children.take(MAX_SUMMARY_LINES).forEach { child ->
            val title =
                child.second.extras.getCharSequence(NotificationCompat.EXTRA_TITLE)?.toString()
            val text =
                child.second.extras.getCharSequence(NotificationCompat.EXTRA_TEXT)?.toString()
            val line = listOfNotNull(
                title?.takeIf { it.isNotBlank() },
                text?.takeIf { it.isNotBlank() }).joinToString(": ")
            if (line.isNotBlank()) lines.addLine(line)
        }
        if (children.size > MAX_SUMMARY_LINES) {
            lines.setSummaryText(
                context.getString(
                    R.string.push_more_chats, children.size - MAX_SUMMARY_LINES
                )
            )
        }
        val summary = NotificationCompat.Builder(context, channel)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setSmallIcon(R.drawable.ic_notification).setColor(NOTIFICATION_COLOR)
            .setContentTitle(context.getString(R.string.app_name)).setContentText(
                context.resources.getQuantityString(
                    R.plurals.push_new_messages, total, total
                )
            ).setStyle(lines).setNumber(NotificationBadgePlan.number(total, badge))
            .setGroup(groupKey).setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE).setAutoCancel(true).apply {
                accountUserId?.let { extras.putLong(EXTRA_ACCOUNT_USER_ID, it) }
            }.setOnlyAlertOnce(true).setSilent(true)
            .setContentIntent(pendingActivity(context, summaryId, openAppIntent(context))).build()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            if (runCatching { manager.notify(summaryId, summary) }.isSuccess) summariesPosted.add(
                accountUserId
            )
        }
    }

    private fun openLinkAction(
        context: Context, id: Int, body: String
    ): NotificationCompat.Action? {
        val url = notificationHttpUrl(body) ?: return null
        val intent =
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(
            context,
            id xor 3,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlags(),
        )
        return NotificationCompat.Action.Builder(
            0,
            context.getString(R.string.push_open_link),
            pending,
        ).build()
    }

    private fun replyAction(
        context: Context,
        id: Int,
        chatId: Long,
        messageId: Int,
        maxId: Int,
        accountUserId: Long? = null,
    ): NotificationCompat.Action {
        val remote = RemoteInput.Builder(KEY_TEXT_REPLY)
            .setLabel(context.getString(R.string.push_reply_hint)).build()
        val intent = Intent(context, NotificationActionReceiver::class.java).setAction(ACTION_REPLY)
            .putExtra(EXTRA_CHAT_ID, chatId).putExtra(EXTRA_MESSAGE_ID, messageId)
            .putExtra(EXTRA_MAX_ID, maxId).putExtra(EXTRA_ACCOUNT_USER_ID, accountUserId ?: 0L)
        val pending = PendingIntent.getBroadcast(
            context,
            id xor 1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or mutableFlags(),
        )
        return NotificationCompat.Action.Builder(
            R.drawable.ic_push_reply,
            context.getString(R.string.push_reply),
            pending,
        ).addRemoteInput(remote).setAllowGeneratedReplies(true)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false).build()
    }

    private fun markReadAction(
        context: Context,
        id: Int,
        chatId: Long,
        messageId: Int,
        maxId: Int,
        accountUserId: Long? = null,
    ): NotificationCompat.Action {
        val intent =
            Intent(context, NotificationActionReceiver::class.java).setAction(ACTION_MARK_READ)
                .putExtra(EXTRA_CHAT_ID, chatId).putExtra(EXTRA_MESSAGE_ID, messageId)
                .putExtra(EXTRA_MAX_ID, maxId).putExtra(EXTRA_ACCOUNT_USER_ID, accountUserId ?: 0L)
        val pending = PendingIntent.getBroadcast(
            context,
            id xor 2,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlags(),
        )
        return NotificationCompat.Action.Builder(
            R.drawable.ic_push_read,
            context.getString(R.string.push_mark_read),
            pending,
        ).setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false).build()
    }

    private fun muteAction(
        context: Context,
        id: Int,
        chatId: Long,
        accountUserId: Long? = null,
    ): NotificationCompat.Action {
        val intent = Intent(context, NotificationActionReceiver::class.java).setAction(ACTION_MUTE)
            .putExtra(EXTRA_CHAT_ID, chatId).putExtra(EXTRA_ACCOUNT_USER_ID, accountUserId ?: 0L)
        val pending = PendingIntent.getBroadcast(
            context,
            id xor 8,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlags(),
        )
        return NotificationCompat.Action.Builder(
            R.drawable.ic_push_read,
            context.getString(R.string.push_mute_hour),
            pending,
        ).setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MUTE)
            .setShowsUserInterface(false).build()
    }

    private fun notificationSettingsAction(
        context: Context,
        id: Int,
        accountUserId: Long?,
    ): NotificationCompat.Action {
        val pending = pendingActivity(
            context,
            id xor 16,
            openNotificationsIntent(context, accountUserId),
        )
        return NotificationCompat.Action.Builder(
            R.drawable.ic_notification,
            context.getString(R.string.push_notification_settings),
            pending,
        ).build()
    }

    @Synchronized
    fun showNotSent(context: Context, chatId: Long, accountUserId: Long? = null) {
        val id = notificationId(chatId, accountUserId)
        val post = activeNotification(context, id) ?: return
        val messages = batchMessages(post) + NotificationMessage(
            0, context.getString(R.string.push_not_sent), System.currentTimeMillis()
        )
        repost(
            context, chatId, post, messages, silent = true, onlyAlertOnce = true,
            accountUserId = accountUserId,
        )
    }

    @Synchronized
    fun editMessage(
        context: Context,
        chatId: Long,
        messageId: Int,
        text: String,
        badge: BadgeSettings,
        hideMedia: Boolean = false,
        accountUserId: Long? = null,
    ) {
        val post = activeNotification(context, notificationId(chatId, accountUserId)) ?: return
        if (!post.extras.getBoolean("org.monogram.push.preview", true)) return
        val messages = batchMessages(post)
        if (messages.none { it.messageId == messageId }) return
        repost(
            context, chatId, post,
            messages.map {
                if (it.messageId == messageId) it.copy(
                    text = text, edited = true, pictureUri = if (hideMedia) null else it.pictureUri
                ) else it
            },
            silent = true, onlyAlertOnce = true, badge = badge,
            accountUserId = accountUserId,
        )
    }

    @Synchronized
    fun dropUnscopedMessages(
        context: Context,
        ids: Set<Int>,
        badge: BadgeSettings,
        accountUserId: Long? = null,
    ) {
        val local = NotificationLocalStore(context, accountUserId)
        val activeChats = NotificationManagerCompat.from(context).activeNotifications.mapNotNull {
            it.notification.shortcutId?.removePrefix("chat:")?.toLongOrNull()
        }
        (local.notificationChatIds() + activeChats).filter {
            it != 0L && it > -org.monogram.core.models.CHANNEL_PEER_OFFSET
        }.forEach { dropMessages(context, it, ids, null, badge, accountUserId = accountUserId) }
    }

    @Synchronized
    fun dropMessages(
        context: Context,
        chatId: Long,
        dropIds: Set<Int>,
        upTo: Int?,
        badge: BadgeSettings,
        topicId: Int? = null,
        accountUserId: Long? = null,
    ) {
        val id = notificationId(chatId, accountUserId)
        val local = NotificationLocalStore(context, accountUserId)
        val ledger = local.notificationLedger(chatId).retain(dropIds, upTo, topicId)
        local.saveNotificationLedger(chatId, ledger)
        val post = activeNotification(context, id) ?: return
        val messages = batchMessages(post)
        val kept = if (topicId == null) NotificationBatch.retain(messages, dropIds, upTo)
        else messages.filter { message ->
            message.topicId != topicId || NotificationBatch.retain(listOf(message), dropIds, upTo)
                .isNotEmpty()
        }
        val remaining = kept.ifEmpty {
            ledger.pending.entries.toList().takeLast(8).map { (messageId, topic) ->
                NotificationMessage(
                    messageId,
                    context.getString(R.string.push_hidden_body),
                    System.currentTimeMillis(),
                    topicId = topic
                )
            }
        }
        if (remaining.isEmpty()) {
            if (upTo == null || !suppressVisibleBubble(
                    context, chatId, NotificationLocalStore(context, accountUserId).bubblesEnabled,
                    accountUserId,
                )
            ) cancel(context, chatId, badge, accountUserId)
        } else {
            repost(
                context,
                chatId,
                post,
                remaining,
                silent = true,
                onlyAlertOnce = true,
                badge = badge,
                accountUserId = accountUserId,
            )
        }
    }

    private fun repost(
        context: Context,
        chatId: Long,
        post: Notification,
        messages: List<NotificationMessage>,
        silent: Boolean,
        onlyAlertOnce: Boolean,
        accountUserId: Long? = null,
        badge: BadgeSettings = NotificationLocalStore(context, accountUserId).badgeSettings(),
    ) {
        val local = NotificationLocalStore(context, accountUserId)
        if (messages.isEmpty() || !local.notificationsEnabled || !notificationsEnabled(context)) return
        val id = notificationId(chatId, accountUserId)
        val title =
            post.extras.getCharSequence(NotificationCompat.EXTRA_TITLE)?.toString().orEmpty()
        val icon = post.getLargeIcon()
        val iconCompat = icon?.let { candidate ->
            runCatching { candidate.loadDrawable(context)?.toBitmap() }.getOrNull()
                ?.let { IconCompat.createWithBitmap(it) }
        }
        val person = senderPerson(title, NotificationConversation.peerPersonKey(chatId), iconCompat)
        val pendingCount = local.notificationLedger(chatId).pending.size
        val unreadCount = maxOf(messages.size, pendingCount)
        val builder = builderFor(
            context = context,
            id = id,
            channel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) post.channelId else NotificationChannels.OTHER,
            title = title,
            chatId = chatId,
            person = person,
            messages = messages,
            count = NotificationBadgePlan.number(unreadCount, badge),
            unreadCount = unreadCount,
            shortcutId = if (chatId != 0L) shortcutId(chatId, accountUserId) else null,
            largeIcon = icon,
            pictureUri = null,
            pictureBitmap = null,
            conversationTitle = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(
                post
            )?.conversationTitle?.toString(),
            messageSenderName = null,
            allowReply = chatId != 0L,
            silent = silent,
            onlyAlertOnce = onlyAlertOnce,
            maxId = 0,
            category = NotificationCompat.CATEGORY_MESSAGE,
            stayUntilOpened = false,
            accountUserId = accountUserId,
        )

        if (!mayPostNotifications(
                Build.VERSION.SDK_INT,
                ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.POST_NOTIFICATIONS
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED,
            )
        ) {
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && NotificationManagerCompat.from(context)
                .getNotificationChannel(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) post.channelId else NotificationChannels.OTHER
                )?.importance == android.app.NotificationManager.IMPORTANCE_NONE
        ) return
        builder.extras.putBoolean(
            "org.monogram.push.preview", post.extras.getBoolean("org.monogram.push.preview", true)
        )
        if (Build.VERSION.SDK_INT >= 30) {
            post.bubbleMetadata?.shortcutId?.let {
                builder.setBubbleMetadata(
                    shortcutBubble(
                        it, suppressNotification = false, expand = false
                    )
                )
            }
        }
        val replacement = builder.build()
        if (runCatching {
                NotificationManagerCompat.from(context).notify(id, replacement)
            }.isSuccess) {
            pendingPosts[id] = replacement to android.os.SystemClock.elapsedRealtime()
            refreshSummary(context, badge, id, replacement, accountUserId)
        }
    }

    private fun shortcutBubble(
        shortcutId: String,
        suppressNotification: Boolean,
        expand: Boolean,
    ): NotificationCompat.BubbleMetadata =
        NotificationCompat.BubbleMetadata.Builder(shortcutId).setDesiredHeight(BUBBLE_HEIGHT_DP)
            .setAutoExpandBubble(expand).setSuppressNotification(suppressNotification).build()

    private fun publishConversationShortcut(
        context: Context,
        shortcutId: String,
        chatId: Long,
        label: String,
        locusId: String,
        person: Person,
        icon: IconCompat,
        messageId: Int,
        bubble: Boolean,
        accountUserId: Long? = null,
    ): Boolean {
        val rank = rememberShareChat(context, chatId, accountUserId)
        val shortcutPerson =
            Person.Builder().setName(person.name).setKey(person.key).setUri(person.uri)
                .setIcon(person.icon).setBot(person.isBot).setImportant(true).build()
        val shortcut = ShortcutInfoCompat.Builder(context, shortcutId)
            .setShortLabel(label.take(30).ifBlank { context.getString(R.string.app_name) })
            .setLongLabel(label).setLocusId(LocusIdCompat(locusId)).setLongLived(true)
            .setIsConversation().setRank(rank).setPerson(shortcutPerson).setIcon(icon).setIntent(
                if (bubble) bubbleChatIntent(context, chatId, messageId, accountUserId)
                else openChatIntent(context, chatId, messageId, accountUserId),
            ).build()
        return runCatching {
            ShortcutManagerCompat.pushDynamicShortcut(
                context, shortcut
            )
        }.isSuccess
    }

    internal fun bubbleChatIntent(
        context: Context,
        chatId: Long,
        messageId: Int,
        accountUserId: Long? = null,
    ): Intent = Intent(context, BubbleActivity::class.java).setAction(ACTION_OPEN_CHAT)
        .putExtra(EXTRA_CHAT_ID, chatId).putExtra(EXTRA_MESSAGE_ID, messageId)
        .putExtra(EXTRA_ACCOUNT_USER_ID, accountUserId ?: 0L)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** 108dp adaptive canvas; the picture stays inside the 72dp safe zone so the bubble is not a white ring. */
    private fun bubbleIcon(context: Context, chatId: Long, avatar: Bitmap?): IconCompat? {
        val density = context.resources.displayMetrics.density
        val canvasPx = (108f * density).toInt().coerceAtLeast(1)
        val safePx = (72f * density).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(canvasPx, canvasPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val inset = (canvasPx - safePx) / 2f
        val dest = RectF(inset, inset, inset + safePx, inset + safePx)
        val source = avatar ?: run {
            val drawable = context.getDrawable(R.mipmap.ic_launcher)
            val fallback = Bitmap.createBitmap(safePx, safePx, Bitmap.Config.ARGB_8888)
            drawable?.setBounds(0, 0, safePx, safePx)
            drawable?.draw(Canvas(fallback))
            fallback
        }
        canvas.save()
        canvas.clipPath(Path().apply { addOval(dest, Path.Direction.CW) })
        canvas.drawBitmap(
            source, null, dest, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        )
        canvas.restore()
        if (Build.VERSION.SDK_INT < 30) return IconCompat.createWithAdaptiveBitmap(bitmap)
        val file = File(context.cacheDir, "bubbles/$chatId.png")
        file.parentFile?.mkdirs()
        val written = runCatching {
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }.isSuccess
        if (!written) return null
        val uri = pictureUri(context, file) ?: return null
        return IconCompat.createWithAdaptiveBitmapContentUri(uri)
    }

    private fun openNotificationsIntent(context: Context, accountUserId: Long?): Intent =
        Intent(context, MainActivity::class.java).putExtra(
            EXTRA_ACCOUNT_USER_ID, accountUserId ?: 0L
        ).setAction(ACTION_OPEN_NOTIFICATIONS)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun pendingActivity(context: Context, requestCode: Int, intent: Intent): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlags(),
        )

    private fun dismissIntent(
        context: Context,
        id: Int,
        chatId: Long,
        maxId: Int,
        accountUserId: Long? = null,
    ): PendingIntent = PendingIntent.getBroadcast(
        context,
        id xor 4,
        Intent(context, NotificationActionReceiver::class.java).setAction(ACTION_DISMISS)
            .putExtra(EXTRA_CHAT_ID, chatId).putExtra(EXTRA_MESSAGE_ID, maxId)
            .putExtra(EXTRA_ACCOUNT_USER_ID, accountUserId ?: 0L),
        PendingIntent.FLAG_UPDATE_CURRENT or immutableFlags(),
    )

    private fun immutableFlags(): Int =
        if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0

    private fun mutableFlags(): Int =
        if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0

    private fun openChatIntent(
        context: Context,
        chatId: Long,
        messageId: Int,
        accountUserId: Long? = null,
    ): Intent = Intent(context, MainActivity::class.java).setAction(ACTION_OPEN_CHAT)
        .putExtra(EXTRA_CHAT_ID, chatId).putExtra(EXTRA_MESSAGE_ID, messageId)
        .putExtra(EXTRA_ACCOUNT_USER_ID, accountUserId ?: 0L)
        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun openAppIntent(context: Context): Intent = Intent(
        context, MainActivity::class.java
    ).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Bounded decode for the big-picture style; the file cap is enforced by the coordinator. */
    private fun decodePicture(file: File): Bitmap? =
        runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()

    private fun avatarBitmap(file: File?): Bitmap? {
        if (file == null || !file.isFile) return null
        val decoded = BitmapFactory.decodeFile(file.absolutePath) ?: return null
        return roundBitmap(decoded)
    }

    private fun roundBitmap(src: Bitmap): Bitmap {
        val size = minOf(src.width, src.height).coerceAtLeast(1)
        val x = (src.width - size) / 2
        val y = (src.height - size) / 2
        val squared = Bitmap.createBitmap(src, x, y, size, size)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = BitmapShader(squared, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val r = size / 2f
        canvas.drawCircle(r, r, r, paint)
        return out
    }
}
