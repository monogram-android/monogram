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
import org.monogram.core.common.push.callNotice
import org.monogram.core.common.push.conversationStyle
import org.monogram.core.common.push.evictedShareChatIds
import org.monogram.core.common.push.mayPostNotifications
import org.monogram.core.common.push.notificationHttpUrl
import org.monogram.core.common.push.rankedShareChatIds
import org.monogram.core.common.push.shadeText
import org.monogram.core.common.push.bubbleFromShortcut
import org.monogram.core.common.push.shouldAttachBubble
import org.monogram.core.common.push.shouldAutoExpandBubble
import org.monogram.core.common.push.shouldRetryPostWithoutBubble
import java.io.File

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
    const val KEY_TEXT_REPLY = "push_reply_text"

    /** Every chat notification joins this group so a burst collapses into one stack in the shade. */
    const val GROUP_KEY = NotificationConversation.GROUP_KEY

    /** Chat ids keep the sign bit clear, so the group summary can never collide with a chat id. */
    private const val SUMMARY_ID = Int.MIN_VALUE

    private const val MAX_SUMMARY_LINES = 5
    private const val EXTRA_BATCH_MESSAGE_ID = "org.monogram.push.batch_msg_id"
    private const val EXTRA_BATCH_TOPIC_ID = "org.monogram.push.batch_topic_id"

    private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    private const val BUBBLE_HEIGHT_DP = 600

    /** Accent Android tints the small icon and app name with, matching the launcher mark. */
    private const val NOTIFICATION_COLOR = 0xFF3E6F87.toInt()

    /** Last alert time per notification id, so a burst updates without beeping per message. */
    private val lastAlertAt = java.util.concurrent.ConcurrentHashMap<Int, Long>()

    /** Marks a notification that only counts hidden messages instead of listing their bodies. */
    private const val HIDDEN_MESSAGE_ID = -1

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
    ) {
        if (!decision.show) return
        if (!mayPostNotifications(
                Build.VERSION.SDK_INT,
                ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED,
            )
        ) {
            return
        }
        val chatId = payload.chatId ?: 0L
        val id = notificationIdFor(payload)
        val active = activeBatch(context, id)
        // A quiet repaint only refreshes a notification that is still in the shade.
        if (quiet && active == null) return
        val title = if (decision.preview) payload.title else context.getString(R.string.push_hidden_title)
        val body = shadeText(decision.preview, payload.body, context.getString(R.string.push_hidden_body))
        val channel = NotificationChannels.channelFor(
            context = context,
            kind = decision.channelKind,
            folderId = folderId,
            chatId = chatId,
            chatTitle = payload.title,
            custom = mode != null && !mode.isDefault,
            sound = decision.sound,
            popup = decision.popup,
        )
        val avatarBitmap = avatarBitmap(avatarFile)
        val avatar = avatarBitmap?.let { IconCompat.createWithBitmap(it) }
        val now = System.currentTimeMillis()
        val style = conversationStyle(payload, quiet, lastAlertAt[id] ?: 0L, now)
        val call = callNotice(payload.locKey, canAnswer = false, fullScreenAllowed = false)
        val person = senderPerson(
            style.personName.ifBlank { title },
            style.personKey ?: NotificationConversation.peerPersonKey(chatId),
            avatar,
        )
        val shortcutId = style.shortcutId
        val demoted = shortcutId != null && isConversationDemoted(context, shortcutId)
        val bubbleIcon = if (shouldAttachBubble(
                enabled = NotificationLocalStore(context).bubblesEnabled,
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
            label = style.shortcutLabel ?: title,
            locusId = style.locusId ?: shortcutId,
            person = person,
            icon = bubbleIcon ?: avatar ?: IconCompat.createWithResource(context, R.mipmap.ic_launcher),
            messageId = payload.messageId ?: 0,
            bubble = bubbleIcon != null,
        )
        val incoming = NotificationMessage(
            payload.messageId ?: 0, body, System.currentTimeMillis(),
            outgoing = payload.scheduled, senderName = style.messageSenderName,
            senderKey = style.messagePersonKey, topicId = payload.topicId
        )
        val previous = active?.messages.orEmpty().filter { it.messageId != HIDDEN_MESSAGE_ID }
        val batch = if (quiet) {
            previous.ifEmpty { listOf(incoming) }
        } else {
            NotificationBatch.append(previous, incoming)
        }
        // Hidden previews keep counting messages without listing the placeholder bodies.
        val hidden = !decision.preview
        val count = if (hidden) NotificationBatch.countHidden(active?.count ?: 0, !quiet) else batch.size
        val shown = if (hidden) {
            val total = count.coerceAtLeast(1)
            listOf(
                NotificationMessage(
                    messageId = HIDDEN_MESSAGE_ID,
                    text = context.resources.getQuantityString(R.plurals.push_new_messages, total, total),
                    timestamp = active?.messages?.lastOrNull()?.timestamp ?: incoming.timestamp,
                    topicId = payload.topicId,
                ),
            )
        } else {
            batch
        }
        val uri = pictureUri(context, pictureFile)
        val bigPicture = if (!style.messagingStyle && uri != null && shown.size == 1) {
            pictureFile?.let { decodePicture(it) }
        } else {
            null
        }
        val silent = !style.countsAsNewAlert || payload.silent || (!decision.sound && mode?.sound != false)
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
                NotificationBadgePlan.number(if (hidden) count.coerceAtLeast(1) else batch.size, badge)
            } else {
                0
            },
            shortcutId = shortcutId,
            largeIcon = avatarBitmap?.let { Icon.createWithBitmap(it) },
            pictureUri = if (bigPicture == null) uri else null,
            pictureBitmap = bigPicture,
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
        )
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
            if (!quiet) {
                org.monogram.core.common.AppLog.api("notify", "posted loc=${payload.locKey}")
                refreshSummary(context, badge)
            }
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
            context.grantUriPermission(SYSTEM_UI_PACKAGE, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.isSuccess
        return uri.takeIf { granted }
    }

    fun removeShortcut(context: Context, chatId: Long) {
        if (chatId == 0L) return
        removeShareShortcuts(context, listOf(chatId))
    }

    private fun rememberShareChat(context: Context, chatId: Long): Int {
        val local = NotificationLocalStore(context)
        val maxCount = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context)
        val previous = local.shareChatIds
        val next = rankedShareChatIds(previous, chatId, maxCount)
        removeShareShortcuts(context, evictedShareChatIds(previous, next))
        if (next != previous) local.shareChatIds = next
        return next.indexOf(chatId).coerceAtLeast(0)
    }

    private fun removeShareShortcuts(context: Context, chatIds: List<Long>) {
        val ids = chatIds.filter { it != 0L }.map { "chat:$it" }
        if (ids.isEmpty()) return
        runCatching {
            ShortcutManagerCompat.removeDynamicShortcuts(context, ids)
            if (Build.VERSION.SDK_INT >= 30) ShortcutManagerCompat.removeLongLivedShortcuts(context, ids)
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
    fun clear(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancelAll() }
        runCatching {
            if (Build.VERSION.SDK_INT >= 30) {
                val ids = ShortcutManagerCompat.getDynamicShortcuts(context).map { it.id }
                if (ids.isNotEmpty()) ShortcutManagerCompat.removeLongLivedShortcuts(context, ids)
            }
            ShortcutManagerCompat.removeAllDynamicShortcuts(context)
            lastAlertAt.clear()
        }
    }

    fun cancel(context: Context, chatId: Long, badge: BadgeSettings = BadgeSettings()) {
        NotificationManagerCompat.from(context).cancel(notificationId(chatId))
        refreshSummary(context, badge)
    }

    fun suppressVisibleBubble(context: Context, chatId: Long, bubblesEnabled: Boolean): Boolean {
        if (!bubblesEnabled || Build.VERSION.SDK_INT < 30) return false
        val id = notificationId(chatId)
        val active = NotificationManagerCompat.from(context).activeNotifications
            .firstOrNull { it.id == id } ?: return false
        val post = active.notification
        if (post.flags and Notification.FLAG_BUBBLE == 0) return false
        val metadata = post.bubbleMetadata ?: return false
        val shortcutId = metadata.shortcutId ?: return false
        if (metadata.isNotificationSuppressed && !metadata.autoExpandBubble) return true
        return runCatching {
            val builder = Notification.Builder.recoverBuilder(context, post)
                .setOnlyAlertOnce(true)
                .setNumber(0)
                .setBubbleMetadata(
                    Notification.BubbleMetadata.Builder(shortcutId)
                        .setDesiredHeight(BUBBLE_HEIGHT_DP)
                        .setSuppressNotification(true)
                        .setAutoExpandBubble(false)
                        .build(),
                )
            NotificationManagerCompat.from(context).notify(id, builder.build())
        }.isSuccess
    }

    /**
     * Re-posts the collapsed chat batch so the repeat timer alerts again. Returns false when the
     * notification is no longer in the shade.
     */
    fun realert(context: Context, chatId: Long): Boolean {
        val manager = NotificationManagerCompat.from(context)
        val id = notificationId(chatId)
        val active = manager.activeNotifications.firstOrNull { it.id == id } ?: return false
        val post = active.notification
        val title = post.extras.getCharSequence(NotificationCompat.EXTRA_TITLE)?.toString().orEmpty()
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
            count = post.number.takeIf { it > messages.size } ?: messages.size,
            shortcutId = if (chatId != 0L) "chat:$chatId" else null,
            largeIcon = icon,
            pictureUri = null,
            pictureBitmap = null,
            conversationTitle = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(post)
                ?.conversationTitle?.toString(),
            messageSenderName = null,
            allowReply = chatId != 0L,
            silent = false,
            onlyAlertOnce = false,
            maxId = 0,
            category = NotificationCompat.CATEGORY_MESSAGE,
            stayUntilOpened = false,
        )
        if (!(Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED)) return false
        return runCatching { manager.notify(id, builder.build()) }.isSuccess
    }

    fun notificationId(chatId: Long): Int = (chatId xor (chatId ushr 32)).toInt() and Int.MAX_VALUE

    /**
     * Payloads without a chat (contact joined, pinned, authorization) still need a stable id, so
     * different kinds do not overwrite each other in the shade.
     */
    fun notificationIdFor(payload: PushPayload): Int =
        payload.chatId?.let { notificationId(it) }
            ?: (("loc:${payload.locKey}").hashCode() and Int.MAX_VALUE)

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
    ): NotificationCompat.Builder {
        val last = messages.last()
        val messageId = last.messageId.takeIf { it > 0 } ?: 0
        val self = selfPerson(context)
        val style = NotificationCompat.MessagingStyle(self)
        messages.forEachIndexed { index, message ->
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
            val entry = NotificationCompat.MessagingStyle.Message(message.text, message.timestamp, author)
            if (message.messageId != 0) entry.extras.putInt(EXTRA_BATCH_MESSAGE_ID, message.messageId)
            message.topicId?.let { entry.extras.putInt(EXTRA_BATCH_TOPIC_ID, it) }
            // Attach the picture to the newest message so the shade renders it inside the
            // conversation instead of replacing MessagingStyle (Telegram serves it the same way,
            // through its NotificationImageProvider).
            if (pictureUri != null && index == messages.lastIndex) entry.setData("image/*", pictureUri)
            style.addMessage(entry)
        }
        conversationTitle?.let { style.setConversationTitle(it) }
        val pictureStyle = pictureBitmap?.let {
            NotificationCompat.BigPictureStyle().bigPicture(it).setSummaryText(last.text)
        }
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(NOTIFICATION_COLOR)
            .setContentTitle(title)
            .setContentText(last.text)
            .setStyle(pictureStyle ?: style)
            .setNumber(count)
            // Telegram shows the unread count for a collapsed chat (NotificationsController:5657).
            .apply {
                if (count > 1) {
                    setSubText(context.resources.getQuantityString(R.plurals.push_new_messages, count, count))
                }
            }
            .setCategory(category)
            .setGroup(GROUP_KEY)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setAutoCancel(!stayUntilOpened)
            .setOnlyAlertOnce(onlyAlertOnce)
            .setContentIntent(pendingActivity(context, id, openChatIntent(context, chatId, messageId)))
            .setPriority(
                if (silent) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_HIGH,
            )
        if (shortcutId != null) builder.setShortcutId(shortcutId)
        builder.addPerson(person)
        if (largeIcon != null) builder.setLargeIcon(largeIcon)
        builder.setDeleteIntent(dismissIntent(context, id))
        if (allowReply && chatId != 0L) {
            openLinkAction(context, id, last.text)?.let { builder.addAction(it) }
            builder.addAction(replyAction(context, id, chatId, messageId, maxId))
                .addAction(markReadAction(context, id, chatId, messageId, maxId))
                .addAction(muteAction(context, id, chatId))
                .addAction(notificationSettingsAction(context, id))
        }
        if (silent) builder.setSilent(true)
        return builder
    }

    private class ActiveBatch(val messages: List<NotificationMessage>, val count: Int)

    private fun activeBatch(context: Context, id: Int): ActiveBatch? {
        val active = NotificationManagerCompat.from(context).activeNotifications
            .firstOrNull { it.id == id }
            ?: return null
        return ActiveBatch(batchMessages(active.notification), active.notification.number.coerceAtLeast(0))
    }

    private fun batchMessages(notification: Notification): List<NotificationMessage> {
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
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
                topicId = message.extras.getInt(EXTRA_BATCH_TOPIC_ID, 0).takeIf { it > 0 },
            )
        }
    }

    private fun selfPerson(context: Context): Person =
        Person.Builder()
            .setName(context.getString(R.string.push_you))
            .setKey(NotificationConversation.SELF_PERSON_KEY)
            .build()

    private fun senderPerson(name: String, key: String, avatar: IconCompat?): Person =
        Person.Builder()
            .setName(name)
            .setKey(key)
            .apply { if (avatar != null) setIcon(avatar) }
            .build()

    /** Re-posts the collapsed batch summary, or clears it when a chat notification left the shade. */
    fun refreshSummary(context: Context, badge: BadgeSettings = BadgeSettings()) {
        val manager = NotificationManagerCompat.from(context)
        val active = manager.activeNotifications
        val children = active
            .filter {
                it.notification.group == GROUP_KEY &&
                    it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0
            }
            .sortedByDescending { it.postTime }
        if (children.size < 2) {
            manager.cancel(SUMMARY_ID)
            return
        }
        val channel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            active.firstOrNull { it.id == SUMMARY_ID }?.notification?.channelId
                ?: children.first().notification.channelId
        } else {
            NotificationChannels.OTHER
        }
        val total = children.sumOf { it.notification.number.coerceAtLeast(1) }
        val lines = NotificationCompat.InboxStyle()
        children.take(MAX_SUMMARY_LINES).forEach { child ->
            val title = child.notification.extras.getCharSequence(NotificationCompat.EXTRA_TITLE)?.toString()
            val text = child.notification.extras.getCharSequence(NotificationCompat.EXTRA_TEXT)?.toString()
            val line = listOfNotNull(title?.takeIf { it.isNotBlank() }, text?.takeIf { it.isNotBlank() })
                .joinToString(": ")
            if (line.isNotBlank()) lines.addLine(line)
        }
        if (children.size > MAX_SUMMARY_LINES) {
            lines.setSummaryText(context.getString(R.string.push_more_chats, children.size - MAX_SUMMARY_LINES))
        }
        val summary = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(NOTIFICATION_COLOR)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.resources.getQuantityString(R.plurals.push_new_messages, total, total))
            .setStyle(lines)
            .setNumber(NotificationBadgePlan.number(total, badge))
            .setGroup(GROUP_KEY)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(pendingActivity(context, SUMMARY_ID, openAppIntent(context)))
            .build()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            runCatching { manager.notify(SUMMARY_ID, summary) }
        }
    }

    private fun openLinkAction(context: Context, id: Int, body: String): NotificationCompat.Action? {
        val url = notificationHttpUrl(body) ?: return null
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
    ): NotificationCompat.Action {
        val remote = RemoteInput.Builder(KEY_TEXT_REPLY)
            .setLabel(context.getString(R.string.push_reply_hint))
            .build()
        val intent = Intent(context, NotificationActionReceiver::class.java)
            .setAction(ACTION_REPLY)
            .putExtra(EXTRA_CHAT_ID, chatId)
            .putExtra(EXTRA_MESSAGE_ID, messageId)
            .putExtra(EXTRA_MAX_ID, maxId)
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
        )
            .addRemoteInput(remote)
            .setAllowGeneratedReplies(true)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .build()
    }

    private fun markReadAction(
        context: Context,
        id: Int,
        chatId: Long,
        messageId: Int,
        maxId: Int,
    ): NotificationCompat.Action {
        val intent = Intent(context, NotificationActionReceiver::class.java)
            .setAction(ACTION_MARK_READ)
            .putExtra(EXTRA_CHAT_ID, chatId)
            .putExtra(EXTRA_MESSAGE_ID, messageId)
            .putExtra(EXTRA_MAX_ID, maxId)
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
        )
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()
    }

    private fun muteAction(context: Context, id: Int, chatId: Long): NotificationCompat.Action {
        val intent = Intent(context, NotificationActionReceiver::class.java)
            .setAction(ACTION_MUTE)
            .putExtra(EXTRA_CHAT_ID, chatId)
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
        )
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MUTE)
            .setShowsUserInterface(false)
            .build()
    }

    private fun notificationSettingsAction(context: Context, id: Int): NotificationCompat.Action {
        val pending = pendingActivity(context, id xor 16, openNotificationsIntent(context))
        return NotificationCompat.Action.Builder(
            R.drawable.ic_notification,
            context.getString(R.string.push_notification_settings),
            pending,
        ).build()
    }

    fun showNotSent(context: Context, chatId: Long) {
        val id = notificationId(chatId)
        val active = NotificationManagerCompat.from(context).activeNotifications.firstOrNull { it.id == id } ?: return
        val messages = batchMessages(active.notification) +
            NotificationMessage(0, context.getString(R.string.push_not_sent), System.currentTimeMillis())
        repost(context, chatId, active.notification, messages, silent = true, onlyAlertOnce = true)
    }

    fun dropMessages(
        context: Context,
        chatId: Long,
        dropIds: Set<Int>,
        upTo: Int?,
        badge: BadgeSettings,
        topicId: Int? = null,
    ) {
        val id = notificationId(chatId)
        val active = NotificationManagerCompat.from(context).activeNotifications.firstOrNull { it.id == id } ?: return
        val messages = batchMessages(active.notification)
        val kept = if (topicId == null) NotificationBatch.retain(messages, dropIds, upTo)
        else messages.filter { message ->
            message.topicId != topicId || NotificationBatch.retain(listOf(message), dropIds, upTo)
                .isNotEmpty()
        }
        if (kept.isEmpty()) {
            if (upTo == null || !suppressVisibleBubble(
                    context, chatId, NotificationLocalStore(context).bubblesEnabled,
                )) cancel(context, chatId, badge)
        }
        else repost(context, chatId, active.notification, kept, silent = true, onlyAlertOnce = true)
    }

    private fun repost(
        context: Context,
        chatId: Long,
        post: Notification,
        messages: List<NotificationMessage>,
        silent: Boolean,
        onlyAlertOnce: Boolean,
    ) {
        if (messages.isEmpty()) return
        val id = notificationId(chatId)
        val title = post.extras.getCharSequence(NotificationCompat.EXTRA_TITLE)?.toString().orEmpty()
        val icon = post.getLargeIcon()
        val iconCompat = icon?.let { candidate ->
            runCatching { candidate.loadDrawable(context)?.toBitmap() }.getOrNull()
                ?.let { IconCompat.createWithBitmap(it) }
        }
        val person = senderPerson(title, NotificationConversation.peerPersonKey(chatId), iconCompat)
        val builder = builderFor(
            context = context,
            id = id,
            channel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) post.channelId else NotificationChannels.OTHER,
            title = title,
            chatId = chatId,
            person = person,
            messages = messages,
            count = messages.size,
            shortcutId = if (chatId != 0L) "chat:$chatId" else null,
            largeIcon = icon,
            pictureUri = null,
            pictureBitmap = null,
            conversationTitle = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(post)
                ?.conversationTitle?.toString(),
            messageSenderName = null,
            allowReply = chatId != 0L,
            silent = silent,
            onlyAlertOnce = onlyAlertOnce,
            maxId = 0,
            category = NotificationCompat.CATEGORY_MESSAGE,
            stayUntilOpened = false,
        )
        if (!mayPostNotifications(
                Build.VERSION.SDK_INT,
                ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED,
            )
        ) {
            return
        }
        if (Build.VERSION.SDK_INT >= 30) {
            post.bubbleMetadata?.shortcutId?.let {
                builder.setBubbleMetadata(shortcutBubble(it, suppressNotification = false, expand = false))
            }
        }
        runCatching { NotificationManagerCompat.from(context).notify(id, builder.build()) }
    }

    private fun shortcutBubble(
        shortcutId: String,
        suppressNotification: Boolean,
        expand: Boolean,
    ): NotificationCompat.BubbleMetadata =
        NotificationCompat.BubbleMetadata.Builder(shortcutId)
            .setDesiredHeight(BUBBLE_HEIGHT_DP)
            .setAutoExpandBubble(expand)
            .setSuppressNotification(suppressNotification)
            .build()

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
    ): Boolean {
        val rank = rememberShareChat(context, chatId)
        val shortcutPerson = Person.Builder()
            .setName(person.name)
            .setKey(person.key)
            .setUri(person.uri)
            .setIcon(person.icon)
            .setBot(person.isBot)
            .setImportant(true)
            .build()
        val shortcut = ShortcutInfoCompat.Builder(context, shortcutId)
            .setShortLabel(label.take(30).ifBlank { context.getString(R.string.app_name) })
            .setLongLabel(label)
            .setLocusId(LocusIdCompat(locusId))
            .setLongLived(true)
            .setIsConversation()
            .setRank(rank)
            .setPerson(shortcutPerson)
            .setIcon(icon)
            .setIntent(
                if (bubble) bubbleChatIntent(context, chatId, messageId)
                else openChatIntent(context, chatId, messageId),
            )
            .build()
        return runCatching { ShortcutManagerCompat.pushDynamicShortcut(context, shortcut) }.isSuccess
    }

    private fun bubbleChatIntent(context: Context, chatId: Long, messageId: Int): Intent =
        Intent(context, BubbleActivity::class.java)
            .setAction(ACTION_OPEN_CHAT)
            .putExtra(EXTRA_CHAT_ID, chatId)
            .putExtra(EXTRA_MESSAGE_ID, messageId)
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
        canvas.drawBitmap(source, null, dest, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
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

    private fun openNotificationsIntent(context: Context): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN_NOTIFICATIONS)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun pendingActivity(context: Context, requestCode: Int, intent: Intent): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlags(),
        )

    private fun dismissIntent(context: Context, id: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            id xor 4,
            Intent(context, NotificationActionReceiver::class.java).setAction(ACTION_DISMISS),
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlags(),
        )

    private fun immutableFlags(): Int =
        if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0

    private fun mutableFlags(): Int =
        if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0

    private fun openChatIntent(context: Context, chatId: Long, messageId: Int): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN_CHAT)
            .putExtra(EXTRA_CHAT_ID, chatId)
            .putExtra(EXTRA_MESSAGE_ID, messageId)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun openAppIntent(context: Context): Intent =
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)

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
