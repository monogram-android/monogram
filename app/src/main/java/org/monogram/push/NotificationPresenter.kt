package org.monogram.push

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.Icon
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.FileProvider
import androidx.core.content.LocusIdCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import org.monogram.MainActivity
import org.monogram.R
import org.monogram.core.common.push.BadgeSettings
import org.monogram.core.common.push.NotificationAlertThrottle
import org.monogram.core.common.push.NotificationBadgePlan
import org.monogram.core.common.push.NotificationBatch
import org.monogram.core.common.push.NotificationConversation
import org.monogram.core.common.push.NotificationDecision
import org.monogram.core.common.push.NotificationMessage
import org.monogram.core.common.push.PeerNotificationMode
import org.monogram.core.common.push.PushChannelKind
import org.monogram.core.common.push.PushPayload
import org.monogram.core.common.push.notificationHttpUrl
import java.io.File
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap

object NotificationPresenter {
    const val ACTION_OPEN_CHAT = "org.monogram.push.OPEN_CHAT"
    const val ACTION_REPLY = "org.monogram.push.REPLY"
    const val ACTION_MARK_READ = "org.monogram.push.MARK_READ"

    /** Fired when the user clears a chat notification from the shade. */
    const val ACTION_DISMISS = "org.monogram.push.DISMISS"
    const val EXTRA_CHAT_ID = "chat_id"
    const val EXTRA_MESSAGE_ID = "message_id"
    const val KEY_TEXT_REPLY = "push_reply_text"

    /** Every chat notification joins this group so a burst collapses into one stack in the shade. */
    const val GROUP_KEY = "org.monogram.push.messages"

    /** Chat ids keep the sign bit clear, so the group summary can never collide with a chat id. */
    private const val SUMMARY_ID = Int.MIN_VALUE

    private const val MAX_SUMMARY_LINES = 5
    private const val EXTRA_BATCH_MESSAGE_ID = "org.monogram.push.batch_msg_id"

