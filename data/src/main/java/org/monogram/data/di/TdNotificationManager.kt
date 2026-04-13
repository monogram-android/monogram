package org.monogram.data.di

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.util.Log
import android.util.LruCache
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.graphics.createBitmap
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.TdApi
import org.monogram.data.core.coRunCatching
import org.monogram.data.db.dao.NotificationSettingDao
import org.monogram.data.db.model.NotificationSettingEntity
import org.monogram.data.gateway.TelegramGateway
import org.monogram.data.infra.FileDownloadQueue
import org.monogram.data.notifications.NotificationMuteDecision
import org.monogram.data.notifications.NotificationMuteResolver
import org.monogram.data.notifications.NotificationScopeState
import org.monogram.data.push.UnifiedPushManager
import org.monogram.data.service.NotificationDismissReceiver
import org.monogram.data.service.NotificationReadReceiver
import org.monogram.data.service.NotificationReplyReceiver
import org.monogram.domain.repository.AppPreferencesProvider
import org.monogram.domain.repository.NotificationSettingsRepository
import org.monogram.domain.repository.NotificationSettingsRepository.TdNotificationScope
import org.monogram.domain.repository.PushProvider
import org.monogram.domain.repository.StringProvider
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

class TdNotificationManager(
    private val context: Context,
    private val gateway: TelegramGateway,
    private val appPreferences: AppPreferencesProvider,
    private val notificationSettingsRepository: NotificationSettingsRepository,
    private val notificationSettingDao: NotificationSettingDao,
    private val fileQueue: FileDownloadQueue,
    private val stringProvider: StringProvider,
    private val unifiedPushManager: UnifiedPushManager,
    private val muteResolver: NotificationMuteResolver
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val notificationManager = NotificationManagerCompat.from(context)
    private val userCache = ConcurrentHashMap<Long, TdApi.User>()
    private val chatCache = ConcurrentHashMap<Long, TdApi.Chat>()
    private val messagesHistory = ConcurrentHashMap<Long, CopyOnWriteArrayList<NotificationHistoryEntry>>()
    private val lastMessageIds = ConcurrentHashMap<Long, Long>()
    private val activeNotifications = ConcurrentHashMap<Long, MutableSet<Int>>()
    private val bitmapCache = object : LruCache<Int, Bitmap>(5 * 1024 * 1024) {
        override fun sizeOf(key: Int, value: Bitmap): Int {
            return value.byteCount
        }
    }
    private val activeDownloads = ConcurrentHashMap<Int, MutableList<(Bitmap?) -> Unit>>()
    private val notificationSettingsCache = ConcurrentHashMap<Long, NotificationSettingEntity>()
    private val scopeNotificationsEnabled = ConcurrentHashMap<TdNotificationScope, Boolean>()
    private val loadedScopeSettings = ConcurrentHashMap.newKeySet<TdNotificationScope>()

    @Volatile
    private var myUserId: Long = 0L

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

    private data class NotificationHistoryEntry(
        val messageId: Long,
        val senderName: String,
        val text: String,
        val timestamp: Long
    )

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
                    refreshMyUserId()
                    fetchScopeNotificationSettings()
                    fetchInitialExceptions()
                    updatePushRegistration()
                }
            }
        }

        scope.launch {
            gateway.updates.collect { update ->
                when (update) {
                    is TdApi.UpdateNewMessage -> {
                        val senderDebug = senderIdToDebug(update.message.senderId)
                        Log.d(
                            TAG,
                            "UpdateNewMessage chatId=${update.message.chatId} messageId=${update.message.id} " +
                                    "outgoing=${update.message.isOutgoing} sender=$senderDebug"
                        )
                        handleNewMessage(update.message)
                    }
                    is TdApi.UpdateUser -> userCache[update.user.id] = update.user
                    is TdApi.UpdateFile -> {
                        val file = update.file
                        val local = file.local
                        val localPath = local?.path
                        if (local?.isDownloadingCompleted == true && !localPath.isNullOrEmpty()) {
                            val callbacks = synchronized(activeDownloads) {
                                activeDownloads.remove(file.id)
                            }
                            if (callbacks != null) {
                                scope.launch(Dispatchers.IO) {
                                    val bitmap = try {
                                        BitmapFactory.decodeFile(localPath)
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
                            refreshMyUserId()
                            updatePushRegistration()
                        } else if (update.name == "my_id") {
                            val id = (update.value as? TdApi.OptionValueInteger)?.value ?: 0L
                            if (id > 0L) {
                                myUserId = id
                            }
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

        scope.launch {
            appPreferences.privateChatsNotifications.collect { enabled ->
                updateScopePreferenceState(TdNotificationScope.PRIVATE_CHATS, enabled)
            }
        }

        scope.launch {
            appPreferences.groupsNotifications.collect { enabled ->
                updateScopePreferenceState(TdNotificationScope.GROUPS, enabled)
            }
        }

        scope.launch {
            appPreferences.channelsNotifications.collect { enabled ->
                updateScopePreferenceState(TdNotificationScope.CHANNELS, enabled)
            }
        }

        scope.launch {
            unifiedPushManager.endpoint.collect {
                if (appPreferences.pushProvider.value == PushProvider.UNIFIED_PUSH && !it.isNullOrBlank()) {
                    Log.d(
                        TAG,
                        "UnifiedPush endpoint update observed, refreshing TDLib registration"
                    )
                    updatePushRegistration()
                }
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

    private fun updateScopePreferenceState(scope: TdNotificationScope, enabled: Boolean) {
        if (!gateway.isAuthenticated.value) return
        scopeNotificationsEnabled[scope] = enabled
        loadedScopeSettings.add(scope)
    }

    private suspend fun updatePushRegistration() {
        if (!gateway.isAuthenticated.value) return

        when (appPreferences.pushProvider.value) {
            PushProvider.FCM -> {
                coRunCatching {
                    unifiedPushManager.unregister()
                    val token = FirebaseMessaging.getInstance().token.await()
                    gateway.execute(
                        TdApi.RegisterDevice(
                            TdApi.DeviceTokenFirebaseCloudMessaging(token, true),
                            longArrayOf()
                        )
                    )
                    Log.d(TAG, "RegisterDevice success for FCM")
                }.onFailure { Log.e(TAG, "FCM token registration failed", it) }
            }

            PushProvider.UNIFIED_PUSH -> {
                coRunCatching {
                    unifiedPushManager.ensureRegistered()
                    val endpoint = unifiedPushManager.endpoint.value
                    if (endpoint.isNullOrBlank()) {
                        Log.w(TAG, "UnifiedPush endpoint is not available yet")
                        return@coRunCatching
                    }

                    gateway.execute(
                        TdApi.RegisterDevice(
                            TdApi.DeviceTokenSimplePush(endpoint),
                            longArrayOf()
                        )
                    )
                    Log.d(
                        TAG,
                        "RegisterDevice success for UnifiedPush endpoint=${endpoint.take(120)}"
                    )
                }.onFailure { Log.e(TAG, "UnifiedPush registration failed", it) }
            }

            PushProvider.GMS_LESS -> {
                coRunCatching {
                    unifiedPushManager.unregister()
                    gateway.execute(
                        TdApi.RegisterDevice(
                            TdApi.DeviceTokenFirebaseCloudMessaging("", false),
                            longArrayOf()
                        )
                    )
                    Log.d(TAG, "RegisterDevice success for GMS-less fallback")
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
                            for (chatId in result.chatIds.distinct()) {
                                val chat = getChatSuspend(chatId) ?: continue
                                updateChatNotificationSettings(chat.id, chat.notificationSettings)
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
            TdNotificationScope.PRIVATE_CHATS,
            TdNotificationScope.GROUPS,
            TdNotificationScope.CHANNELS
        )

        scopes.forEach { scope ->
            val enabled = coRunCatching { notificationSettingsRepository.getNotificationSettings(scope) }
                .getOrDefault(false)

            scopeNotificationsEnabled[scope] = enabled
            loadedScopeSettings.add(scope)

            when (scope) {
                TdNotificationScope.PRIVATE_CHATS -> appPreferences.setPrivateChatsNotifications(
                    enabled
                )

                TdNotificationScope.GROUPS -> appPreferences.setGroupsNotifications(enabled)
                TdNotificationScope.CHANNELS -> appPreferences.setChannelsNotifications(enabled)
            }
        }
    }

    fun isChatMuted(chat: TdApi.Chat): Boolean {
        return resolveMuteDecision(chat).isMuted
    }

    private fun resolveMuteDecision(chat: TdApi.Chat): NotificationMuteDecision {
        return muteResolver.resolve(
            chat = chat,
            cachedSettings = notificationSettingsCache[chat.id],
            scopeState = NotificationScopeState(
                loadedScopes = loadedScopeSettings.toSet(),
                enabledByScope = scopeNotificationsEnabled.toMap()
            )
        )
    }

    fun clearHistory(chatId: Long) {
        messagesHistory.remove(chatId)
        lastMessageIds.remove(chatId)
        activeNotifications.remove(chatId)?.forEach { notificationId ->
            notificationManager.cancel(notificationId)
        }
        notificationManager.cancel(notificationIdForChat(chatId))
        updateSummary()
    }

    fun removeNotification(chatId: Long, notificationId: Int) {
        activeNotifications[chatId]?.remove(notificationId)
        notificationManager.cancel(notificationId)

        if (notificationId == notificationIdForChat(chatId)) {
            messagesHistory.remove(chatId)
            activeNotifications.remove(chatId)
        } else {
            val history = messagesHistory[chatId]
            if (history != null) {
                history.removeAll { it.messageId == notificationId.toLong() }
                if (history.isEmpty()) {
                    messagesHistory.remove(chatId)
                    activeNotifications.remove(chatId)
                }
            }
        }
        updateSummary()
    }

    private fun handleNewMessage(message: TdApi.Message) {
        Log.d(
            TAG,
            "handleNewMessage enter chatId=${message.chatId} messageId=${message.id} outgoing=${message.isOutgoing} " +
                    "content=${message.content?.javaClass?.simpleName ?: "null"}"
        )

        if (message.isOutgoing) {
            Log.d(
                TAG,
                "Skip notification: outgoing message, chatId=${message.chatId}, messageId=${message.id}"
            )
            return
        }

        val messageContent = message.content
        if (messageContent == null) {
            Log.w(TAG, "Skipping notification for message ${message.id}: content is null")
            return
        }

        val senderId = message.senderId
        if (senderId == null) {
            Log.w(TAG, "Skipping notification for message ${message.id}: senderId is null")
            return
        }

        if (senderId is TdApi.MessageSenderUser && senderId.userId != 0L && senderId.userId == myUserId) {
            Log.d(
                TAG,
                "Skip notification: sender is self, chatId=${message.chatId}, messageId=${message.id}"
            )
            return
        }

        val lastId = lastMessageIds[message.chatId]
        if (lastId != null && message.id <= lastId) {
            Log.d(
                TAG,
                "Skip notification: stale/duplicate message, chatId=${message.chatId}, messageId=${message.id}, lastId=$lastId"
            )
            return
        }
        lastMessageIds[message.chatId] = message.id

        scope.launch {
            val chat = getChatSuspend(message.chatId)
            if (chat == null) {
                Log.d(
                    TAG,
                    "Skip notification: chat unavailable, chatId=${message.chatId}, messageId=${message.id}"
                )
                return@launch
            }

            val chatType = chat.type
            if (chatType == null) {
                Log.w(TAG, "Skipping notification for chat ${chat.id}: chat type is null")
                return@launch
            }

            val isMember = withTimeoutOrNull(1_500L) { checkMembership(chat) } ?: true
            if (!isMember) {
                Log.d(TAG, "Skipping notification for chat ${chat.id}: user is not a member")
                return@launch
            }

            val muteDecision = resolveMuteDecision(chat)
            if (muteDecision.isMuted) {
                Log.d(
                    TAG,
                    "Skip notification: muted reason=${muteDecision.reason} scope=${muteDecision.scope} " +
                            "muteFor=${muteDecision.muteFor} useDefault=${muteDecision.useDefault} " +
                            "chatId=${chat.id} messageId=${message.id}"
                )
                return@launch
            }

            val contentText =
                if (appPreferences.showSenderOnly.value) stringProvider.getString("notification_new_message") else getMessageText(
                    messageContent
                )

            if (contentText.isBlank()) {
                Log.d(
                    TAG,
                    "Skip notification: empty content text, chatId=${chat.id}, messageId=${message.id}"
                )
                return@launch
            }

            val timestamp = message.date.toLong() * 1000
            val shouldPreloadAvatar =
                !appPreferences.isPowerSavingMode.value && !appPreferences.batteryOptimizationEnabled.value

            resolveSender(senderId, chat, true) { senderName, senderBitmap ->
                Log.d(
                    TAG,
                    "Resolved sender for notification chatId=${chat.id} messageId=${message.id} " +
                            "senderName=$senderName hasBitmap=${senderBitmap != null}"
                )

                Log.d(
                    TAG,
                    "Append notification chatId=${chat.id} messageId=${message.id} " +
                            "chatType=${chatType.javaClass.simpleName} text=${
                                previewText(
                                    contentText
                                )
                            }"
                )
                appendMessageToNotification(
                    chatId = chat.id,
                    messageId = message.id,
                    chatType = chatType,
                    senderName = senderName,
                    senderBitmap = senderBitmap,
                    chatIcon = senderBitmap,
                    text = contentText,
                    timestamp = timestamp
                )

                if (shouldPreloadAvatar && senderBitmap == null) {
                    preloadNotificationAssets(senderId, chat)
                }
            }
        }
    }

    private suspend fun checkMembership(chat: TdApi.Chat): Boolean {
        val chatType = chat.type ?: return true
        return when (chatType) {
            is TdApi.ChatTypePrivate -> true
            is TdApi.ChatTypeBasicGroup -> {
                if (chatType.basicGroupId == 0L) {
                    return true
                }
                coRunCatching {
                    val result = gateway.execute(TdApi.GetBasicGroup(chatType.basicGroupId))
                    result.status is TdApi.ChatMemberStatusMember ||
                            result.status is TdApi.ChatMemberStatusCreator ||
                            result.status is TdApi.ChatMemberStatusAdministrator
                }.getOrDefault(true)
            }
            is TdApi.ChatTypeSupergroup -> {
                if (chatType.supergroupId == 0L) {
                    return true
                }
                coRunCatching {
                    val result = gateway.execute(TdApi.GetSupergroup(chatType.supergroupId))
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
        if (text.isBlank()) {
            Log.d(
                TAG,
                "Skip appendMessageToNotification: blank text, chatId=$chatId, messageId=$messageId"
            )
            return
        }

        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Skip appendMessageToNotification: missing POST_NOTIFICATIONS permission")
            return
        }

        val channelId = when (chatType) {
            is TdApi.ChatTypePrivate -> CHANNEL_PRIVATE
            is TdApi.ChatTypeBasicGroup -> CHANNEL_GROUPS
            is TdApi.ChatTypeSupergroup -> if (chatType.isChannel) CHANNEL_CHANNELS else CHANNEL_GROUPS
            else -> CHANNEL_OTHER
        }

        val history = messagesHistory.getOrPut(chatId) { CopyOnWriteArrayList() }
        history.add(
            NotificationHistoryEntry(
                messageId = messageId,
                senderName = senderName,
                text = text,
                timestamp = timestamp
            )
        )
        if (history.size > 10) {
            history.removeAt(0)
        }

        val notificationId = notificationIdForChat(chatId)
        Log.d(
            TAG,
            "Notification history updated chatId=$chatId size=${history.size} notificationId=$notificationId"
        )

        activeNotifications.getOrPut(chatId) { ConcurrentHashMap.newKeySet() }.add(notificationId)

        val pendingIntent = buildContentPendingIntent(chatId, notificationId)
        val dismissPendingIntent = buildDismissPendingIntent(chatId, notificationId)
        val replyAction = buildReplyAction(chatId, notificationId)
        val readAction = buildReadAction(chatId, notificationId)

        val myself = Person.Builder().setName(stringProvider.getString("notification_person_me")).build()
        val messagingStyle = NotificationCompat.MessagingStyle(myself)
        val historySnapshot = history.toList()
        historySnapshot.forEach { entry ->
            val person = Person.Builder()
                .setName(entry.senderName)
                .setKey(entry.senderName)
                .build()
            messagingStyle.addMessage(
                NotificationCompat.MessagingStyle.Message(
                    entry.text,
                    entry.timestamp,
                    person
                )
            )
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
        val posted = runCatching {
            val builder = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(org.monogram.data.R.drawable.message_outline)
                .setStyle(messagingStyle)
                .setPriority(priority)
                .setGroup(GROUP_CHATS)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setAutoCancel(true)
                .setShortcutId(chatId.toString())
                .setLocusId(androidx.core.content.LocusIdCompat(chatId.toString()))
                .setOnlyAlertOnce(true)

            pendingIntent?.let { builder.setContentIntent(it) }
            dismissPendingIntent?.let { builder.setDeleteIntent(it) }
            replyAction?.let { builder.addAction(it) }
            readAction?.let { builder.addAction(it) }

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
                builder.setContentText(stringProvider.getString("notification_new_message"))
            }

            if (chatIcon != null) {
                runCatching { builder.setLargeIcon(getCircularBitmap(chatIcon)) }
                    .onFailure { Log.w(TAG, "Failed to set large icon for notification", it) }
            }

            notificationManager.notify(notificationId, builder.build())
            true
        }.onFailure {
            Log.e(TAG, "Failed to build rich notification, falling back", it)
        }.getOrDefault(false)

        if (!posted) {
            postFallbackNotification(
                chatId = chatId,
                chatType = chatType,
                title = chatTitle,
                text = text,
                channelId = channelId,
                notificationId = notificationId,
                pendingIntent = pendingIntent,
                dismissPendingIntent = dismissPendingIntent
            )
        }

        Log.d(TAG, "Notification posted chatId=$chatId notificationId=$notificationId")
        updateSummary()
    }

    private fun buildContentPendingIntent(chatId: Long, notificationId: Int): PendingIntent? {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                putExtra("chat_id", chatId)
            } ?: return null

        return runCatching {
            PendingIntent.getActivity(
                context,
                notificationId,
                launchIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }.onFailure {
            Log.w(TAG, "Failed to create content PendingIntent", it)
        }.getOrNull()
    }

    private fun buildDismissPendingIntent(chatId: Long, notificationId: Int): PendingIntent? {
        val dismissIntent = Intent(context, NotificationDismissReceiver::class.java).apply {
            putExtra("chat_id", chatId)
            putExtra("notification_id", notificationId)
        }

        return runCatching {
            PendingIntent.getBroadcast(
                context,
                notificationId,
                dismissIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }.onFailure {
            Log.w(TAG, "Failed to create dismiss PendingIntent", it)
        }.getOrNull()
    }

    private fun buildReplyAction(chatId: Long, notificationId: Int): NotificationCompat.Action? {
        val replyIntent = Intent(context, NotificationReplyReceiver::class.java).apply {
            putExtra("chat_id", chatId)
            putExtra("notification_id", notificationId)
        }

        val replyPendingIntent = runCatching {
            PendingIntent.getBroadcast(
                context,
                notificationId,
                replyIntent,
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }.getOrNull() ?: return null

        val remoteInput = RemoteInput.Builder(KEY_TEXT_REPLY)
            .setLabel(stringProvider.getString("menu_reply"))
            .build()

        return runCatching {
            NotificationCompat.Action.Builder(
                android.R.drawable.ic_menu_send,
                stringProvider.getString("menu_reply"),
                replyPendingIntent
            ).addRemoteInput(remoteInput).build()
        }.onFailure {
            Log.w(TAG, "Failed to build reply action", it)
        }.getOrNull()
    }

    private fun buildReadAction(chatId: Long, notificationId: Int): NotificationCompat.Action? {
        val readIntent = Intent(context, NotificationReadReceiver::class.java).apply {
            putExtra("chat_id", chatId)
            putExtra("notification_id", notificationId)
        }

        val readPendingIntent = runCatching {
            PendingIntent.getBroadcast(
                context,
                notificationId,
                readIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }.getOrNull() ?: return null

        return runCatching {
            NotificationCompat.Action.Builder(
                android.R.drawable.ic_menu_view,
                stringProvider.getString("action_mark_as_read"),
                readPendingIntent
            ).build()
        }.onFailure {
            Log.w(TAG, "Failed to build read action", it)
        }.getOrNull()
    }

    private fun postFallbackNotification(
        chatId: Long,
        chatType: TdApi.ChatType,
        title: String,
        text: String,
        channelId: String,
        notificationId: Int,
        pendingIntent: PendingIntent?,
        dismissPendingIntent: PendingIntent?
    ) {
        runCatching {
            val builder = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(org.monogram.data.R.drawable.message_outline)
                .setContentTitle(title)
                .setContentText(if (appPreferences.inAppPreview.value) text else stringProvider.getString("notification_new_message"))
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setGroup(GROUP_CHATS)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setPriority(
                    when (appPreferences.notificationPriority.value) {
                        0 -> NotificationCompat.PRIORITY_LOW
                        2 -> NotificationCompat.PRIORITY_HIGH
                        else -> NotificationCompat.PRIORITY_DEFAULT
                    }
                )

            pendingIntent?.let { builder.setContentIntent(it) }
            dismissPendingIntent?.let { builder.setDeleteIntent(it) }

            if (chatType !is TdApi.ChatTypePrivate) {
                builder.setSubText(stringProvider.getString("notification_group_chats"))
            }

            notificationManager.notify(notificationId, builder.build())
            Log.w(TAG, "Fallback notification posted chatId=$chatId notificationId=$notificationId")
        }.onFailure {
            Log.e(TAG, "Fallback notification failed chatId=$chatId notificationId=$notificationId", it)
        }
    }

    private fun notificationIdForChat(chatId: Long): Int {
        val hash = (chatId xor (chatId ushr 32)).toInt()
        return if (hash == SUMMARY_ID) SUMMARY_ID + 1 else hash
    }

    private fun senderIdToDebug(senderId: TdApi.MessageSender?): String = when (senderId) {
        null -> "null"
        is TdApi.MessageSenderUser -> "user:${senderId.userId}"
        is TdApi.MessageSenderChat -> "chat:${senderId.chatId}"
        else -> senderId.javaClass.simpleName
    }

    private fun previewText(text: String, max: Int = 80): String {
        val normalized = text.replace('\n', ' ').trim()
        return if (normalized.length <= max) normalized else normalized.take(max) + "..."
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
            messages.toList().map { message ->
                Triple(chatId, message, message.timestamp)
            }
        }.sortedByDescending { it.third }

        val totalMessagesCount = allMessages.size
        val inboxStyle = NotificationCompat.InboxStyle()

        allMessages.take(5).forEach { (chatId, message, _) ->
            val chat = chatCache[chatId]
            val senderName = message.senderName.ifBlank { stringProvider.getString("unknown_user") }
            val chatTitle = chat?.title ?: senderName

            val sb = SpannableStringBuilder()
            val title = if (chatTitle != senderName) "$chatTitle ($senderName)" else senderName

            sb.append(title, StyleSpan(Typeface.BOLD), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append("  ")
            sb.append(message.text)

            inboxStyle.addLine(sb)
        }

        val summaryTitle = stringProvider.getString(
            "notification_summary_title_format",
            totalMessagesCount,
            activeChatsCount
        )
        inboxStyle.setSummaryText(stringProvider.getString("notification_summary_text_format", activeChatsCount))
        inboxStyle.setBigContentTitle(summaryTitle)

        val builder = NotificationCompat.Builder(context, CHANNEL_PRIVATE)
            .setSmallIcon(org.monogram.data.R.drawable.message_outline)
            .setStyle(inboxStyle)
            .setGroup(GROUP_CHATS)
            .setGroupSummary(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setContentTitle(summaryTitle)

        notificationManager.notify(SUMMARY_ID, builder.build())
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            manager.createNotificationChannelGroups(
                listOf(
                    NotificationChannelGroup(GROUP_CHATS, stringProvider.getString("notification_group_chats")),
                    NotificationChannelGroup(GROUP_OTHER, stringProvider.getString("notification_group_other"))
                )
            )

            val channels = listOf(
                NotificationChannel(CHANNEL_PRIVATE, stringProvider.getString("notification_channel_private_name"), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = stringProvider.getString("notification_channel_private_description")
                    group = GROUP_CHATS
                    enableVibration(true)
                    setShowBadge(true)
                    lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
                },
                NotificationChannel(CHANNEL_GROUPS, stringProvider.getString("notification_channel_groups_name"), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = stringProvider.getString("notification_channel_groups_description")
                    group = GROUP_CHATS
                    enableVibration(true)
                    setShowBadge(true)
                    lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
                },
                NotificationChannel(CHANNEL_CHANNELS, stringProvider.getString("notification_channel_channels_name"), NotificationManager.IMPORTANCE_LOW).apply {
                    description = stringProvider.getString("notification_channel_channels_description")
                    group = GROUP_CHATS
                    enableVibration(false)
                    setShowBadge(true)
                },
                NotificationChannel(CHANNEL_OTHER, stringProvider.getString("notification_channel_other_name"), NotificationManager.IMPORTANCE_LOW).apply {
                    description = stringProvider.getString("notification_channel_other_description")
                    group = GROUP_OTHER
                }
            )

            manager.createNotificationChannels(channels)
        }
    }

    private fun getMessageText(content: TdApi.MessageContent?): String {
        fun withDetails(base: String, details: String?): String {
            val cleanDetails = details?.trim().orEmpty()
            return if (cleanDetails.isEmpty()) base else "$base $cleanDetails"
        }

        if (content == null) {
            return stringProvider.getString("reply_content_message")
        }

        return when (content) {
            is TdApi.MessageText -> sanitizeSpoilers(content.text)
            is TdApi.MessagePhoto -> withDetails("📷 ${stringProvider.getString("logs_media_photo")}", sanitizeSpoilers(content.caption))
            is TdApi.MessageVideo -> withDetails("📹 ${stringProvider.getString("logs_media_video")}", sanitizeSpoilers(content.caption))
            is TdApi.MessageVoiceNote -> "🎤 ${stringProvider.getString("logs_media_voice")}"
            is TdApi.MessageSticker -> stringProvider.getString("reply_content_sticker")
            is TdApi.MessageAnimation -> stringProvider.getString("reply_content_gif")
            is TdApi.MessageAudio -> withDetails("🎵 ${stringProvider.getString("logs_media_audio")}", content.audio?.title)
            is TdApi.MessageDocument -> withDetails("📄 ${stringProvider.getString("logs_media_document")}", content.document?.fileName)
            is TdApi.MessageLocation -> {
                val location = content.location
                if (location != null) {
                    "📍 ${stringProvider.getString("location_label")} ${location.latitude}, ${location.longitude}"
                } else {
                    "📍 ${stringProvider.getString("location_label")}"
                }
            }
            is TdApi.MessageContact -> withDetails(
                "👤 ${stringProvider.getString("logs_media_contact")}",
                listOf(content.contact?.firstName, content.contact?.lastName)
                    .filterNotNull()
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
            )
            is TdApi.MessagePoll -> withDetails("📊 ${stringProvider.getString("logs_media_poll")}", content.poll?.question?.text)
            else -> stringProvider.getString("reply_content_message")
        }
    }

    private suspend fun refreshMyUserId() {
        myUserId = coRunCatching {
            gateway.execute(TdApi.GetMe()).id
        }.getOrDefault(myUserId)
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
            getChatSuspend(chatId)?.let(callback)
        }
    }

    private suspend fun getChatSuspend(chatId: Long): TdApi.Chat? {
        chatCache[chatId]?.let { return it }

        return try {
            gateway.execute(TdApi.GetChat(chatId)).also { chat ->
                chatCache[chat.id] = chat
            }
        } catch (_: Exception) {
            null
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
        senderId: TdApi.MessageSender?,
        chat: TdApi.Chat,
        onlyIfLocal: Boolean = false,
        callback: (String, Bitmap?) -> Unit
    ) {
        val fallbackName = chat.title?.takeIf { it.isNotBlank() } ?: stringProvider.getString("unknown_user")

        if (senderId == null) {
            downloadFile(chat.photo?.small, onlyIfLocal) { bitmap ->
                callback(fallbackName, bitmap)
            }
            return
        }

        when (senderId) {
            is TdApi.MessageSenderUser -> {
                if (onlyIfLocal) {
                    val user = userCache[senderId.userId]
                    if (user == null) {
                        callback(fallbackName, null)
                        return
                    }

                    val fullName = listOf(user.firstName, user.lastName)
                        .filterNotNull()
                        .filter { it.isNotBlank() }
                        .joinToString(" ")
                    val name =
                        if (chat.type is TdApi.ChatTypePrivate) fallbackName else fullName.ifBlank { fallbackName }
                    val file =
                        user.profilePhoto?.small
                            ?: if (chat.type is TdApi.ChatTypePrivate) chat.photo?.small else null
                    downloadFile(file, true) { bitmap ->
                        callback(name, bitmap)
                    }
                    return
                }

                getUser(senderId.userId) { user ->
                    val fullName = listOf(user.firstName, user.lastName)
                        .filterNotNull()
                        .filter { it.isNotBlank() }
                        .joinToString(" ")
                    val name =
                        if (chat.type is TdApi.ChatTypePrivate) fallbackName else fullName.ifBlank { fallbackName }
                    val file =
                        user.profilePhoto?.small ?: if (chat.type is TdApi.ChatTypePrivate) chat.photo?.small else null
                    downloadFile(file, onlyIfLocal) { bitmap ->
                        callback(name, bitmap)
                    }
                }
            }

            is TdApi.MessageSenderChat -> {
                if (onlyIfLocal) {
                    val senderChat = chatCache[senderId.chatId]
                    if (senderChat == null) {
                        callback(fallbackName, null)
                        return
                    }

                    val name = senderChat.title?.takeIf { it.isNotBlank() } ?: fallbackName
                    downloadFile(senderChat.photo?.small, true) { bitmap ->
                        callback(name, bitmap)
                    }
                    return
                }

                getChat(senderId.chatId) { senderChat ->
                    val name = senderChat.title?.takeIf { it.isNotBlank() } ?: fallbackName
                    downloadFile(senderChat.photo?.small, onlyIfLocal) { bitmap ->
                        callback(name, bitmap)
                    }
                }
            }

            else -> {
                downloadFile(chat.photo?.small, onlyIfLocal) { bitmap ->
                    callback(fallbackName, bitmap)
                }
            }
        }
    }

    private fun preloadNotificationAssets(senderId: TdApi.MessageSender?, chat: TdApi.Chat) {
        resolveSender(senderId, chat, false) { _, _ -> }
        downloadFile(chat.photo?.small, false) { _ -> }
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

        val local = file.local
        val localPath = local?.path
        if (local?.isDownloadingCompleted == true && !localPath.isNullOrEmpty()) {
            val bitmap = try {
                BitmapFactory.decodeFile(localPath)
            } catch (e: Exception) {
                Log.e(TAG, "Error decoding file: $localPath", e)
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
