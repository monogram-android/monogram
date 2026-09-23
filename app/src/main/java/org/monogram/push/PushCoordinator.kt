package org.monogram.push

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.RemoteInput
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import org.monogram.core.common.AppLog
import org.monogram.core.common.Outcome
import org.monogram.core.common.push.NotificationLocalStore
import org.monogram.core.common.push.PushAction
import org.monogram.core.common.push.PushPayload
import org.monogram.core.common.push.PushRegistration
import org.monogram.core.common.push.PushWakeGate
import org.monogram.core.common.push.shouldRefreshDialogsOnWake
import org.monogram.core.common.push.shouldSyncOnWake
import org.monogram.core.common.push.decideNotification
import org.monogram.core.common.push.folderMemberIds
import org.monogram.core.common.push.parsePushPayload
import org.monogram.core.common.push.webPushRegistration
import org.monogram.core.models.Chat
import org.monogram.core.models.Folder
import org.monogram.core.models.NotifyDefaults
import org.monogram.core.models.NotifyException
import org.monogram.core.models.NotifySettings
import org.monogram.core.models.PeerId
import org.monogram.core.models.peerAvatarCacheKey
import org.monogram.core.models.PushDebugState
import org.monogram.core.models.PushTokenType
import org.monogram.network.bridge.MtprotoClient
import org.monogram.network.http.MediaRepository
import org.unifiedpush.android.connector.UnifiedPush

private val VISUAL_LOC_KEYS = listOf("PHOTO", "VIDEO", "GIF", "STICKER", "ROUND")
private val VISUAL_MEDIA_KINDS = setOf("photo", "video", "gif", "sticker", "sticker_animated", "document")

