package org.monogram.push

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.monogram.core.common.AppLog
import org.monogram.core.common.Outcome
import org.monogram.core.common.push.IncomingPush
import org.monogram.core.common.push.NO_PUSH_STATUS
import org.monogram.core.common.push.NotificationLocalStore
import org.monogram.core.common.push.PUSH_STATUS_CHOOSE_DISTRIBUTOR
import org.monogram.core.common.push.PUSH_STATUS_DISTRIBUTOR_GONE
import org.monogram.core.common.push.PUSH_STATUS_NO_DISTRIBUTOR
import org.monogram.core.common.push.PushAction
import org.monogram.core.common.push.PushChannelKind
import org.monogram.core.common.push.PushPayload
import org.monogram.core.common.push.PushProviderMode
import org.monogram.core.common.push.PushRegistration
import org.monogram.core.common.push.PushTransport
import org.monogram.core.common.push.PushWakeGate
import org.monogram.core.common.push.acceptsFcmToken
import org.monogram.core.common.push.acceptsUnifiedPushEndpoint
import org.monogram.core.common.push.decideNotification
import org.monogram.core.common.push.folderMemberIds
import org.monogram.core.common.push.historyReadUpTo
import org.monogram.core.common.push.liveMessagePayload
import org.monogram.core.common.push.normalizeDecrypted
import org.monogram.core.common.push.normalizeIncomingBody
import org.monogram.core.common.push.notificationPeerKind
import org.monogram.core.common.push.parsePushPayload
import org.monogram.core.common.push.planPushProvider
import org.monogram.core.common.push.pushRegistrationChange
import org.monogram.core.common.push.shouldRefreshDialogsOnWake
import org.monogram.core.common.push.shouldSyncOnWake
import org.monogram.core.common.push.simplePushEndpoint
import org.monogram.core.common.push.webPushRegistration
import org.monogram.core.models.Chat
import org.monogram.core.models.Folder
import org.monogram.core.models.Message
import org.monogram.core.models.NotifyDefaults
import org.monogram.core.models.NotifyException
import org.monogram.core.models.NotifySettings
import org.monogram.core.models.PeerId
import org.monogram.core.models.PushDebugState
import org.monogram.core.models.PushTokenType
import org.monogram.core.models.peerAvatarCacheKey
import org.monogram.network.bridge.MtprotoClient
import org.monogram.network.bridge.MtprotoUpdate
import org.monogram.network.http.MediaRepository
import org.unifiedpush.android.connector.UnifiedPush
import java.util.Collections
import kotlin.coroutines.resume

private val VISUAL_LOC_KEYS = listOf("PHOTO", "VIDEO", "GIF", "STICKER", "ROUND")
private val VISUAL_MEDIA_KINDS =
    setOf("photo", "video", "gif", "sticker", "sticker_animated", "document")

