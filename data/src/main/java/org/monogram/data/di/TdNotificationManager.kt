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
import androidx.core.graphics.drawable.IconCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.TdApi
import org.monogram.data.core.coRunCatching
import org.monogram.data.db.dao.NotificationSettingDao
import org.monogram.data.db.model.NotificationSettingEntity
import org.monogram.data.gateway.TelegramGateway
import org.monogram.data.gateway.UpdateDispatcher
import org.monogram.data.infra.FileDownloadQueue
import org.monogram.data.notifications.NotificationMuteDecision
import org.monogram.data.notifications.NotificationMuteResolver
import org.monogram.data.notifications.NotificationRenderBatcher
import org.monogram.data.notifications.NotificationScopeState
import org.monogram.data.notifications.TdlibNotificationStateStore
import org.monogram.data.push.FcmRuntime
import org.monogram.data.push.UnifiedPushManager
import org.monogram.data.service.NotificationDismissReceiver
import org.monogram.data.service.NotificationReadReceiver
import org.monogram.data.service.NotificationReplyReceiver
import org.monogram.domain.repository.AppPreferencesProvider
import org.monogram.domain.repository.NotificationSettingsRepository
import org.monogram.domain.repository.NotificationSettingsRepository.TdNotificationScope
import org.monogram.domain.repository.PushProvider
import org.monogram.domain.repository.StringProvider
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.math.min