    private const val SYSTEM_UI_PACKAGE = "com.android.systemui"

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
        val chatId = payload.chatId ?: 0L
        val id = notificationIdFor(payload)
        val active = activeBatch(context, id)
        // A quiet repaint only refreshes a notification that is still in the shade.
        if (quiet && active == null) return
        val title = if (decision.preview) payload.title else context.getString(R.string.push_hidden_title)
        val body = if (decision.preview) payload.body else context.getString(R.string.push_hidden_body)
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
        val person = senderPerson(title, chatId, avatar)
        val shortcutId = if (chatId != 0L) "chat:$chatId" else "loc:${payload.locKey}"
        runCatching {
            val shortcut = ShortcutInfoCompat.Builder(context, shortcutId)
                .setShortLabel(title.take(30).ifBlank { context.getString(R.string.app_name) })
                // Telegram labels the conversation with the full chat name and a locus id
                // (NotificationsController:3610-3628) so the system can rank the conversation.
                .setLongLabel(title)
                .setLocusId(LocusIdCompat(shortcutId))
                .setLongLived(true)
                .setPerson(person)
                .setIcon(avatar ?: IconCompat.createWithResource(context, R.mipmap.ic_launcher))
                .setIntent(openChatIntent(context, chatId, payload.messageId ?: 0))
                .build()
            ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
        }
        val incoming = NotificationMessage(payload.messageId ?: 0, body, System.currentTimeMillis())
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
                ),
            )
        } else {
            batch
        }
        val now = System.currentTimeMillis()
        val throttled = !NotificationAlertThrottle.shouldAlert(lastAlertAt[id] ?: 0L, now)
        val uri = pictureUri(context, pictureFile)
        // Full-width picture for a single media message (what Telegram shows in the shade) while a
        // batch keeps the conversation style and carries the image inside its newest message.
        val bigPicture = if (uri != null && shown.size == 1) pictureFile?.let { decodePicture(it) } else null
        val silent = quiet || payload.silent || (!decision.sound && mode?.sound != false) || throttled
        if (!silent) lastAlertAt[id] = now
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
            isConversation = decision.channelKind != PushChannelKind.Private,
            silent = silent,
        )
        val posted = runCatching {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
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
        val id = "chat:$chatId"
        runCatching {
            ShortcutManagerCompat.removeDynamicShortcuts(context, listOf(id))
            if (Build.VERSION.SDK_INT >= 30) ShortcutManagerCompat.removeLongLivedShortcuts(context, listOf(id))
        }
    }

    /**
     * Drops every notification and conversation shortcut. Called on logout/session revoke so a
     * signed-out account leaves no chat names, avatars or reply actions behind in the shade.
     */
    fun clear(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancelAll() }
        runCatching {
            ShortcutManagerCompat.removeAllDynamicShortcuts(context)
            lastAlertAt.clear()
        }
    }

    fun cancel(context: Context, chatId: Long, badge: BadgeSettings = BadgeSettings()) {
        NotificationManagerCompat.from(context).cancel(notificationId(chatId))
        refreshSummary(context, badge)
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
        val person = senderPerson(title, chatId, iconCompat)
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
            isConversation = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(post)
                ?.conversationTitle != null,
            silent = false,
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
        isConversation: Boolean,
        silent: Boolean,
    ): NotificationCompat.Builder {
        val last = messages.last()
        val messageId = last.messageId.takeIf { it > 0 } ?: 0
        val self = selfPerson(context)
        val style = NotificationCompat.MessagingStyle(self)
        messages.forEachIndexed { index, message ->
            val author = if (message.outgoing) self else person
            val entry = NotificationCompat.MessagingStyle.Message(message.text, message.timestamp, author)
            if (message.messageId != 0) entry.extras.putInt(EXTRA_BATCH_MESSAGE_ID, message.messageId)
            // Attach the picture to the newest message so the shade renders it inside the
            // conversation instead of replacing MessagingStyle (Telegram serves it the same way,
            // through its NotificationImageProvider).
            if (pictureUri != null && index == messages.lastIndex) entry.setData("image/*", pictureUri)
            style.addMessage(entry)
        }
        if (isConversation) style.setConversationTitle(title)
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
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setGroup(GROUP_KEY)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setAutoCancel(true)
            .setOnlyAlertOnce(silent)
            .setContentIntent(pendingActivity(context, id, openChatIntent(context, chatId, messageId)))
            .setPriority(
                if (silent) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_HIGH,
            )
        if (shortcutId != null) builder.setShortcutId(shortcutId)
        if (largeIcon != null) builder.setLargeIcon(largeIcon)
        builder.setDeleteIntent(dismissIntent(context, id))
        if (chatId != 0L) {
            openLinkAction(context, id, last.text)?.let { builder.addAction(it) }
            builder.addAction(replyAction(context, id, chatId, messageId))
                .addAction(markReadAction(context, id, chatId, messageId))
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
            )
        }
    }

    private fun selfPerson(context: Context): Person =
        Person.Builder()
            .setName(context.getString(R.string.push_you))
            .setKey(NotificationConversation.SELF_PERSON_KEY)
            .build()

    private fun senderPerson(title: String, chatId: Long, avatar: IconCompat?): Person =
        Person.Builder()
            .setName(title)
            .setKey(NotificationConversation.peerPersonKey(chatId))
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
    ): NotificationCompat.Action {
        val remote = RemoteInput.Builder(KEY_TEXT_REPLY)
            .setLabel(context.getString(R.string.push_reply_hint))
            .build()
        val intent = Intent(context, NotificationActionReceiver::class.java)
            .setAction(ACTION_REPLY)
            .putExtra(EXTRA_CHAT_ID, chatId)
            .putExtra(EXTRA_MESSAGE_ID, messageId)
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
    ): NotificationCompat.Action {
        val intent = Intent(context, NotificationActionReceiver::class.java)
            .setAction(ACTION_MARK_READ)
            .putExtra(EXTRA_CHAT_ID, chatId)
            .putExtra(EXTRA_MESSAGE_ID, messageId)
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