class PushCoordinator(
    private val context: Context,
    private val client: MtprotoClient,
    private val store: NotificationLocalStore,
    private val mediaRepository: MediaRepository,
    @Suppress("unused") private val storeFactory: StoreFactory,
    private val sessionStore: org.monogram.core.database.SessionMetadataStore,
) : PushRegistration {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile
    var openChatId: Long? = null

    @Volatile
    private var openTopicId: Int? = null

    @Volatile
    private var knownChats: List<Chat> = emptyList()

    @Volatile
    private var lastPayload: PushPayload? = null
    private val notifySettingsMutex = Mutex()
    @Volatile
    var appForeground: Boolean = false
    @Volatile
    var folders: List<Folder> = emptyList()
    @Volatile
    var exceptions: List<NotifyException> = emptyList()
    @Volatile
    var users: NotifySettings = NotifySettings()
    @Volatile
    var chats: NotifySettings = NotifySettings()
    @Volatile
    var broadcasts: NotifySettings = NotifySettings()
    @Volatile
    var chatPhotos: Map<Long, String> = emptyMap()
    private val mediaLookups: MutableSet<Long> = Collections.synchronizedSet(HashSet())
    private val wakeGate = PushWakeGate()
    private var wakeJob: Job? = null
    @Volatile
    private var notifySettingsLoaded = false
    private var notifySettingsJob: Job? = null
    private var repeatJob: Job? = null
    private var refreshJob: Job? = null
    private var updatesJob: Job? = null
    @Volatile
    private var accountUserId: Long = 0L

    init {
        // A push can arrive before the settings RPC (or with no network at all): start from the copy
        // the chat list cached, so a muted chat is not notified just because nothing was loaded yet.
        // [notifySettingsLoaded] stays false on purpose: exceptions, folders and notification
        // channels still have to come from the server.
        store.notifyDefaults?.let { cached ->
            users = cached.users
            chats = cached.chats
            broadcasts = cached.broadcasts
        }
        store.notifyExceptions?.let { exceptions = it }
    }

    fun start() {
        NotificationChannels.ensure(context)
        NotificationChannels.applyPrefs(context, store)
        scheduleRepeat()
        updatesJob?.cancel()
        updatesJob = scope.launch {
            client.updates().collect { update ->
                when (update) {
                    is MtprotoUpdate.NotifySettingsChanged -> notifySettingsMutex.withLock {
                        val current = if (update.peerKind == "peer") {
                            store.notifyExceptions.orEmpty().firstOrNull {
                                it.chatId == update.chatId && it.topicId == update.topicId
                            }?.settings
                        } else when (update.peerKind) {
                            "users" -> store.notifyDefaults?.users
                            "chats" -> store.notifyDefaults?.chats
                            "broadcasts" -> store.notifyDefaults?.broadcasts
                            else -> null
                        }
                        store.cacheNotifySettings(
                            update.peerKind,
                            update.chatId.value,
                            (current ?: NotifySettings()).copy(muteUntil = update.muteUntil),
                            update.topicId
                        )
                    }

                    is MtprotoUpdate.FoldersChanged -> folders = update.folders
                    is MtprotoUpdate.ChatsChanged -> rememberChatPhotos(
                        (knownChats.associateBy { it.id } + update.chats.associateBy { it.id }).values.toList(),
                    )

                    is MtprotoUpdate.NewMessage -> presentLiveMessage(update.message)

                    else -> Unit
                }
            }
        }
        refreshJob?.cancel()
        refreshJob = scope.launch {
            while (true) {
                delay(24L * 60L * 60L * 1000L)
                reregister()
            }
        }
    }

    override fun setAccountUserId(userId: Long) {
        accountUserId = userId
    }

    override fun distributors(): List<String> = unifiedPushDistributors()

    fun requestPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT < 33) return
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                2301
            )
        }
    }

    override fun debugState(): PushDebugState =
        store.debugState(gmsAvailable(), distributor(), permissionGranted())

    override fun gmsAvailable(): Boolean =
        GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS

    override fun distributor(): String = runCatching {
        UnifiedPush.getAckDistributor(context).orEmpty()
    }.getOrDefault("")

    fun permissionGranted(): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun setForeground(value: Boolean) {
        appForeground = value
        if (value) {
            openChatId?.let { dismissVisibleChat(it) }
            // The app is open and the session is connected: load server mute/preview/folder state.
            ensureNotifySettings()
        }
    }

    /**
     * Loads notify settings, exceptions and folders once per process. The policy otherwise falls back
     * to defaults, which would notify muted chats and leave every chat on its category channel with
     * no folder channels at all.
     */
    @Synchronized
    fun ensureNotifySettings() {
        if (notifySettingsLoaded || notifySettingsJob?.isActive == true) return
        notifySettingsJob = scope.launch {
            // account.getNotifySettings needs a logged-in auth key. Before sign-in the
            // server answers 401 AUTH_KEY_UNREGISTERED and that used to kill login.
            if (client.isLocallyAuthorized() != Outcome.Ok(true)) {
                notifySettingsJob = null
                return@launch
            }
            refreshNotifySettings()
        }
    }

    override fun onVisibleChat(chatId: Long?, topicId: Int?) {
        openChatId = chatId
        openTopicId = topicId
        if (chatId != null) dismissVisibleChat(chatId)
    }

    override fun onChatRead(chatId: Long) {
        if (!NotificationPresenter.suppressVisibleBubble(context, chatId, store.bubblesEnabled)) {
            dismissChat(chatId)
        }
    }

    override fun onLogout() {
        notifySettingsLoaded = false
        notifySettingsJob?.cancel()
        notifySettingsJob = null
        accountUserId = 0L
        knownChats = emptyList()
        lastPayload = null
        exceptions = emptyList()
        users = NotifySettings()
        chats = NotifySettings()
        broadcasts = NotifySettings()
        store.notifyDefaults = null
        store.notifyExceptions = null
        NotificationPresenter.clear(context)
        store.lastShownChatId = 0L
        store.shareChatIds = emptyList()
        store.clearPushIdentity(clearSecret = true)
    }

    override suspend fun unregisterPush() {
        val token = store.token()
        val type = PushTokenType.fromCode(store.tokenType())
        if (type != null && token.isNotBlank()) {
            runCatching { client.unregisterDevice(tokenType = type, token = token) }
        }
        store.clearPushIdentity()
        unregisterUnifiedPushConnector()
    }

    private fun dismissChat(chatId: Long) {
        NotificationPresenter.cancel(context, chatId, store.badgeSettings())
        if (store.lastShownChatId == chatId) store.lastShownChatId = 0L
    }

    override fun requestPermission() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    override fun applyChannels() {
        NotificationChannels.applyPrefs(context, store)
        scheduleRepeat()
    }

    override suspend fun reregister() {
        NotificationChannels.ensure(context, folders, pruneFolders = true)
        val plan = planPushProvider(
            requested = store.providerMode,
            playServices = gmsAvailable(),
            firebaseConfigured = firebaseConfigured(),
            unifiedPushAvailable = unifiedPushAvailable(),
        )
        when (plan.register) {
            PushTransport.Fcm -> registerFcm()
            PushTransport.UnifiedPush -> {
                plan.status?.let { store.setLastRegister(it) }
                registerUnifiedPush()
            }

            null -> {
                unregisterPush()
                store.setLastRegister(plan.status ?: NO_PUSH_STATUS)
            }
        }
    }

    private fun firebaseConfigured(): Boolean =
        runCatching { FirebaseApp.getApps(context).isNotEmpty() }.getOrDefault(false)

    private fun unifiedPushAvailable(): Boolean =
        runCatching { UnifiedPush.getDistributors(context).isNotEmpty() }.getOrDefault(false)

    private suspend fun registerFcm() {
        val token = runCatching {
            suspendCancellableCoroutine { cont ->
                FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                    if (!cont.isActive) return@addOnCompleteListener
                    cont.resume(if (task.isSuccessful) task.result else null)
                }
            }
        }.getOrNull()
        if (token.isNullOrBlank()) {
            store.setLastRegister("fcm token unavailable")
            return
        }
        registerToken(PushTokenType.Fcm, token)
    }

    private fun registerUnifiedPush() {
        val chosen = store.distributorPackage
        val available = unifiedPushDistributors()
        val explicit = store.providerMode == PushProviderMode.ForceUnifiedPush
        if (chosen.isBlank()) {
            if (explicit) {
                store.setLastRegister(
                    if (available.isEmpty()) PUSH_STATUS_NO_DISTRIBUTOR else PUSH_STATUS_CHOOSE_DISTRIBUTOR,
                )
                return
            }
        } else if (available.isNotEmpty() && chosen !in available) {
            store.setLastRegister(PUSH_STATUS_DISTRIBUTOR_GONE)
            return
        } else {
            runCatching { UnifiedPush.saveDistributor(context, chosen) }
        }
        val instance = pushInstance()
        val previous = store.pushInstance
        if (previous.isNotBlank() && previous != instance) {
            runCatching { UnifiedPush.unregister(context, previous) }
        }
        store.pushInstance = instance
        runCatching {
            val register = {
                UnifiedPush.register(
                    context,
                    instance = instance,
                    messageForDistributor = "Monogram"
                )
            }
            if (chosen.isNotBlank()) {
                register()
            } else {
                UnifiedPush.tryUseCurrentOrDefaultDistributor(context) { success ->
                    if (success) {
                        register()
                    } else {
                        store.setLastRegister(PUSH_STATUS_NO_DISTRIBUTOR)
                        AppLog.warn("push", "no UnifiedPush distributor")
                    }
                }
            }
        }.onFailure {
            store.setLastRegister("unifiedpush unavailable")
            AppLog.warn("push", "unifiedpush unavailable")
        }
    }

    private fun unifiedPushDistributors(): List<String> =
        runCatching { UnifiedPush.getDistributors(context) }.getOrDefault(emptyList())

    /**
     * Connector instances are supported. This install keeps one active account, so the instance
     * is that account's user id when it is known and the connector default otherwise.
     */
    private fun pushInstance(): String =
        accountUserId.takeIf { it != 0L }?.let { "account:$it" } ?: "default"

    private fun unregisterUnifiedPushConnector() {
        val instance = store.pushInstance
        runCatching {
            if (instance.isBlank()) UnifiedPush.unregister(context) else UnifiedPush.unregister(
                context,
                instance
            )
        }
    }

    fun onWebPushEndpoint(endpoint: String, p256dh: String?, auth: String?) {
        scope.launch {
            if (!acceptsUnifiedPushEndpoint()) return@launch
            val (type, token) = webPushRegistration(endpoint, p256dh, auth)
            if (type == PushTokenType.Simple) {
                val simple = simplePushEndpoint(token, store.simplePushGateway)
                simple.warning?.let { store.setLastRegister(it) }
                registerToken(type, simple.token)
            } else {
                registerToken(type, token)
            }
        }
    }

    private fun acceptsUnifiedPushEndpoint(): Boolean =
        acceptsUnifiedPushEndpoint(store.providerMode, currentPlan().register)

    private fun currentPlan() = planPushProvider(
        requested = store.providerMode,
        playServices = gmsAvailable(),
        firebaseConfigured = firebaseConfigured(),
        unifiedPushAvailable = unifiedPushAvailable(),
    )

    fun onFcmToken(token: String) {
        scope.launch {
            if (!acceptsFcmToken(currentPlan().register)) return@launch
            registerToken(PushTokenType.Fcm, token)
        }
    }

    private suspend fun registerToken(type: PushTokenType, token: String) {
        val change = pushRegistrationChange(
            previousType = PushTokenType.fromCode(store.tokenType()),
            previousToken = store.token(),
            nextType = type,
            nextToken = token,
        )
        change.previous?.let { (previousType, previousToken) ->
            runCatching { client.unregisterDevice(tokenType = previousType, token = previousToken) }
        }
        if (change.unregisterUnifiedPush) unregisterUnifiedPushConnector()
        store.setToken(type, token)
        val result = client.registerDevice(
            tokenType = type,
            token = token,
            secret = if (type == PushTokenType.Fcm) store.secret() else ByteArray(0),
            noMuted = store.noMuted,
            appSandbox = store.appSandbox,
        )
        store.setLastRegister(
            when (result) {
                is Outcome.Ok -> "${type.name.lowercase()} ok"
                is Outcome.Err -> result.telegram?.type ?: "error"
            },
        )
        AppLog.api(
            "registerDevice",
            "type=${type.code} result=${
                store.debugState(
                    gmsAvailable(),
                    distributor(),
                    permissionGranted()
                ).lastRegister
            }"
        )
    }

    override fun simulate(locKey: String) {
        val json =
            """{"loc_key":"$locKey","loc_args":["Debug","Test notification"],"custom":{"from_id":1,"msg_id":1}}"""
        scope.launch { handlePayload(json, wake = false, joinWake = false) }
    }

    fun handleFcm(payload: String?, onComplete: (() -> Unit)? = null) {
        handleIncoming(fcm = true, body = payload?.encodeToByteArray(), onComplete = onComplete)
    }

    fun handleIncoming(fcm: Boolean, body: ByteArray?, onComplete: (() -> Unit)? = null) {
        scope.launch {
            try {
                when (val incoming = normalizeIncomingBody(fcm, body)) {
                    IncomingPush.Wake -> {
                        if (!fcm) ensureNotifySettings()
                        wakeFetchAndJoin()
                    }

                    is IncomingPush.Decrypt -> when (val decrypted =
                        client.decryptPushPayload(store.secret(), incoming.cipher)) {
                        is Outcome.Ok -> when (val plain = normalizeDecrypted(decrypted.value)) {
                            is IncomingPush.Present -> handlePayload(
                                plain.json,
                                wake = true,
                                joinWake = true
                            )

                            else -> wakeFetchAndJoin()
                        }

                        is Outcome.Err -> {
                            AppLog.warn("push", "decrypt failed")
                            wakeFetchAndJoin()
                        }
                    }

                    is IncomingPush.Present -> handlePayload(
                        incoming.json,
                        wake = true,
                        joinWake = true
                    )
                }
            } finally {
                onComplete?.invoke()
            }
        }
    }

    fun handleWake() {
        ensureNotifySettings()
        wakeFetch()
    }

    private fun presentLiveMessage(message: Message) {
        if (appForeground || message.outgoing || message.pending) return
        val chat = knownChats.firstOrNull { it.id == message.id.chatId }
        val payload = liveMessagePayload(
            chatId = message.id.chatId.value,
            messageId = message.id.id,
            outgoing = false,
            text = message.text,
            fileName = message.fileName,
            senderName = message.senderName,
            senderId = message.senderId?.value,
            title = chat?.title.orEmpty(),
            kind = chat?.let { notificationPeerKind(it) } ?: PushChannelKind.Private,
        ) ?: return
        scope.launch { handlePayload(payload, wake = false, joinWake = false) }
    }

    private suspend fun handlePayload(json: String, wake: Boolean, joinWake: Boolean) {
        handlePayload(parsePushPayload(json), wake, joinWake)
    }

    private suspend fun handlePayload(
        payload: PushPayload,
        wake: Boolean,
        joinWake: Boolean,
    ) {
        if (accountUserId == 0L) accountUserId = sessionStore.readAuthorizedUserId()?.value ?: 0L
        if (payload.userId != null && (accountUserId == 0L || payload.userId != accountUserId)) return
        AppLog.api("notify", "parsed loc=${payload.locKey} action=${payload.action}")
        store.recordPayload(payload)
        suspend fun maybeWake() {
            if (!wake) return
            if (joinWake) wakeFetchAndJoin() else wakeFetch()
        }
        when (payload.action) {
            PushAction.SessionRevoke -> {
                unregisterPush()
                onLogout()
                client.logout()
            }

            PushAction.Delete -> {
                payload.chatId?.let { chatId ->
                    val ids =
                        payload.deletedIds.ifEmpty { listOfNotNull(payload.messageId?.takeIf { it > 0 }) }
                    if (ids.isEmpty()) dismissChat(chatId)
                    else NotificationPresenter.dropMessages(
                        context,
                        chatId,
                        ids.toSet(),
                        upTo = null,
                        badge = store.badgeSettings()
                    )
                }
                maybeWake()
            }

            PushAction.ReadHistory, PushAction.ReadReaction -> {
                payload.chatId?.let { chatId ->
                    NotificationPresenter.dropMessages(
                        context,
                        chatId,
                        dropIds = emptySet(),
                        upTo = historyReadUpTo(payload.messageId ?: 0, payload.maxId ?: 0),
                        badge = store.badgeSettings(),
                    )
                }
                maybeWake()
            }

            PushAction.Wake, PushAction.Ignore -> maybeWake()
            PushAction.Show -> {
                AppLog.api("notify", "loc=${payload.locKey} fg=$appForeground")
                ensureNotifySettings()
                withTimeoutOrNull(15_000L) { notifySettingsJob?.join() }
                if (!notifySettingsLoaded &&
                    (!store.hasNotifySettingsCache || store.mutedFolders().isNotEmpty())
                ) {
                    maybeWake()
                    return
                }
                val unknownPeer =
                    payload.chatId != null && knownChats.none { it.id.value == payload.chatId }
                if (unknownPeer || store.mutedFolders().isNotEmpty()) {
                    val dialogs = withTimeoutOrNull(5_000L) { client.getChats() }
                    if (dialogs is Outcome.Ok) rememberChatPhotos(dialogs.value)
                    else if (store.mutedFolders().isNotEmpty()) {
                        maybeWake()
                        return
                    }
                }
                val now = (System.currentTimeMillis() / 1000L).toInt()
                val mutedFolderChats = folders
                    .filter { it.id in store.mutedFolders() }
                    .flatMap { folderMemberIds(it, knownChats) }
                    .toSet()
                val decision = decideNotification(
                    payload,
                    policy(mutedFolderChats),
                    now,
                    appForeground,
                    openChatId,
                    openTopicId,
                )
                val folderId = folders.firstOrNull { folder ->
                    payload.chatId != null && payload.chatId in folderMemberIds(folder, knownChats)
                }?.id
                NotificationChannels.ensure(context, folders)
                val folderPopup = folderId?.let { store.categoryPopup("folder_$it") } ?: true
                val shown = if (folderPopup) decision else decision.copy(popup = false)
                if (!shown.show) {
                    AppLog.api("notify", "suppressed loc=${payload.locKey}")
                }
                present(payload, shown, folderId)
                if (decision.show) {
                    lastPayload = payload
                    payload.chatId?.let { store.lastShownChatId = it }
                    scheduleRepeat()
                }
                maybeWake()
            }
        }
    }

    fun handleNotificationAction(intent: Intent, done: () -> Unit) {
        scope.launch {
            try {
                if (intent.action == NotificationPresenter.ACTION_DISMISS) {
                    NotificationPresenter.refreshSummary(context)
                    return@launch
                }
                val chatId = intent.getLongExtra(NotificationPresenter.EXTRA_CHAT_ID, 0L)
                val messageId = intent.getIntExtra(NotificationPresenter.EXTRA_MESSAGE_ID, 0)
                AppLog.api("notify", "action=${intent.action} chat=$chatId")
                if (chatId == 0L) return@launch
                when (intent.action) {
                    NotificationPresenter.ACTION_REPLY -> {
                        val text = RemoteInput.getResultsFromIntent(intent)
                            ?.getCharSequence(NotificationPresenter.KEY_TEXT_REPLY)
                            ?.toString()
                            ?.trim()
                            .orEmpty()
                        if (text.isEmpty()) return@launch
                        val replyTo = messageId.takeIf { it > 0 } ?: 0
                        val sent = client.connect() is Outcome.Ok &&
                                client.sendText(
                                    PeerId(chatId),
                                    text,
                                    replyToMsgId = replyTo
                                ) is Outcome.Ok
                        if (sent) dismissChat(chatId)
                        else NotificationPresenter.showNotSent(context, chatId)
                    }

                    NotificationPresenter.ACTION_MARK_READ -> {
                        val upTo = historyReadUpTo(
                            messageId,
                            intent.getIntExtra(NotificationPresenter.EXTRA_MAX_ID, 0),
                        )
                        val read = client.connect() is Outcome.Ok &&
                                client.readHistory(PeerId(chatId), upTo) is Outcome.Ok
                        if (read) dismissChat(chatId)
                        else NotificationPresenter.showNotSent(context, chatId)
                    }

                    NotificationPresenter.ACTION_MUTE -> {
                        notifySettingsMutex.withLock {
                            val until =
                                (System.currentTimeMillis() / 1000L + 3_600).coerceAtMost(Int.MAX_VALUE.toLong())
                                    .toInt()
                            val current = store.notifyExceptions.orEmpty().firstOrNull {
                                it.chatId.value == chatId && it.topicId == null
                            }?.settings ?: NotifySettings()
                            val settings = current.copy(muteUntil = until)
                            val muted = client.connect() is Outcome.Ok &&
                                    client.updateNotifySettings(
                                        "peer",
                                        settings,
                                        PeerId(chatId)
                                    ) is Outcome.Ok
                            if (muted) {
                                store.cacheNotifySettings("peer", chatId, settings)
                                dismissChat(chatId)
                            }
                        }
                    }
                }
            } finally {
                done()
            }
        }
    }

    private fun rememberChatPhotos(chats: List<Chat>) {
        knownChats =
            (knownChats.associateBy { it.id } + chats.associateBy { it.id }).values.toList()
        chatPhotos = chats.mapNotNull { chat ->
            chat.photoCacheKey?.takeIf { it.isNotBlank() }?.let { chat.id.value to it }
        }.toMap()
    }

    private fun avatarFile(chatId: Long): java.io.File? {
        chatPhotos[chatId]?.let { key -> mediaRepository.cachedFile(key)?.let { return it } }
        return mediaRepository.cachedAvatar(PeerId(chatId))
    }

    private fun present(
        payload: PushPayload,
        decision: org.monogram.core.common.push.NotificationDecision,
        folderId: Int?,
    ) {
        val chatId = payload.chatId ?: 0L
        val cached = if (chatId != 0L) avatarFile(chatId) else null
        val mode = if (chatId != 0L) store.peerMode(chatId) else null
        val badge = store.badgeSettings()
        NotificationPresenter.show(context, payload, decision, folderId, cached, mode, badge)
        if (chatId == 0L || !decision.show) return
        if (decision.preview) {
            scope.launch {
                val messageId = payload.messageId ?: 0
                if (messageId <= 0) return@launch
                // A text push can still be a captioned photo, and this push path carries no
                // attachb64, so the media kind has to come from the message itself. The lookup is
                // done once per message and only while its notification is on screen.
                if (!payload.isVisualMedia() && !rememberMediaLookup(
                        chatId,
                        messageId
                    )
                ) return@launch
                val mediaKind = when {
                    payload.isVisualMedia() -> "photo"
                    else -> messageMediaKind(chatId, messageId) ?: return@launch
                }
                if (mediaKind !in VISUAL_MEDIA_KINDS) return@launch
                if (payload.isVisualMedia()) rememberMediaLookup(chatId, messageId)
                mediaThumbFile(chatId, messageId)?.let { file ->
                    NotificationPresenter.show(
                        context,
                        payload,
                        decision,
                        folderId,
                        avatarFile(chatId),
                        mode,
                        badge,
                        quiet = true,
                        pictureFile = file,
                    )
                }
            }
        }
        if (cached != null) return
        scope.launch {
            if (chatPhotos[chatId] == null) {
                when (val result = client.getChats()) {
                    is Outcome.Ok -> rememberChatPhotos(result.value)
                    is Outcome.Err -> Unit
                }
            }
            avatarFile(chatId)?.let { file ->
                NotificationPresenter.show(
                    context,
                    payload,
                    decision,
                    folderId,
                    file,
                    mode,
                    badge,
                    quiet = true
                )
                return@launch
            }
            val key = chatPhotos[chatId] ?: peerAvatarCacheKey(PeerId(chatId))
            val file = when (val result = mediaRepository.ensureLocalAvatar(PeerId(chatId), key)) {
                is Outcome.Ok -> result.value
                is Outcome.Err -> null
            } ?: return@launch
            NotificationPresenter.show(
                context,
                payload,
                decision,
                folderId,
                file,
                mode,
                badge,
                quiet = true
            )
        }
    }

    /**
     * Whether a thumbnail is worth fetching: a visual loc key (media without a caption) or an
     * `attachb64` blob, which the server only sends for media; a captioned photo arrives as
     * `MESSAGE_TEXT` with the caption, so the loc key alone would miss it.
     */
    private fun PushPayload.isVisualMedia(): Boolean =
        attachB64 != null || VISUAL_LOC_KEYS.any { locKey.contains(it) }

    /** Media kind of a message (`Message.mediaKind`), used when a text push turns out to be media. */
    private suspend fun messageMediaKind(chatId: Long, messageId: Int): String? =
        when (val result =
            client.getHistoryPage(PeerId(chatId), limit = 1, offsetId = messageId + 1)) {
            is Outcome.Ok -> result.value.firstOrNull { it.id.id == messageId }?.mediaKind
            is Outcome.Err -> null
        }

    /** One thumbnail lookup per message, bounded so a redelivered push cannot repeat the fetch. */
    private fun rememberMediaLookup(chatId: Long, messageId: Int): Boolean {
        if (mediaLookups.size > 256) mediaLookups.clear()
        return mediaLookups.add(chatId * 31 + messageId)
    }

    /**
     * Thumbnail for a media push, so the shade shows the picture instead of only "sent a photo".
     * Bounded by a 2 MiB file cap and cached per message in the app cache dir.
     */
    private suspend fun mediaThumbFile(chatId: Long, messageId: Int): java.io.File? {
        if (chatId == 0L || messageId <= 0) return null
        val dir = java.io.File(context.cacheDir, "notif-thumbs").apply { mkdirs() }
        val file = java.io.File(dir, "thumb_${chatId}_$messageId.jpg")
        if (!file.isFile) {
            val downloaded =
                client.downloadMessageThumb(PeerId(chatId), messageId, file.absolutePath)
            if (downloaded is Outcome.Err) {
                // The avatar repaint downloads at the same time and the session can reject one of the
                // two ("download media failed ... unclassified"); one short retry recovers it.
                delay(750)
                if (client.downloadMessageThumb(
                        PeerId(chatId),
                        messageId,
                        file.absolutePath
                    ) is Outcome.Err
                ) {
                    return null
                }
            }
        }
        if (!file.isFile || file.length() <= 0L || file.length() > 2L * 1024 * 1024) return null
        return file
    }

    private fun scheduleRepeat() {
        repeatJob?.cancel()
        val minutes = store.repeatMinutes
        if (minutes <= 0) return
        repeatJob = scope.launch {
            while (true) {
                delay(minutes * 60_000L)
                if (appForeground) continue
                val chatId = store.lastShownChatId
                if (chatId == 0L) continue
                val payload = lastPayload?.takeIf { it.chatId == chatId } ?: continue
                val mutedFolders = folders.filter { it.id in store.mutedFolders() }
                    .flatMap { folderMemberIds(it, knownChats) }.toSet()
                val decision = decideNotification(
                    payload.copy(mention = false), policy(mutedFolders),
                    (System.currentTimeMillis() / 1000L).toInt(), false
                )
                if (!decision.show || !decision.sound) continue
                // Re-alerts the collapsed chat batch; stops when it left the shade.
                if (!NotificationPresenter.realert(context, chatId)) return@launch
            }
        }
    }

    private fun wakeFetch() {
        if (!shouldSyncOnWake(appForeground)) return
        val generation = wakeGate.tryStart(System.currentTimeMillis()) ?: return
        wakeJob?.cancel()
        wakeJob = scope.launch { runWake(generation) }
    }

    private suspend fun wakeFetchAndJoin() {
        if (!shouldSyncOnWake(appForeground)) return
        val generation = wakeGate.tryStart(System.currentTimeMillis()) ?: return
        wakeJob?.cancel()
        runWake(generation)
    }

    private suspend fun runWake(generation: Int) {
        if (!wakeGate.isCurrent(generation)) return
        when (val connected = client.connect()) {
            is Outcome.Err -> AppLog.warn("push", "wake connect failed")
            is Outcome.Ok -> {
                if (!wakeGate.isCurrent(generation)) return
                if (!shouldRefreshDialogsOnWake(chatPhotos.size)) return
                when (val result = client.getChats()) {
                    is Outcome.Ok -> rememberChatPhotos(result.value)
                    is Outcome.Err -> Unit
                }
            }
        }
    }

    private fun policy(mutedFolderChats: Set<Long>): org.monogram.core.common.push.NotificationPolicyState {
        val defaults = store.notifyDefaults ?: NotifyDefaults(users, chats, broadcasts)
        val rows = store.notifyExceptions ?: exceptions
        return store.policy(
            defaults.users, defaults.chats, defaults.broadcasts,
            rows.filter { it.topicId == null && it.chatId.value != 0L }
                .associate { it.chatId.value to it.settings },
            mutedFolderChats
        ).copy(
            topicExceptions = rows.mapNotNull { row -> row.topicId?.let { (row.chatId.value to it) to row.settings } }
                .toMap(),
            peerKinds = knownChats.associate { it.id.value to notificationPeerKind(it) },
        )
    }

    private fun dismissVisibleChat(chatId: Long) {
        val topicId = openTopicId
        if (topicId != null) {
            NotificationPresenter.dropMessages(
                context, chatId, emptySet(),
                Int.MAX_VALUE, store.badgeSettings(), topicId,
            )
            return
        }
        if (knownChats.any { it.id.value == chatId && it.isForum }) return
        if (!NotificationPresenter.suppressVisibleBubble(context, chatId, store.bubblesEnabled)) {
            dismissChat(chatId)
        }
    }

    suspend fun refreshNotifySettings() = notifySettingsMutex.withLock {
        val userResult = client.getNotifySettings("users")
        val chatResult = client.getNotifySettings("chats")
        val broadcastResult = client.getNotifySettings("broadcasts")
        users = (userResult as? Outcome.Ok)?.value ?: users
        chats = (chatResult as? Outcome.Ok)?.value ?: chats
        broadcasts = (broadcastResult as? Outcome.Ok)?.value ?: broadcasts
        // Shared with the chat list so both agree offline; see NotificationLocalStore.
        if (userResult is Outcome.Ok && chatResult is Outcome.Ok && broadcastResult is Outcome.Ok) {
            store.notifyDefaults =
                NotifyDefaults(users = users, chats = chats, broadcasts = broadcasts)
        }
        val exceptionResult = client.getNotifyExceptions()
        if (exceptionResult is Outcome.Ok) {
            exceptions = exceptionResult.value
            store.notifyExceptions = exceptions
        }
        val folderResult = client.getFolders()
        folders = (folderResult as? Outcome.Ok)?.value ?: folders
        val dialogResult = client.getChats()
        when (val result = dialogResult) {
            is Outcome.Ok -> rememberChatPhotos(result.value)
            is Outcome.Err -> Unit
        }
        notifySettingsLoaded = userResult is Outcome.Ok && chatResult is Outcome.Ok &&
                broadcastResult is Outcome.Ok && exceptionResult is Outcome.Ok &&
                folderResult is Outcome.Ok && dialogResult is Outcome.Ok
        AppLog.api(
            "notify",
            "settings loaded folders=${folders.size} exceptions=${exceptions.size} mutedFolders=${store.mutedFolders().size}",
        )
        NotificationChannels.ensure(context, folders, pruneFolders = true)
    }
}