class PushCoordinator(
    private val context: Context,
    private val client: MtprotoClient,
    private val store: NotificationLocalStore,
    private val mediaRepository: MediaRepository,
    @Suppress("unused") private val storeFactory: StoreFactory,
) : PushRegistration {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile var openChatId: Long? = null
    @Volatile var appForeground: Boolean = false
    @Volatile var folders: List<Folder> = emptyList()
    @Volatile var exceptions: List<NotifyException> = emptyList()
    @Volatile var users: NotifySettings = NotifySettings()
    @Volatile var chats: NotifySettings = NotifySettings()
    @Volatile var broadcasts: NotifySettings = NotifySettings()
    @Volatile var chatPhotos: Map<Long, String> = emptyMap()
    private val mediaLookups: MutableSet<Long> = java.util.Collections.synchronizedSet(HashSet())
    private val wakeGate = PushWakeGate()
    private var wakeJob: Job? = null
    @Volatile private var notifySettingsLoaded = false
    private var notifySettingsJob: Job? = null
    private var repeatJob: Job? = null

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
    }

    fun requestPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT < 33) return
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2301)
        }
    }

    override fun debugState(): PushDebugState =
        store.debugState(gmsAvailable(), distributor(), permissionGranted())

    override fun gmsAvailable(): Boolean =
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS

    override fun distributor(): String = runCatching {
        UnifiedPush.getAckDistributor(context).orEmpty()
    }.getOrDefault("")

    fun permissionGranted(): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun setForeground(value: Boolean) {
        appForeground = value
        if (value) {
            openChatId?.let { dismissChat(it) }
            // The app is open and the session is connected: load server mute/preview/folder state.
            ensureNotifySettings()
        }
    }

    /**
     * Loads notify settings, exceptions and folders once per process. The policy otherwise falls back
     * to defaults, which would notify muted chats and leave every chat on its category channel with
     * no folder channels at all.
     */
    fun ensureNotifySettings() {
        if (notifySettingsLoaded || notifySettingsJob?.isActive == true) return
        notifySettingsJob = scope.launch { refreshNotifySettings() }
    }

    override fun onVisibleChat(chatId: Long?) {
        openChatId = chatId
        if (chatId != null) dismissChat(chatId)
    }

    override fun onChatRead(chatId: Long) {
        dismissChat(chatId)
    }

    override fun onLogout() {
        NotificationPresenter.clear(context)
        store.lastShownChatId = 0L
    }

    private fun dismissChat(chatId: Long) {
        NotificationPresenter.cancel(context, chatId, store.badgeSettings())
        NotificationPresenter.removeShortcut(context, chatId)
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
        if (gmsAvailable()) {
            registerFcm()
        } else {
            registerUnifiedPush()
        }
    }

    private suspend fun registerFcm() {
        val token = runCatching {
            suspendCancellableCoroutine { cont ->
                FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                    if (!cont.isActive) return@addOnCompleteListener
                    cont.resume(if (task.isSuccessful) task.result else null)
                }
            }
        }.getOrNull() ?: return
        registerToken(PushTokenType.Fcm, token)
    }

    private fun registerUnifiedPush() {
        runCatching {
            UnifiedPush.tryUseCurrentOrDefaultDistributor(context) { success ->
                if (success) {
                    UnifiedPush.register(context, messageForDistributor = "Monogram")
                } else {
                    store.setLastRegister("no UnifiedPush distributor")
                    AppLog.warn("push", "no UnifiedPush distributor")
                }
            }
        }.onFailure {
            store.setLastRegister("unifiedpush unavailable")
            AppLog.warn("push", "unifiedpush unavailable")
        }
    }

    fun onWebPushEndpoint(endpoint: String, p256dh: String?, auth: String?) {
        scope.launch {
            val (type, token) = webPushRegistration(endpoint, p256dh, auth)
            registerToken(type, token)
        }
    }

    fun onFcmToken(token: String) {
        scope.launch { registerToken(PushTokenType.Fcm, token) }
    }

    private suspend fun registerToken(type: PushTokenType, token: String) {
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
        AppLog.api("registerDevice", "type=${type.code} result=${store.debugState(gmsAvailable(), distributor(), permissionGranted()).lastRegister}")
    }

    override fun simulate(locKey: String) {
        val json = """{"loc_key":"$locKey","loc_args":["Debug","Test notification"],"custom":{"from_id":1,"msg_id":1}}"""
        handlePayloadJson(json, wake = false)
    }

    fun handleFcm(payload: String?) {
        if (payload.isNullOrBlank()) {
            wakeFetch()
            return
        }
        val decrypted = client.decryptPushPayload(store.secret(), payload)
        val json = when (decrypted) {
            is Outcome.Ok -> decrypted.value
            is Outcome.Err -> {
                AppLog.warn("push", "decrypt failed")
                wakeFetch()
                return
            }
        }
        handlePayloadJson(json, wake = true)
    }

    fun handleWake() {
        ensureNotifySettings()
        wakeFetch()
    }

    private fun handlePayloadJson(json: String, wake: Boolean) {
        val payload = parsePushPayload(json)
        AppLog.api("notify", "parsed loc=${payload.locKey} action=${payload.action}")
        store.recordPayload(payload)
        when (payload.action) {
            PushAction.SessionRevoke -> scope.launch {
                onLogout()
                client.logout()
            }
            PushAction.Delete -> {
                payload.chatId?.let { dismissChat(it) }
                if (wake) wakeFetch()
            }
            PushAction.ReadHistory, PushAction.ReadReaction -> {
                payload.chatId?.let { dismissChat(it) }
                if (wake) wakeFetch()
            }
            PushAction.Wake, PushAction.Ignore -> if (wake) wakeFetch()
            PushAction.Show -> {
                AppLog.api("notify", "loc=${payload.locKey} fg=$appForeground")
                ensureNotifySettings()
                val now = (System.currentTimeMillis() / 1000L).toInt()
                val mutedFolderChats = folders
                    .filter { it.id in store.mutedFolders() }
                    .flatMap { folderMemberIds(it.chatIds, it.excludeChatIds) }
                    .toSet()
                val decision = decideNotification(
                    payload,
                    store.policy(users, chats, broadcasts, exceptions.associate { it.chatId.value to it.settings }, mutedFolderChats),
                    now,
                    appForeground,
                    openChatId,
                )
                val folderId = folders.firstOrNull { folder ->
                    payload.chatId != null && payload.chatId in folderMemberIds(folder.chatIds, folder.excludeChatIds)
                }?.id
                NotificationChannels.ensure(context, folders)
                if (!decision.show) {
                    AppLog.api("notify", "suppressed loc=${payload.locKey}")
                }
                present(payload, decision, folderId)
                if (decision.show) {
                    payload.chatId?.let { store.lastShownChatId = it }
                    scheduleRepeat()
                }
                if (wake) wakeFetch()
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
                        when (client.sendText(PeerId(chatId), text, replyToMsgId = messageId)) {
                            is Outcome.Ok -> dismissChat(chatId)
                            is Outcome.Err -> AppLog.warn("notify", "reply failed")
                        }
                    }
                    NotificationPresenter.ACTION_MARK_READ -> {
                        dismissChat(chatId)
                        val maxId = if (messageId > 0) messageId else Int.MAX_VALUE
                        when (client.readHistory(PeerId(chatId), maxId)) {
                            is Outcome.Ok -> Unit
                            is Outcome.Err -> AppLog.warn("notify", "read failed")
                        }
                    }
                }
            } finally {
                done()
            }
        }
    }

    private fun rememberChatPhotos(chats: List<Chat>) {
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
                if (!payload.isVisualMedia() && !rememberMediaLookup(chatId, messageId)) return@launch
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
                NotificationPresenter.show(context, payload, decision, folderId, file, mode, badge, quiet = true)
                return@launch
            }
            val key = chatPhotos[chatId] ?: peerAvatarCacheKey(PeerId(chatId))
            val file = when (val result = mediaRepository.ensureLocalAvatar(PeerId(chatId), key)) {
                is Outcome.Ok -> result.value
                is Outcome.Err -> null
            } ?: return@launch
            NotificationPresenter.show(context, payload, decision, folderId, file, mode, badge, quiet = true)
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
        when (val result = client.getHistoryPage(PeerId(chatId), limit = 1, offsetId = messageId + 1)) {
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
            val downloaded = client.downloadMessageThumb(PeerId(chatId), messageId, file.absolutePath)
            if (downloaded is Outcome.Err) {
                // The avatar repaint downloads at the same time and the session can reject one of the
                // two ("download media failed ... unclassified"); one short retry recovers it.
                delay(750)
                if (client.downloadMessageThumb(PeerId(chatId), messageId, file.absolutePath) is Outcome.Err) {
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
                // Re-alerts the collapsed chat batch; stops when it left the shade.
                if (!NotificationPresenter.realert(context, chatId)) return@launch
            }
        }
    }

    private fun wakeFetch() {
        if (!shouldSyncOnWake(appForeground)) return
        val generation = wakeGate.tryStart(System.currentTimeMillis()) ?: return
        wakeJob?.cancel()
        wakeJob = scope.launch {
            if (!wakeGate.isCurrent(generation)) return@launch
            when (val connected = client.connect()) {
                is Outcome.Err -> AppLog.warn("push", "wake connect failed")
                is Outcome.Ok -> {
                    if (!wakeGate.isCurrent(generation)) return@launch
                    if (!shouldRefreshDialogsOnWake(chatPhotos.size)) return@launch
                    when (val result = client.getChats()) {
                        is Outcome.Ok -> rememberChatPhotos(result.value)
                        is Outcome.Err -> Unit
                    }
                }
            }
        }
    }

    suspend fun refreshNotifySettings() {
        users = (client.getNotifySettings("users") as? Outcome.Ok)?.value ?: users
        chats = (client.getNotifySettings("chats") as? Outcome.Ok)?.value ?: chats
        broadcasts = (client.getNotifySettings("broadcasts") as? Outcome.Ok)?.value ?: broadcasts
        // Shared with the chat list so both agree offline; see NotificationLocalStore.
        store.notifyDefaults = NotifyDefaults(users = users, chats = chats, broadcasts = broadcasts)
        exceptions = (client.getNotifyExceptions() as? Outcome.Ok)?.value ?: exceptions
        store.notifyExceptions = exceptions
        folders = (client.getFolders() as? Outcome.Ok)?.value ?: folders
        when (val result = client.getChats()) {
            is Outcome.Ok -> rememberChatPhotos(result.value)
            is Outcome.Err -> Unit
        }
        notifySettingsLoaded = true
        AppLog.api(
            "notify",
            "settings loaded folders=${folders.size} exceptions=${exceptions.size} mutedFolders=${store.mutedFolders().size}",
        )
        NotificationChannels.ensure(context, folders, pruneFolders = true)
    }
}