class TdNotificationManager(
    private val context: Context,
    private val gateway: TelegramGateway,
    private val updates: UpdateDispatcher,
    private val appPreferences: AppPreferencesProvider,
    private val notificationSettingsRepository: NotificationSettingsRepository,
    private val notificationSettingDao: NotificationSettingDao,
    private val fileQueue: FileDownloadQueue,
    private val stringProvider: StringProvider,
    private val fcmRuntime: FcmRuntime,
    private val unifiedPushManager: UnifiedPushManager,
    private val muteResolver: NotificationMuteResolver
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val notificationManager = NotificationManagerCompat.from(context)
    private val notificationStatePreferences = context.getSharedPreferences(
        NOTIFICATION_STATE_PREFS,
        Context.MODE_PRIVATE
    )
    private val notificationStateCounter = AtomicLong(
        notificationStatePreferences.getLong(KEY_NOTIFICATION_STATE_VERSION, 0L)
    )
    private val userCache = ConcurrentHashMap<Long, TdApi.User>()
    private val chatCache = ConcurrentHashMap<Long, TdApi.Chat>()
    private val messagesHistory = ConcurrentHashMap<Long, CopyOnWriteArrayList<NotificationHistoryEntry>>()
    private val lastMessageIds = ConcurrentHashMap<Long, Long>()
    private val activeNotifications = ConcurrentHashMap<Long, MutableSet<Int>>()
    private val nativeNotificationStateStore = TdlibNotificationStateStore()
    private val bitmapCache = object : LruCache<Int, Bitmap>(5 * 1024 * 1024) {
        override fun sizeOf(key: Int, value: Bitmap): Int {
            return value.byteCount
        }
    }
    private val activeDownloads = ConcurrentHashMap<Int, MutableList<(Bitmap?) -> Unit>>()
    private val notificationSettingsCache = ConcurrentHashMap<Long, NotificationSettingEntity>()
    private val scopeNotificationsEnabled = ConcurrentHashMap<TdNotificationScope, Boolean>()
    private val loadedScopeSettings = ConcurrentHashMap.newKeySet<TdNotificationScope>()
    private val nativeRenderBatcher = NotificationRenderBatcher(scope) { chatIds ->
        for (chatId in chatIds) {
            renderNativeNotifications(chatId)
        }
    }

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
        private const val NOTIFICATION_STATE_PREFS = "tdlib_notification_state"
        private const val KEY_NOTIFICATION_STATE_VERSION = "version"
        private const val KEY_NOTIFICATION_STATE_FINGERPRINT = "fingerprint"
        private const val ACTION_DEDUP_TTL_MS = 5 * 60_000L
    }

    private data class NotificationHistoryEntry(
        val messageId: Long,
        val senderName: String,
        val text: String,
        val timestamp: Long,
        val senderBitmap: Bitmap? = null
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
                    nativeNotificationStateStore.reset()
                    clearAllRenderedNotifications()
                    loadedScopeSettings.clear()
                    scopeNotificationsEnabled.clear()
                    refreshMyUserId()
                    fetchScopeNotificationSettings()
                    fetchInitialExceptions()
                    updatePushRegistration()
                }
            }
        }

        // handleCoreUpdate issues TDLib requests inline (getChat, membership checks), so
        // this consumer is orders of magnitude slower than the update rate and would be
        // the first to be conflated away on the observation flow. updateNotificationGroup
        // carries added/removed deltas and updateActiveNotifications arrives exactly once
        // before them, so none of it may be dropped or reordered.
        updates.lane(name = "notifications", scope = scope) { update ->
            handleCoreUpdate(update)
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

    private suspend fun handleCoreUpdate(update: TdApi.Update) {
        when (update) {
            is TdApi.UpdateActiveNotifications -> handleActiveNotifications(update)
            is TdApi.UpdateNotificationGroup -> handleNotificationGroupUpdate(update)
            is TdApi.UpdateNotification -> handleNotificationUpdate(update)
            is TdApi.UpdateUser -> userCache[update.user.id] = update.user
            is TdApi.UpdateFile -> handleFileUpdate(update.file)
            is TdApi.UpdateChatNotificationSettings -> {
                updateChatNotificationSettings(update.chatId, update.notificationSettings)
                chatCache[update.chatId]?.let { chat ->
                    chatCache[update.chatId] = chat.apply {
                        notificationSettings = update.notificationSettings
                    }
                }
            }

            is TdApi.UpdateChatReadInbox -> clearHistory(update.chatId)
            is TdApi.UpdateOption -> handleOptionUpdate(update)
        }
    }

    private suspend fun handleOptionUpdate(update: TdApi.UpdateOption) {
        if (update.name == "is_authenticated" && (update.value as? TdApi.OptionValueBoolean)?.value == true) {
            refreshMyUserId()
            updatePushRegistration()
            return
        }

        if (update.name == "my_id") {
            val id = (update.value as? TdApi.OptionValueInteger)?.value ?: 0L
            if (id > 0L) {
                myUserId = id
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
                    if (!fcmRuntime.isSupported) {
                        Log.w(TAG, "FCM runtime is not available in this build")
                        return@coRunCatching
                    }
                    unifiedPushManager.unregister()
                    val token = fcmRuntime.fetchToken()
                    if (token.isNullOrBlank()) {
                        Log.w(TAG, "FCM token is not available")
                        return@coRunCatching
                    }
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
                        "RegisterDevice success for UnifiedPush"
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
        nativeNotificationStateStore.clearChat(chatId)
        clearRenderedHistory(chatId)
    }

    private fun clearRenderedHistory(chatId: Long) {
        messagesHistory.remove(chatId)
        lastMessageIds.remove(chatId)
        activeNotifications.remove(chatId)?.forEach { notificationId ->
            notificationManager.cancel(notificationId)
        }
        notificationManager.cancel(notificationIdForChat(chatId))
        updateSummary()
    }

    private fun clearAllRenderedNotifications() {
        messagesHistory.keys.toList().forEach { chatId ->
            clearRenderedHistory(chatId)
        }
        notificationManager.cancel(SUMMARY_ID)
    }

    fun removeNotification(chatId: Long, notificationId: Int) {
        nativeNotificationStateStore.removeNotification(chatId, notificationId)
        removeRenderedNotification(chatId, notificationId)
    }

    /** Returns false when Android redelivers the same notification action. */
    @Synchronized
    fun consumeNotificationAction(action: String, chatId: Long, notificationId: Int): Boolean {
        val now = System.currentTimeMillis()
        val key = "action:$action:$chatId:$notificationId"
        if (notificationStatePreferences.getLong(key, 0L) > now) return false
        notificationStatePreferences.edit().putLong(key, now + ACTION_DEDUP_TTL_MS).apply()
        return true
    }

    private fun removeRenderedNotification(chatId: Long, notificationId: Int) {
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

    private suspend fun handleActiveNotifications(update: TdApi.UpdateActiveNotifications) {
        val affectedChatIds = nativeNotificationStateStore.replaceAll(update)
        persistNotificationState()
        nativeRenderBatcher.enqueue(affectedChatIds)
    }

    private suspend fun handleNotificationGroupUpdate(update: TdApi.UpdateNotificationGroup) {
        val affectedChatIds = nativeNotificationStateStore.apply(update)
        persistNotificationState()
        nativeRenderBatcher.enqueue(affectedChatIds)
    }

    private suspend fun handleNotificationUpdate(update: TdApi.UpdateNotification) {
        val affectedChatIds = nativeNotificationStateStore.apply(update)
        persistNotificationState()
        nativeRenderBatcher.enqueue(affectedChatIds)
    }

    private fun persistNotificationState() {
        val version = notificationStateCounter.incrementAndGet()
        notificationStatePreferences.edit()
            .putLong(KEY_NOTIFICATION_STATE_VERSION, version)
            .putLong(KEY_NOTIFICATION_STATE_FINGERPRINT, nativeNotificationStateStore.fingerprint())
            .apply()
    }

    private fun handleFileUpdate(file: TdApi.File) {
        val local = file.local
        val localPath = local?.path
        if (local?.isDownloadingCompleted != true || localPath.isNullOrEmpty()) {
            return
        }

        val callbacks = synchronized(activeDownloads) {
            activeDownloads.remove(file.id)
        } ?: return

        scope.launch(Dispatchers.IO) {
            val bitmap = try {
                BitmapFactory.decodeFile(localPath)
            } catch (_: Exception) {
                null
            }
            if (bitmap != null) {
                bitmapCache.put(file.id, bitmap)
            }
            callbacks.forEach { it(bitmap) }
        }
    }

    private suspend fun renderNativeNotifications(chatId: Long) {
        val notifications = nativeNotificationStateStore.getChatNotifications(chatId)
        if (notifications.isEmpty()) {
            clearRenderedHistory(chatId)
            return
        }

        val chat = getChatSuspend(chatId)
        if (chat == null) {
            Log.d(TAG, "Skip native notification render: chat unavailable, chatId=$chatId")
            clearRenderedHistory(chatId)
            return
        }

        val chatType = chat.type
        if (chatType == null) {
            Log.w(TAG, "Skip native notification render: chat type unavailable, chatId=$chatId")
            clearRenderedHistory(chatId)
            return
        }

        if (resolveMuteDecision(chat).isMuted) {
            clearRenderedHistory(chatId)
            return
        }

        val isMember = withTimeoutOrNull(1_500L) { checkMembership(chat) } ?: true
        if (!isMember) {
            Log.d(TAG, "Skip native notification render: user is not a member, chatId=$chatId")
            clearRenderedHistory(chatId)
            return
        }

        val resolvedEntries = notifications.mapNotNull { notification ->
            resolveNotificationHistoryEntry(chat, notification)
        }
        if (resolvedEntries.isEmpty()) {
            clearRenderedHistory(chatId)
            return
        }

        replaceNotificationHistory(
            chatId = chatId,
            chatType = chatType,
            historyEntries = resolvedEntries,
            chatIcon = resolvedEntries.lastOrNull()?.senderBitmap
        )
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
                            result.status is TdApi.ChatMemberStatusAdministrator ||
                            (result.status as? TdApi.ChatMemberStatusRestricted)?.isMember == true
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
                            result.status is TdApi.ChatMemberStatusAdministrator ||
                            (result.status as? TdApi.ChatMemberStatusRestricted)?.isMember == true
                }.getOrDefault(true)
            }

            else -> true
        }
    }

    private fun replaceNotificationHistory(
        chatId: Long,
        chatType: TdApi.ChatType,
        historyEntries: List<NotificationHistoryEntry>,
        chatIcon: Bitmap?
    ) {
        val trimmedHistory = historyEntries.takeLast(10)
        if (trimmedHistory.isEmpty()) {
            clearRenderedHistory(chatId)
            return
        }

        messagesHistory[chatId] = CopyOnWriteArrayList(trimmedHistory)
        lastMessageIds[chatId] = trimmedHistory.maxOf { it.messageId }
        activeNotifications[chatId] = ConcurrentHashMap.newKeySet<Int>().apply {
            add(notificationIdForChat(chatId))
        }

        postHistoryNotification(
            chatId = chatId,
            chatType = chatType,
            historySnapshot = trimmedHistory,
            chatIcon = chatIcon
        )
    }

    private fun postHistoryNotification(
        chatId: Long,
        chatType: TdApi.ChatType,
        historySnapshot: List<NotificationHistoryEntry>,
        chatIcon: Bitmap?
    ) {
        val latestEntry = historySnapshot.lastOrNull() ?: run {
            clearRenderedHistory(chatId)
            return
        }
        val notificationId = notificationIdForChat(chatId)
        val channelId = when (chatType) {
            is TdApi.ChatTypePrivate -> CHANNEL_PRIVATE
            is TdApi.ChatTypeBasicGroup -> CHANNEL_GROUPS
            is TdApi.ChatTypeSupergroup -> if (chatType.isChannel) CHANNEL_CHANNELS else CHANNEL_GROUPS
            else -> CHANNEL_OTHER
        }

        Log.d(
            TAG,
            "Notification history updated chatId=$chatId size=${historySnapshot.size} notificationId=$notificationId"
        )

        activeNotifications.getOrPut(chatId) { ConcurrentHashMap.newKeySet() }.add(notificationId)

        val pendingIntent = buildContentPendingIntent(chatId, notificationId)
        val dismissPendingIntent = buildDismissPendingIntent(chatId, notificationId)
        val replyAction = buildReplyAction(chatId, notificationId)
        val readAction = buildReadAction(chatId, notificationId)

        val myself = Person.Builder().setName(stringProvider.getString("notification_person_me")).build()
        val messagingStyle = NotificationCompat.MessagingStyle(myself)
        historySnapshot.forEach { entry ->
            val personBuilder = Person.Builder()
                .setName(entry.senderName)
                .setKey(entry.senderName)

            entry.senderBitmap?.let { bitmap ->
                personBuilder.setIcon(IconCompat.createWithBitmap(getCircularBitmap(bitmap)))
            }

            messagingStyle.addMessage(
                NotificationCompat.MessagingStyle.Message(
                    entry.text,
                    entry.timestamp,
                    personBuilder.build()
                )
            )
        }

        val isGroup = when (chatType) {
            is TdApi.ChatTypePrivate -> false
            is TdApi.ChatTypeSupergroup -> !chatType.isChannel
            else -> true
        }
        messagingStyle.isGroupConversation = isGroup

        val chatTitle = chatCache[chatId]?.title ?: latestEntry.senderName
        if (isGroup) {
            messagingStyle.conversationTitle = chatTitle
        }

        val latestText = latestEntry.text
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
            builder.setContentText(latestText)

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
                text = latestText,
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

    private suspend fun resolveNotificationHistoryEntry(
        chat: TdApi.Chat,
        notification: TdApi.Notification
    ): NotificationHistoryEntry? {
        return when (val notificationType = notification.type) {
            is TdApi.NotificationTypeNewMessage -> {
                val message = notificationType.message ?: return null
                val text = if (appPreferences.showSenderOnly.value) {
                    stringProvider.getString("notification_new_message")
                } else {
                    getMessageText(message.content)
                }
                if (text.isBlank()) {
                    return null
                }

                val (senderName, senderBitmap) = resolveSenderSuspend(
                    senderId = message.senderId,
                    chat = chat,
                    onlyIfLocal = true
                )
                NotificationHistoryEntry(
                    messageId = message.id,
                    senderName = senderName,
                    text = text,
                    timestamp = notification.date.toLong() * 1000L,
                    senderBitmap = senderBitmap
                )
            }

            is TdApi.NotificationTypeNewPushMessage -> {
                val senderName = notificationType.senderName
                    ?.takeIf { it.isNotBlank() }
                    ?: chat.title?.takeIf { it.isNotBlank() }
                    ?: stringProvider.getString("unknown_user")
                NotificationHistoryEntry(
                    messageId = notification.id.toLong(),
                    senderName = senderName,
                    text = getPushMessageText(notificationType.content),
                    timestamp = notification.date.toLong() * 1000L
                )
            }

            else -> NotificationHistoryEntry(
                messageId = notification.id.toLong(),
                senderName = chat.title?.takeIf { it.isNotBlank() }
                    ?: stringProvider.getString("unknown_user"),
                text = stringProvider.getString("notification_new_message"),
                timestamp = notification.date.toLong() * 1000L
            )
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

    private fun getPushMessageText(content: TdApi.PushMessageContent?): String {
        return when (content) {
            is TdApi.PushMessageContentText -> sanitizeSpoilers(content.text?.let {
                TdApi.FormattedText(
                    it,
                    null
                )
            })

            is TdApi.PushMessageContentPhoto -> {
                val caption = content.caption?.trim().orEmpty()
                if (caption.isBlank()) {
                    stringProvider.getString("logs_media_photo")
                } else {
                    "📷 ${stringProvider.getString("logs_media_photo")} $caption"
                }
            }

            is TdApi.PushMessageContentVideo -> {
                val caption = content.caption?.trim().orEmpty()
                if (caption.isBlank()) {
                    stringProvider.getString("logs_media_video")
                } else {
                    "📹 ${stringProvider.getString("logs_media_video")} $caption"
                }
            }

            is TdApi.PushMessageContentVoiceNote -> "🎤 ${stringProvider.getString("logs_media_voice")}"
            is TdApi.PushMessageContentAudio -> {
                val title = content.audio?.title?.trim().orEmpty()
                if (title.isBlank()) {
                    stringProvider.getString("logs_media_audio")
                } else {
                    "🎵 ${stringProvider.getString("logs_media_audio")} $title"
                }
            }

            is TdApi.PushMessageContentDocument -> {
                val fileName = content.document?.fileName?.trim().orEmpty()
                if (fileName.isBlank()) {
                    stringProvider.getString("logs_media_document")
                } else {
                    "📄 ${stringProvider.getString("logs_media_document")} $fileName"
                }
            }

            is TdApi.PushMessageContentContact -> content.name.ifBlank {
                stringProvider.getString("logs_media_contact")
            }

            is TdApi.PushMessageContentSticker -> stringProvider.getString("reply_content_sticker")
            is TdApi.PushMessageContentPoll -> content.question.ifBlank {
                stringProvider.getString("logs_media_poll")
            }

            is TdApi.PushMessageContentChatChangeTitle -> content.title.ifBlank {
                stringProvider.getString("notification_new_message")
            }

            is TdApi.PushMessageContentChatAddMembers -> content.memberName.ifBlank {
                stringProvider.getString("notification_new_message")
            }

            is TdApi.PushMessageContentBasicGroupChatCreate,
            is TdApi.PushMessageContentVideoChatStarted,
            is TdApi.PushMessageContentVideoChatEnded,
            is TdApi.PushMessageContentInviteVideoChatParticipants,
            is TdApi.PushMessageContentChatChangePhoto,
            is TdApi.PushMessageContentChatSetBackground,
            is TdApi.PushMessageContentChatSetTheme,
            is TdApi.PushMessageContentChatDeleteMember,
            is TdApi.PushMessageContentChatJoinByLink,
            is TdApi.PushMessageContentChatJoinByRequest,
            is TdApi.PushMessageContentRecurringPayment,
            is TdApi.PushMessageContentSuggestProfilePhoto,
            is TdApi.PushMessageContentSuggestBirthdate,
            is TdApi.PushMessageContentProximityAlertTriggered,
            is TdApi.PushMessageContentChecklistTasksAdded,
            is TdApi.PushMessageContentChecklistTasksDone,
            is TdApi.PushMessageContentPollOptionAdded,
            is TdApi.PushMessageContentMessageForwards,
            is TdApi.PushMessageContentMediaAlbum -> stringProvider.getString("notification_new_message")

            else -> stringProvider.getString("notification_new_message")
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

    private suspend fun resolveSenderSuspend(
        senderId: TdApi.MessageSender?,
        chat: TdApi.Chat,
        onlyIfLocal: Boolean = false
    ): Pair<String, Bitmap?> = suspendCancellableCoroutine { continuation ->
        resolveSender(senderId, chat, onlyIfLocal) { senderName, senderBitmap ->
            if (continuation.isActive) {
                continuation.resume(senderName to senderBitmap)
            }
        }
    }

    private fun preloadNotificationAssets(senderId: TdApi.MessageSender?, chat: TdApi.Chat) {
        // Avoid network-triggered preloads for notifications; use local cache only.
        resolveSender(senderId, chat, true) { _, _ -> }
        downloadFile(chat.photo?.small, true) { _ -> }
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
