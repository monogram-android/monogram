package org.monogram.data.di

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.util.Log
import android.util.LruCache
import androidx.core.app.*
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.drawable.toBitmap
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import org.drinkless.tdlib.TdApi
import org.monogram.data.core.coRunCatching
import org.monogram.data.db.dao.NotificationSettingDao
import org.monogram.data.db.model.NotificationSettingEntity
import org.monogram.data.gateway.TelegramGateway
import org.monogram.data.infra.FileDownloadQueue
import org.monogram.data.service.NotificationDismissReceiver
import org.monogram.data.service.NotificationReadReceiver
import org.monogram.data.service.NotificationReplyReceiver
import org.monogram.domain.repository.AppPreferencesProvider
import org.monogram.domain.repository.NotificationSettingsRepository
import org.monogram.domain.repository.NotificationSettingsRepository.TdNotificationScope
import org.monogram.domain.repository.PushProvider
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

class TdNotificationManager(
    private val context: Context,
    private val gateway: TelegramGateway,
    private val appPreferences: AppPreferencesProvider,
    private val notificationSettingsRepository: NotificationSettingsRepository,
    private val notificationSettingDao: NotificationSettingDao,
    private val fileQueue: FileDownloadQueue
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val notificationManager = NotificationManagerCompat.from(context)
    private val userCache = ConcurrentHashMap<Long, TdApi.User>()
    private val chatCache = ConcurrentHashMap<Long, TdApi.Chat>()
    private val messagesHistory = ConcurrentHashMap<Long, MutableList<Pair<Long, NotificationCompat.MessagingStyle.Message>>>()
    private val lastMessageIds = ConcurrentHashMap<Long, Long>()
    private val activeNotifications = ConcurrentHashMap<Long, MutableSet<Int>>()
    private val bitmapCache = object : LruCache<Int, Bitmap>(5 * 1024 * 1024) {
        override fun sizeOf(key: Int, value: Bitmap): Int {
            return value.byteCount
        }
    }
    private val activeDownloads = ConcurrentHashMap<Int, MutableList<(Bitmap?) -> Unit>>()
    private val notificationSettingsCache = ConcurrentHashMap<Long, NotificationSettingEntity>()
    private val scopeNotificationsEnabled = ConcurrentHashMap<NotificationScopeKey, Boolean>()
    private val loadedScopeSettings = ConcurrentHashMap.newKeySet<NotificationScopeKey>()

    private enum class NotificationScopeKey {
        PRIVATE,
        GROUPS,
        CHANNELS
    }

    companion object {
        private const val TAG = "TdNotificationManager"
        const val GROUP_CHATS = "group_chats"
        const val GROUP_OTHER = "group_other"

        const val CHANNEL_PRIVATE = "channel_private_chats"
        const val CHANNEL_GROUPS = "channel_groups"
        const val CHANNEL_CHANNELS = "channel_channels"
        const val CHANNEL_OTHER = "channel_other"

        const val SUMMARY_ID = 0
        const val KEY_TEXT_REPLY = "key_text_reply"
    }

    init {
        createNotificationChannels()
        loadSettingsFromDb()
        observeUpdates()
    }

    private fun loadSettingsFromDb() {
        scope.launch(Dispatchers.IO) {
            val settings = notificationSettingDao.getAll()
            settings.forEach {
                notificationSettingsCache[it.chatId] = it
            }
        }
    }

    private fun observeUpdates() {
        scope.launch {
            gateway.isAuthenticated.collect { authenticated ->
                if (authenticated) {
                    loadedScopeSettings.clear()
                    scopeNotificationsEnabled.clear()
                    fetchScopeNotificationSettings()
                    fetchInitialExceptions()
                    updatePushRegistration()
                }
            }
        }

        scope.launch {
            gateway.updates.collect { update ->
                when (update) {
                    is TdApi.UpdateNewMessage -> handleNewMessage(update.message)
                    is TdApi.UpdateUser -> userCache[update.user.id] = update.user
                    is TdApi.UpdateFile -> {
                        val file = update.file
                        if (file.local.isDownloadingCompleted && file.local.path.isNotEmpty()) {
                            val callbacks = synchronized(activeDownloads) {
                                activeDownloads.remove(file.id)
                            }
                            if (callbacks != null) {
                                scope.launch(Dispatchers.IO) {
                                    val bitmap = try {
                                        BitmapFactory.decodeFile(file.local.path)
                                    } catch (e: Exception) {
                                        null
                                    }
                                    if (bitmap != null) {
                                        bitmapCache.put(file.id, bitmap)
                                    }
                                    callbacks.forEach { it(bitmap) }
                                }
                            }
                        }
                    }
                    is TdApi.UpdateChatNotificationSettings -> {
                        updateChatNotificationSettings(update.chatId, update.notificationSettings)
                        chatCache[update.chatId]?.let { chat ->
                            chatCache[update.chatId] = chat.apply {
                                notificationSettings = update.notificationSettings
                            }
                        }
                    }
                    is TdApi.UpdateChatReadInbox -> {
                        clearHistory(update.chatId)
                        updateSummary()
                    }
                    is TdApi.UpdateOption -> {
                        if (update.name == "is_authenticated" && (update.value as? TdApi.OptionValueBoolean)?.value == true) {
                            updatePushRegistration()
                        }
                    }
                    else -> {}
                }
            }
        }

        scope.launch {
            appPreferences.pushProvider.collect {
                updatePushRegistration()
            }
        }
    }

    private fun updateChatNotificationSettings(chatId: Long, settings: TdApi.ChatNotificationSettings) {
        val entity = NotificationSettingEntity(
            chatId = chatId,
            muteFor = settings.muteFor,
            useDefault = settings.useDefaultMuteFor
        )
        notificationSettingsCache[chatId] = entity
        scope.launch(Dispatchers.IO) {
            notificationSettingDao.insert(entity)
        }
    }

    private suspend fun updatePushRegistration() {
        if (!gateway.isAuthenticated.value) return

        when (appPreferences.pushProvider.value) {
            PushProvider.FCM -> {
                coRunCatching {
                    val token = FirebaseMessaging.getInstance().token.await()
                    gateway.execute(
                        TdApi.RegisterDevice(
                            TdApi.DeviceTokenFirebaseCloudMessaging(token, true),
                            longArrayOf()
                        )
                    )
                }.onFailure { Log.e(TAG, "FCM token registration failed", it) }
            }

            PushProvider.GMS_LESS -> {
                coRunCatching {
                    gateway.execute(
                        TdApi.RegisterDevice(
                            TdApi.DeviceTokenFirebaseCloudMessaging("", false),
                            longArrayOf()
                        )
                    )
                }.onFailure { Log.e(TAG, "GMS-less token registration failed", it) }
            }
        }
    }

    private suspend fun fetchInitialExceptions() {
        if (!gateway.isAuthenticated.value) return

        val scopes = listOf(
            TdApi.NotificationSettingsScopePrivateChats(),
            TdApi.NotificationSettingsScopeGroupChats(),
            TdApi.NotificationSettingsScopeChannelChats()
        )

        coroutineScope {
            scopes.forEach { scope ->
                launch {
                    coRunCatching {
                        val result = gateway.execute(TdApi.GetChatNotificationSettingsExceptions(scope, true))
                        if (result is TdApi.Chats) {
                            result.chatIds.forEach { chatId ->
                                getChat(chatId) { chat ->
                                    updateChatNotificationSettings(chat.id, chat.notificationSettings)
                                }
                            }
                        }
                    }.onFailure {
                        Log.w(TAG, "Failed to fetch notification exceptions", it)
                    }
                }
            }
        }
    }

    private suspend fun fetchScopeNotificationSettings() {
        if (!gateway.isAuthenticated.value) return

        val scopes = listOf(
            NotificationScopeKey.PRIVATE to TdNotificationScope.PRIVATE_CHATS,
            NotificationScopeKey.GROUPS to TdNotificationScope.GROUPS,
            NotificationScopeKey.CHANNELS to TdNotificationScope.CHANNELS
        )

        scopes.forEach { (key, scope) ->
            val enabled = coRunCatching { notificationSettingsRepository.getNotificationSettings(scope) }
                .getOrDefault(false)

            scopeNotificationsEnabled[key] = enabled
            loadedScopeSettings.add(key)

            when (key) {
                NotificationScopeKey.PRIVATE -> appPreferences.setPrivateChatsNotifications(enabled)
                NotificationScopeKey.GROUPS -> appPreferences.setGroupsNotifications(enabled)
                NotificationScopeKey.CHANNELS -> appPreferences.setChannelsNotifications(enabled)
            }
        }
    }

    fun isChatMuted(chat: TdApi.Chat): Boolean {
        val cached = notificationSettingsCache[chat.id]
        val muteFor = cached?.muteFor ?: chat.notificationSettings.muteFor
        val useDefault = cached?.useDefault ?: chat.notificationSettings.useDefaultMuteFor

        return if (useDefault) {
            val scopeKey = when (chat.type) {
                is TdApi.ChatTypePrivate -> NotificationScopeKey.PRIVATE
                is TdApi.ChatTypeBasicGroup -> NotificationScopeKey.GROUPS
                is TdApi.ChatTypeSupergroup -> {
                    if ((chat.type as TdApi.ChatTypeSupergroup).isChannel) NotificationScopeKey.CHANNELS else NotificationScopeKey.GROUPS
                }

                else -> null
            }

            if (scopeKey == null || !loadedScopeSettings.contains(scopeKey)) {
                return true
            }

            val globalEnabled = scopeNotificationsEnabled[scopeKey] ?: false
            !globalEnabled
        } else {
            muteFor > 0
        }
    }

    fun clearHistory(chatId: Long) {
        messagesHistory.remove(chatId)
        lastMessageIds.remove(chatId)
        activeNotifications.remove(chatId)?.forEach { notificationId ->
            notificationManager.cancel(notificationId)
        }
        notificationManager.cancel(chatId.toInt())
        updateSummary()
    }

    fun removeNotification(chatId: Long, notificationId: Int) {
        activeNotifications[chatId]?.remove(notificationId)
        notificationManager.cancel(notificationId)

        if (notificationId == chatId.toInt()) {
            messagesHistory.remove(chatId)
            activeNotifications.remove(chatId)
        } else {
            val history = messagesHistory[chatId]
            if (history != null) {
                history.removeAll { it.first == notificationId.toLong() }
                if (history.isEmpty()) {
                    messagesHistory.remove(chatId)
                    activeNotifications.remove(chatId)
                }
            }
        }
        updateSummary()
    }

    private fun handleNewMessage(message: TdApi.Message) {
        if (message.isOutgoing) return

        val lastId = lastMessageIds[message.chatId]
        if (lastId != null && message.id <= lastId) {
            return
        }
        lastMessageIds[message.chatId] = message.id

        getChat(message.chatId) { chat ->
            scope.launch {
                val isMember = checkMembership(chat)
                if (!isMember) {
                    Log.d(TAG, "Skipping notification for chat ${chat.id}: user is not a member")
                    return@launch
                }

                if (isChatMuted(chat)) return@launch

                val contentText =
                    if (appPreferences.showSenderOnly.value) "Новое сообщение" else getMessageText(message.content)

                if (contentText.isBlank()) return@launch

                val timestamp = message.date.toLong() * 1000

                val shouldDownloadAvatar =
                    !appPreferences.isPowerSavingMode.value && !appPreferences.batteryOptimizationEnabled.value

                resolveSender(message.senderId, chat, !shouldDownloadAvatar) { senderName, senderBitmap ->
                    if (shouldDownloadAvatar) {
                        downloadAvatar(chat.photo, false) { chatIcon ->
                            appendMessageToNotification(
                                chatId = chat.id,
                                messageId = message.id,
                                chatType = chat.type,
                                senderName = senderName,
                                senderBitmap = senderBitmap,
                                chatIcon = chatIcon ?: senderBitmap,
                                text = contentText,
                                timestamp = timestamp
                            )
                        }
                    } else {
                        appendMessageToNotification(
                            chatId = chat.id,
                            messageId = message.id,
                            chatType = chat.type,
                            senderName = senderName,
                            senderBitmap = senderBitmap,
                            chatIcon = senderBitmap,
                            text = contentText,
                            timestamp = timestamp
                        )
                    }
                }
            }
        }
    }

    private suspend fun checkMembership(chat: TdApi.Chat): Boolean {
        return when (val type = chat.type) {
            is TdApi.ChatTypePrivate -> true
            is TdApi.ChatTypeBasicGroup -> {
                if (type.basicGroupId == 0L) {
                    return true
                }
                coRunCatching {
                        val result = gateway.execute(TdApi.GetBasicGroup(type.basicGroupId))
                    result.status is TdApi.ChatMemberStatusMember ||
                            result.status is TdApi.ChatMemberStatusCreator ||
                            result.status is TdApi.ChatMemberStatusAdministrator
                }.getOrDefault(true)
            }
            is TdApi.ChatTypeSupergroup -> {
                if (type.supergroupId == 0L) {
                    return true
                }
                coRunCatching {
                        val result = gateway.execute(TdApi.GetSupergroup(type.supergroupId))
                    result.status is TdApi.ChatMemberStatusMember ||
                            result.status is TdApi.ChatMemberStatusCreator ||
                            result.status is TdApi.ChatMemberStatusAdministrator
                }.getOrDefault(true)
            }

            else -> true
        }
    }

    fun appendMessageToNotification(
        chatId: Long,
        messageId: Long,
        chatType: TdApi.ChatType,
        senderName: String,
        senderBitmap: Bitmap?,
        chatIcon: Bitmap?,
        text: String,
        timestamp: Long
    ) {
        if (text.isBlank()) return

        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        val channelId = when (chatType) {
            is TdApi.ChatTypePrivate -> CHANNEL_PRIVATE
            is TdApi.ChatTypeBasicGroup -> CHANNEL_GROUPS
            is TdApi.ChatTypeSupergroup -> if (chatType.isChannel) CHANNEL_CHANNELS else CHANNEL_GROUPS
            else -> CHANNEL_OTHER
        }

        val personBuilder = Person.Builder()
            .setName(senderName)
            .setKey(senderName)

        if (senderBitmap != null) {
            personBuilder.setIcon(IconCompat.createWithBitmap(getCircularBitmap(senderBitmap)))
        }

        val sender = personBuilder.build()

        val styleMessage = NotificationCompat.MessagingStyle.Message(
            text,
            timestamp,
            sender
        )

        val history = messagesHistory.getOrPut(chatId) { mutableListOf() }
        history.add(messageId to styleMessage)
        if (history.size > 10) {
            history.removeAt(0)
        }

        val notificationId = chatId.toInt()
        activeNotifications.getOrPut(chatId) { ConcurrentHashMap.newKeySet() }.add(notificationId)

        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("chat_id", chatId)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val dismissIntent = Intent(context, NotificationDismissReceiver::class.java).apply {
            putExtra("chat_id", chatId)
            putExtra("notification_id", notificationId)
        }
        val dismissPendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId,
            dismissIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val remoteInput = RemoteInput.Builder(KEY_TEXT_REPLY)
            .setLabel("Ответить")
            .build()

        val replyIntent = Intent(context, NotificationReplyReceiver::class.java).apply {
            putExtra("chat_id", chatId)
            putExtra("notification_id", notificationId)
        }
        val replyPendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId,
            replyIntent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val readIntent = Intent(context, NotificationReadReceiver::class.java).apply {
            putExtra("chat_id", chatId)
            putExtra("notification_id", notificationId)
        }
        val readPendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId,
            readIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )


        val replyAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            "Ответить",
            replyPendingIntent
        ).addRemoteInput(remoteInput).build()

        val readAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_view,
            "Прочитано",
            readPendingIntent
        ).build()


        val myself = Person.Builder().setName("Я").build()
        val messagingStyle = NotificationCompat.MessagingStyle(myself)
        history.forEach { (_, msg) ->
            messagingStyle.addMessage(msg)
        }

        val isGroup = chatType !is TdApi.ChatTypePrivate
        messagingStyle.isGroupConversation = isGroup

        val chatTitle = chatCache[chatId]?.title ?: senderName
        if (isGroup) {
            messagingStyle.conversationTitle = chatTitle
        }

        val priority = when (appPreferences.notificationPriority.value) {
            0 -> NotificationCompat.PRIORITY_LOW
            2 -> NotificationCompat.PRIORITY_HIGH
            else -> NotificationCompat.PRIORITY_DEFAULT
        }

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(org.monogram.data.R.drawable.message_outline)
            .setStyle(messagingStyle)
            .setPriority(priority)
            .setContentIntent(pendingIntent)
            .setDeleteIntent(dismissPendingIntent)
            .addAction(replyAction)
            .addAction(readAction)
            .setGroup(GROUP_CHATS)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setShortcutId(chatId.toString())
            .setLocusId(androidx.core.content.LocusIdCompat(chatId.toString()))
            .setOnlyAlertOnce(true)

        builder.setContentTitle(chatTitle)
        builder.setContentText(text)

        if (appPreferences.inAppSounds.value) {
            builder.setDefaults(NotificationCompat.DEFAULT_SOUND)
        } else {
            builder.setSilent(true)
        }

        if (appPreferences.inAppVibrate.value) {
            when (appPreferences.notificationVibrationPattern.value) {
                "short" -> builder.setVibrate(longArrayOf(0, 100, 50, 100))
                "long" -> builder.setVibrate(longArrayOf(0, 500, 200, 500))
                "disabled" -> builder.setVibrate(longArrayOf(0))
                else -> builder.setDefaults(NotificationCompat.DEFAULT_VIBRATE)
            }
        }

        if (!appPreferences.inAppPreview.value) {
            builder.setContentText("Новое сообщение")
        }

        if (chatIcon != null) {
            Log.d("TdNotificationManager", "Setting chat icon to $chatTitle")
            builder.setLargeIcon(getCircularBitmap(chatIcon))
        }

        notificationManager.notify(notificationId, builder.build())
        updateSummary()
    }

    private fun getCircularBitmap(bitmap: Bitmap): Bitmap {
        val size = min(bitmap.width, bitmap.height)

        val output = createBitmap(size, size)
        val canvas = Canvas(output)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                if (bitmap.width != bitmap.height) {
                    val dx = (size - bitmap.width) / 2f
                    val dy = (size - bitmap.height) / 2f
                    setLocalMatrix(Matrix().apply { setTranslate(dx, dy) })
                }
            }
        }

        val radius = size / 2f
        canvas.drawCircle(radius, radius, radius, paint)

        return output
    }

    private fun updateSummary() {
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val activeChatsCount = messagesHistory.size
        if (activeChatsCount == 0) {
            notificationManager.cancel(SUMMARY_ID)
            return
        }

        val allMessages = messagesHistory.flatMap { (chatId, messages) ->
            messages.map { (_, message) ->
                Triple(chatId, message, message.timestamp)
            }
        }.sortedByDescending { it.third }

        val totalMessagesCount = allMessages.size
        val inboxStyle = NotificationCompat.InboxStyle()

        allMessages.take(5).forEach { (chatId, message, _) ->
            val chat = chatCache[chatId]
            val senderName = message.person?.name ?: "Unknown"
            val chatTitle = chat?.title ?: senderName

            val sb = SpannableStringBuilder()
            val title = if (chatTitle != senderName) "$chatTitle ($senderName)" else senderName

            sb.append(title, StyleSpan(Typeface.BOLD), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append("  ")
            sb.append(message.text)

            inboxStyle.addLine(sb)
        }

        val summaryTitle = "$totalMessagesCount сообщений из $activeChatsCount чатов"
        inboxStyle.setSummaryText("$activeChatsCount чатов")
        inboxStyle.setBigContentTitle(summaryTitle)

        val builder = NotificationCompat.Builder(context, CHANNEL_PRIVATE)
            .setSmallIcon(org.monogram.data.R.drawable.message_outline)
            .setStyle(inboxStyle)
            .setGroup(GROUP_CHATS)
            .setGroupSummary(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setContentTitle(summaryTitle)

        val latestMessageTriple = allMessages.firstOrNull()
        if (latestMessageTriple != null) {
            val (_, message, _) = latestMessageTriple
            val iconCompat = message.person?.icon
            if (iconCompat != null) {
                val drawable = iconCompat.loadDrawable(context)
                if (drawable != null) {
                    builder.setLargeIcon(drawable.toBitmap())
                }
            }
        }

        notificationManager.notify(SUMMARY_ID, builder.build())
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            manager.createNotificationChannelGroups(
                listOf(
                    NotificationChannelGroup(GROUP_CHATS, "Чаты"),
                    NotificationChannelGroup(GROUP_OTHER, "Прочее")
                )
            )

            val channels = listOf(
                NotificationChannel(CHANNEL_PRIVATE, "Личные чаты", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Уведомления из личных переписок"
                    group = GROUP_CHATS
                    enableVibration(true)
                    setShowBadge(true)
                    lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
                },
                NotificationChannel(CHANNEL_GROUPS, "Группы", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Уведомления из групп"
                    group = GROUP_CHATS
                    enableVibration(true)
                    setShowBadge(true)
                    lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
                },
                NotificationChannel(CHANNEL_CHANNELS, "Каналы", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Уведомления из каналов"
                    group = GROUP_CHATS
                    enableVibration(false)
                    setShowBadge(true)
                },
                NotificationChannel(CHANNEL_OTHER, "Другое", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Прочие уведомления"
                    group = GROUP_OTHER
                }
            )

            manager.createNotificationChannels(channels)
        }
    }

    private fun getMessageText(content: TdApi.MessageContent): String {
        return when (content) {
            is TdApi.MessageText -> sanitizeSpoilers(content.text)
            is TdApi.MessagePhoto -> "📷 Фотография ${sanitizeSpoilers(content.caption)}"
            is TdApi.MessageVideo -> "📹 Видео ${sanitizeSpoilers(content.caption)}"
            is TdApi.MessageVoiceNote -> "🎤 Голосовое сообщение"
            is TdApi.MessageSticker -> "Стикер"
            is TdApi.MessageAnimation -> "GIF"
            is TdApi.MessageAudio -> "🎵 Аудио ${content.audio.title}"
            is TdApi.MessageDocument -> "📄 Файл ${content.document.fileName}"
            is TdApi.MessageLocation -> "📍 Локация ${content.location.latitude}, ${content.location.longitude}"
            is TdApi.MessageContact -> "👤 Контакт ${content.contact.firstName} ${content.contact.lastName}"
            is TdApi.MessagePoll -> "📊 Опрос ${content.poll.question.text}"
            else -> "Сообщение"
        }
    }

    private fun sanitizeSpoilers(formattedText: TdApi.FormattedText?): String {
        if (formattedText == null) return ""
        val text = formattedText.text.orEmpty()
        val spoilerEntities = formattedText.entities
            ?.filter { it.type is TdApi.TextEntityTypeSpoiler }
            .orEmpty()

        if (spoilerEntities.isEmpty()) return text

        val builder = StringBuilder(text)
        spoilerEntities
            .sortedByDescending { it.offset }
            .forEach { entity ->
                val start = entity.offset.coerceIn(0, builder.length)
                val end = (entity.offset + entity.length).coerceIn(start, builder.length)
                if (start < end) {
                    builder.replace(start, end, "[spoiler]")
                }
            }

        return builder.toString()
    }

    fun getChat(chatId: Long, callback: (TdApi.Chat) -> Unit) {
        chatCache[chatId]?.let {
            callback(it)
            return
        }
        scope.launch {
            try {
                val result = gateway.execute(TdApi.GetChat(chatId))
                chatCache[chatId] = result
                callback(result)
            } catch (_: Exception) {
            }
        }
    }

    private fun getUser(userId: Long, callback: (TdApi.User) -> Unit) {
        if (userId == 0L) return
        userCache[userId]?.let {
            callback(it)
            return
        }
        scope.launch {
            try {
                val result = gateway.execute(TdApi.GetUser(userId))
                userCache[result.id] = result
                callback(result)
            } catch (_: Exception) {
            }
        }
    }


    private fun resolveSender(
        senderId: TdApi.MessageSender,
        chat: TdApi.Chat,
        onlyIfLocal: Boolean = false,
        callback: (String, Bitmap?) -> Unit
    ) {
        when (senderId) {
            is TdApi.MessageSenderUser -> {
                getUser(senderId.userId) { user ->
                    val name =
                        if (chat.type is TdApi.ChatTypePrivate) chat.title else "${user.firstName} ${user.lastName}".trim()
                    val file =
                        user.profilePhoto?.small ?: if (chat.type is TdApi.ChatTypePrivate) chat.photo?.small else null
                    downloadFile(file, onlyIfLocal) { bitmap ->
                        callback(name, bitmap)
                    }
                }
            }

            is TdApi.MessageSenderChat -> {
                getChat(senderId.chatId) { senderChat ->
                    val name = senderChat.title
                    downloadFile(senderChat.photo?.small, onlyIfLocal) { bitmap ->
                        callback(name, bitmap)
                    }
                }
            }

            else -> {
                val name = chat.title
                downloadFile(chat.photo?.small, onlyIfLocal) { bitmap ->
                    callback(name, bitmap)
                }
            }
        }
    }

    private fun downloadAvatar(
        fileInfo: TdApi.ChatPhotoInfo?,
        onlyIfLocal: Boolean = false,
        callback: (Bitmap?) -> Unit
    ) {
        downloadFile(fileInfo?.small, onlyIfLocal, callback)
    }

    private fun downloadFile(file: TdApi.File?, onlyIfLocal: Boolean = false, callback: (Bitmap?) -> Unit) {
        if (file == null) {
            callback(null)
            return
        }

        val cachedBitmap = bitmapCache.get(file.id)
        if (cachedBitmap != null) {
            callback(cachedBitmap)
            return
        }

        if (file.local.isDownloadingCompleted && file.local.path.isNotEmpty()) {
            val bitmap = try {
                BitmapFactory.decodeFile(file.local.path)
            } catch (e: Exception) {
                Log.e(TAG, "Error decoding file: ${file.local.path}", e)
                null
            }

            if (bitmap != null) {
                bitmapCache.put(file.id, bitmap)
                callback(bitmap)
                return
            }
        }

        if (onlyIfLocal) {
            callback(null)
            return
        }

        synchronized(activeDownloads) {
            val callbacks = activeDownloads[file.id]
            if (callbacks != null) {
                callbacks.add(callback)
                return
            }
            activeDownloads[file.id] = mutableListOf(callback)
        }

        fileQueue.enqueue(file.id, 32, FileDownloadQueue.DownloadType.DEFAULT, synchronous = true)
    }
}
