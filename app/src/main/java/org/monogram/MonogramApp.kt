package org.monogram

import android.Manifest.permission
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.material3.ComposeMaterial3Flags
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.core.content.ContextCompat
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.monogram.core.common.AppLog
import org.monogram.core.common.DebugLog
import org.monogram.core.common.DebugStats
import org.monogram.core.common.MediaCacheLimitStore
import org.monogram.core.common.Outcome
import org.monogram.core.common.PerfLog
import org.monogram.core.common.RuntimePressure
import org.monogram.core.common.SponsorRegistry
import org.monogram.core.common.TelegramCredentials
import org.monogram.core.common.push.NotificationLocalStore
import org.monogram.core.database.DatabaseProvider
import org.monogram.core.database.MonogramDatabase
import org.monogram.core.database.OfflineWarmup
import org.monogram.core.database.OutgoingMediaFiles
import org.monogram.core.database.SessionMetadataStore
import org.monogram.core.models.AuthSession
import org.monogram.core.models.PeerId
import org.monogram.core.ui.AppUpdateSettings
import org.monogram.core.ui.AppearanceSettings
import org.monogram.core.ui.DownloadSettings
import org.monogram.core.ui.DownloadState
import org.monogram.core.ui.FileLeases
import org.monogram.core.ui.ImageCache
import org.monogram.core.ui.currentLocation
import org.monogram.core.ui.perf.perfSpan
import org.monogram.feature.chats.AccountMenuItem
import org.monogram.feature.settings.ProxySettingsStore
import org.monogram.feature.settings.SettingsAccountDetails
import org.monogram.mtproto.MtprotoNativeLoader
import org.monogram.network.bridge.BridgedMtprotoClient
import org.monogram.network.bridge.ClientInitInfo
import org.monogram.network.bridge.MtprotoTransportMode
import org.monogram.network.bridge.MtprotoUpdate
import org.monogram.network.bridge.ProxyConfig
import org.monogram.network.bridge.ProxyType
import org.monogram.network.http.MediaFetchKind
import org.monogram.network.http.MediaPriority
import org.monogram.network.http.MediaRepository
import org.monogram.network.http.TelegramChunkFetcher
import org.monogram.network.http.TelegramInlineThumbPeek
import org.monogram.network.http.TelegramMediaFetcher
import org.monogram.network.http.TelegramPeerInlineThumbPeek
import org.monogram.push.AccountPushRouter
import org.monogram.push.NotificationPresenter
import org.monogram.push.PushCoordinator
import org.monogram.push.SharedPushSecret
import org.monogram.root.AccountDurableUpdateSink
import org.monogram.root.CloudDraftSync
import org.monogram.root.LiveLocationRuntime
import org.monogram.root.OutgoingTextSync
import org.monogram.root.editRetainedLiveLocation
import org.monogram.root.sendRetainedMessage
import org.monogram.sponsor.SponsorSyncManager
import org.monogram.update.AndroidAppUpdateInstaller
import org.monogram.update.AppUpdateManager
import java.io.File
import java.util.Locale
import java.util.UUID

class MonogramApp : Application() {
    private lateinit var credentials: TelegramCredentials
    lateinit var accountRegistry: AccountRegistry
        private set
    lateinit var accountRuntimeManager: AccountRuntimeManager
        private set
    lateinit var client: BridgedMtprotoClient
        private set
    lateinit var mediaRepository: MediaRepository
        private set
    lateinit var notifications: NotificationLocalStore
        private set
    lateinit var push: PushCoordinator
    lateinit var pushRouter: AccountPushRouter
        private set

    lateinit var sponsorSync: SponsorSyncManager
        private set
    lateinit var appUpdate: AppUpdateManager
        private set
    lateinit var sessionStore: SessionMetadataStore
        private set
    lateinit var warmup: OfflineWarmup
        private set

    private val settingsScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val accountOperations = Mutex()
    private val accountWorkManager: AccountWorkManager<AccountRuntime> by lazy {
        AccountWorkManager(
            scope = settingsScope,
            accountId = { it.account.userId },
            prepare = {
                it.markAuthorized()
                it.warmup.activateOutgoingAccount()
            },
        ) { runtime ->
            val draft = CloudDraftSync(
                runtime.warmup,
                runtime.client::getCloudDrafts,
                runtime.client::saveCloudDraft,
            )
            val outgoing = OutgoingTextSync(
                runtime.warmup,
                send = { row -> sendRetainedMessage(runtime.client, row) },
                sendMedia = { row ->
                    org.monogram.root.sendRetainedMedia(
                        runtime.warmup, runtime.client, row
                    )
                },
            )
            object : AccountBackgroundWork {
                @Volatile
                private var running = false
                private var updateJob: Job? = null
                private var sessionLostJob: Job? = null

                override fun isRunning(): Boolean = running

                override fun start(scope: CoroutineScope) {
                    running = true
                    runtime.client.installDurableUpdateSink(
                        AccountDurableUpdateSink(runtime.warmup)::commit,
                    )
                    draft.start(scope)
                    outgoing.start(scope)
                    updateJob = scope.launch {
                        runtime.client.updates().collect { update ->
                            when (update) {
                                is MtprotoUpdate.ChatsChanged -> runtime.warmup.upsertChats(update.chats)
                                is MtprotoUpdate.SyncState -> if (!update.isSyncing) resume()
                                else -> Unit
                            }
                        }
                    }
                    sessionLostJob = scope.launch {
                        runtime.client.sessionLost().first()
                        runtime.markSignInRequired()
                        accountStatusEvents.tryEmit(runtime.account.userId)
                        running = false
                        updateJob?.cancel()
                        outgoing.stop()
                        draft.stop()
                        runtime.warmup.deactivateOutgoingAccount()
                    }
                }

                override fun resume() {
                    if (!running) return
                    draft.resume()
                    outgoing.resume()
                }

                override suspend fun stop() {
                    running = false
                    updateJob?.cancelAndJoin()
                    updateJob = null
                    sessionLostJob?.cancelAndJoin()
                    sessionLostJob = null
                    outgoing.stop()
                    draft.stop()
                    runtime.warmup.deactivateOutgoingAccount()
                }
            }
        }
    }
    private val sharedPushSecret by lazy { SharedPushSecret(this) }
    private val accountStatusEvents = MutableSharedFlow<Long>(extraBufferCapacity = 8)
    val accountStatusChanges = accountStatusEvents.asSharedFlow()
    private val pushRouterCoordinatorIds = mutableSetOf<Long>()
    private val accountPushes = mutableMapOf<Long, PushCoordinator>()
    private val accountMediaRepositories =
        java.util.concurrent.ConcurrentHashMap<Long, MediaRepository>()

    @Volatile
    private var bootstrapMediaRepository: MediaRepository? = null

    private val ready = CompletableDeferred<Unit>()
    private val pushReady = CompletableDeferred<Unit>()
    private val accountLiveLocations = mutableMapOf<BridgedMtprotoClient, LiveLocationRuntime>()
    internal val liveLocations: LiveLocationRuntime
        get() {
            val client = client
            val warmup = warmup
            return accountLiveLocations.getOrPut(client) {
                LiveLocationRuntime(activityScope, warmup, sample = {
                    currentLocation(
                        this, allowCached = false
                    )?.let { org.monogram.core.models.GeoPlace(it.first, it.second) }
                }, permitted = {
                    ContextCompat.checkSelfPermission(
                        this, permission.ACCESS_COARSE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
                        this, permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                }, edit = { id, point ->
                    val epoch = warmup.outgoingAccountEpoch()
                    editRetainedLiveLocation(
                        client, id, point
                    ) { warmup.outgoingAccountIsCurrent(epoch) }
                })
            }
        }
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val startedActivities = mutableSetOf<MainActivity>()
    private var activityIdleJob: Job? = null

    internal fun activityStarted(activity: MainActivity) {
        startedActivities.add(activity)
        updateActivityForeground()
    }

    internal fun activityStopped(activity: MainActivity) {
        startedActivities.remove(activity)
        updateActivityForeground()
    }

    private fun updateActivityForeground() {
        activityIdleJob?.cancel()
        activityIdleJob = activityScope.launch {
            awaitReady()
            val foreground = startedActivities.isNotEmpty()
            val activeUserId = accountRegistry.activeUserId
            if (activeUserId == null) push.setForeground(foreground)
            pushRouter.setForeground(foreground, activeUserId)
        }
    }

    private suspend fun startAccountPushes(readyUserIds: Set<Long>? = null) {
        accountRuntimeManager.all().forEach { runtime ->
            val userId = runtime.account.userId
            if (readyUserIds != null && userId !in readyUserIds) return@forEach
            if (pushRouterCoordinatorIds.contains(userId)) return@forEach
            var createdCoordinator: PushCoordinator? = null
            try {
                if (runtime.sessionStore.readMeta(SessionMetadataStore.KEY_AUTHORIZED) == "0") return@forEach
                val accountNotifications = NotificationLocalStore(
                    this, userId, migrateLegacy = runtime.account.databaseName == "monogram.db"
                )
                val accountMedia = accountMedia(runtime)
                val coordinator = PushCoordinator(
                    this,
                    runtime.client,
                    accountNotifications,
                    accountMedia,
                    DefaultStoreFactory(),
                    runtime.sessionStore,
                    otherAccountUserIds = {
                        accountRegistry.all().map { it.userId }
                    },
                    sharedPushSecret = sharedPushSecret::get,
                    notificationCache = runtime.warmup,
                )
                createdCoordinator = coordinator
                coordinator.setAccountUserId(userId)
                coordinator.start()
                pushRouter.register(userId, coordinator)
                accountPushes[userId] = coordinator
                pushRouterCoordinatorIds += userId
            } catch (error: Exception) {
                try {
                    createdCoordinator?.stop()
                    pushRouter.unregister(userId)
                    accountPushes.remove(userId)
                    pushRouterCoordinatorIds.remove(userId)
                } catch (cleanupError: Exception) {
                    error.addSuppressed(cleanupError)
                }
                if (error is CancellationException) throw error
                AppLog.warn("push", "account push setup unavailable")
            }
        }
        accountPushes.values.toList().forEach(PushCoordinator::requestReregister)
    }

    data class PendingAccountAuthorization(
        val client: BridgedMtprotoClient,
        val sessionPath: File,
    )

    fun beginAdditionalAuthorization(): PendingAccountAuthorization {
        val path = File(filesDir, "accounts/.pending/${UUID.randomUUID()}/mtproto.session.json")
        return PendingAccountAuthorization(
            BridgedMtprotoClient(
                credentials = credentials,
                sessionPath = path.absolutePath,
                initConnection = monogramInitConnection(),
            ),
            path,
        )
    }

    suspend fun adoptAdditionalAuthorized(
        session: AuthSession,
        pending: PendingAccountAuthorization,
    ) = accountOperations.withLock {
        val accountId = session.userId.value
        check(accountRegistry.get(accountId) == null) { "Account is already registered" }
        val sessionPath = File(filesDir, "accounts/$accountId/mtproto.session.json")
        requireVacantAccountStorage(
            filesDir,
            noBackupFilesDir,
            getDatabasePath("monogram-account-$accountId.db"),
            accountId,
        )
        val previousAccountId = accountRuntimeManager.activeUserId() ?: accountRegistry.activeUserId
        var accountClient: BridgedMtprotoClient? = null
        var runtimeAdopted = false
        try {
            sessionPath.parentFile?.mkdirs()
            pending.client.close()
            MtprotoNativeLoader.promoteSession(pending.sessionPath, sessionPath)
            val account = accountRegistry.ensure(session, activate = false)
            check(account.sessionPath == sessionPath) { "Promoted session path mismatch" }
            val database = DatabaseProvider.get(this, account.userId)
            val accountSessionStore = SessionMetadataStore(database)
            accountSessionStore.saveAuthorized(session)
            val accountWarmup = OfflineWarmup(
                database,
                outgoingMediaFiles = OutgoingMediaFiles(
                    File(noBackupFilesDir, "outgoing-media/${account.userId}"),
                ),
            )
            val client = BridgedMtprotoClient(
                credentials = credentials,
                sessionPath = account.sessionPath.absolutePath,
                initConnection = monogramInitConnection(),
            )
            accountClient = client
            accountRuntimeManager.adopt(
                account,
                client,
                database,
                accountSessionStore,
                accountWarmup,
            )
            runtimeAdopted = true
            accountWorkManager.start(accountRuntimeManager.switchTo(account.userId))
            client.connect()
            applyDownloadSettings(client, DownloadSettings.state.value)
            startAccountPushes()
            bindActiveRuntime(accountRuntimeManager.switchTo(account.userId))
            pending.sessionPath.parentFile?.deleteRecursively()
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                try {
                    if (!runtimeAdopted) accountClient?.close()
                } catch (cleanupError: Throwable) {
                    error.addSuppressed(cleanupError)
                }
                try {
                    if (previousAccountId != null && accountRegistry.get(previousAccountId) != null) {
                        bindActiveRuntime(accountRuntimeManager.switchTo(previousAccountId))
                    }
                } catch (restoreError: Throwable) {
                    error.addSuppressed(restoreError)
                }
            }
            throw error
        }
    }

    suspend fun adoptAuthorizedAccount(session: AuthSession) = accountOperations.withLock {
        check(
            accountRegistry.all().isEmpty() || accountRegistry.activeUserId == session.userId.value
        ) {
            "Authorization requires an account-specific runtime"
        }
        check(push.usesClient(client)) { "Account push coordinator unavailable" }
        val account = accountRegistry.adoptAuthorized(session)
        val database = if (account.databaseName == "monogram.db") {
            DatabaseProvider.get(this)
        } else {
            DatabaseProvider.get(this, account.userId)
        }
        accountRuntimeManager.adopt(account, client, database, sessionStore, warmup)
        val existingMedia = accountMediaRepositories.putIfAbsent(account.userId, mediaRepository)
        check(existingMedia == null || existingMedia === mediaRepository) { "Account media repository mismatch" }
        bootstrapMediaRepository = null
        accountWorkManager.start(accountRuntimeManager.switchTo(account.userId))
        push.setAccountUserId(account.userId)
        pushRouter.register(account.userId, push)
        accountPushes[account.userId] = push
        pushRouterCoordinatorIds += account.userId
        pushRouter.setForeground(startedActivities.isNotEmpty(), account.userId)
    }

    internal data class RootAccountBindings(
        val account: AccountRecord?,
        val accountUserIds: List<Long>,
        val client: BridgedMtprotoClient,
        val sessionStore: SessionMetadataStore,
        val warmup: OfflineWarmup,
        val mediaRepository: MediaRepository,
        val notifications: NotificationLocalStore,
        val push: PushCoordinator?,
        val liveLocations: LiveLocationRuntime,
    )

    internal suspend fun rootAccountBindings(): RootAccountBindings = accountOperations.withLock {
        val accounts = accountRegistry.all()
        val activeId = accountRegistry.activeUserId
        val account = activeId?.let { id -> accounts.firstOrNull { it.userId == id } }
        check(activeId == null || account != null) { "Active account unavailable" }
        if (account != null) {
            val runtime = accountRuntimeManager.active()
            check(
                runtime != null && runtime.isValid() && runtime.account.userId == account.userId && runtime.client === client && runtime.sessionStore === sessionStore && runtime.warmup === warmup && accountMediaRepositories[account.userId] === mediaRepository
            ) { "Active account runtime mismatch" }
        }
        val coordinator =
            if (activeId == null) push else pushRouter.coordinator(activeId) as? PushCoordinator
        RootAccountBindings(
            account,
            accounts.map { it.userId },
            client,
            sessionStore,
            warmup,
            mediaRepository,
            notifications,
            coordinator?.takeIf { it.usesClient(client) },
            liveLocations,
        )
    }

    suspend fun switchAccount(userId: Long) = accountOperations.withLock {
        val runtime = accountRuntimeManager.switchTo(userId)
        bindActiveRuntime(runtime)
        settingsScope.launch {
            if (!runtime.isValid() || runtime.status == AccountRuntimeStatus.SignInRequired || runtime.sessionStore.readMeta(
                    SessionMetadataStore.KEY_AUTHORIZED
                ) == "0"
            ) return@launch
            runtime.client.connect()
            applyDownloadSettings(runtime.client, DownloadSettings.state.value)
        }
    }

    suspend fun removeAccount(userId: Long) = accountOperations.withLock {
        val account = accountRegistry.get(userId) ?: return@withLock
        require(canRemoveAccount(accountRegistry.all().size)) { "Cannot remove the last account" }
        accountRuntimeManager.requireAvailable(userId)
        pushRouter.unregister(userId)
        NotificationPresenter.clear(this, userId)
        NotificationLocalStore.clearAccount(this, userId)
        accountPushes.remove(userId)
        pushRouterCoordinatorIds.remove(userId)
        accountWorkManager.stop(userId)
        accountMediaRepositories.remove(userId)?.shutdown()
        ImageCache.release(account.mediaPath)
        accountRuntimeManager.all().firstOrNull { it.account.userId == userId }?.let { runtime ->
            accountLiveLocations.remove(runtime.client)?.stopAuthorized()
        }
        accountRuntimeManager.close(userId, removeStorage = true)
        val nextId = accountRuntimeManager.activeUserId()
        val nextRuntime = nextId?.let { accountRuntimeManager.switchTo(it) }
        nextRuntime?.let(::bindActiveRuntime)
        if (nextRuntime != null) {
            try {
                if (nextRuntime.isValid() && nextRuntime.status != AccountRuntimeStatus.SignInRequired && nextRuntime.sessionStore.readMeta(
                        SessionMetadataStore.KEY_AUTHORIZED
                    ) != "0"
                ) {
                    if (nextRuntime.client.connect() is Outcome.Err) {
                        AppLog.warn("account", "remaining account connection failed")
                    }
                    applyDownloadSettings(nextRuntime.client, DownloadSettings.state.value)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                AppLog.warn("account", "remaining account connection failed unexpectedly")
            }
        }
        startAccountPushes()
    }

    private fun bindActiveRuntime(runtime: AccountRuntime) {
        client = runtime.client
        mediaRepository = accountMedia(runtime)
        notifications = NotificationLocalStore(
            this,
            runtime.account.userId,
            migrateLegacy = runtime.account.databaseName == "monogram.db",
        )
        sessionStore = runtime.sessionStore
        warmup = runtime.warmup
        push = accountPushes[runtime.account.userId] ?: push
        val foreground = startedActivities.isNotEmpty()
        pushRouter.setForeground(foreground, runtime.account.userId)
    }

    suspend fun accountMenuItems(): List<AccountMenuItem> = accountRegistry.all().map { account ->
        try {
            val runtime = accountRuntimeManager.open(account)
            val authorized =
                runtime.status != AccountRuntimeStatus.SignInRequired && runtime.sessionStore.isAuthorized()
            val title =
                runtime.sessionStore.readProfile(account.userId)?.title?.takeIf { it.isNotBlank() }
                    ?: getString(
                        org.monogram.feature.chats.R.string.chats_account_fallback, account.userId
                    )
            AccountMenuItem(
                userId = account.userId,
                title = title,
                needsSignIn = !authorized,
                active = account.userId == accountRegistry.activeUserId,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            AppLog.warn("account", "account information unavailable")
            AccountMenuItem(
                userId = account.userId,
                title = getString(
                    org.monogram.feature.chats.R.string.chats_account_fallback, account.userId
                ),
                active = account.userId == accountRegistry.activeUserId,
                unavailable = true,
            )
        }
    }

    suspend fun loadSettingsAccountDetails(
        userId: Long,
        publish: suspend (SettingsAccountDetails) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val (runtime, repository, cached) = accountOperations.withLock {
            val account = accountRegistry.get(userId) ?: return@withContext
            val runtime = accountRuntimeManager.open(account)
            Triple(runtime, accountMedia(runtime), runtime.sessionStore.readProfile(userId))
        }
        publish(
            SettingsAccountDetails(
                title = cached?.title?.takeIf { it.isNotBlank() },
                avatarCacheKey = cached?.avatarCacheKey,
                mediaRepository = repository,
                username = cached?.username,
                phone = cached?.phone,
            )
        )
        currentCoroutineContext().ensureActive()
        if (!runtime.isValid() || runtime.status == AccountRuntimeStatus.SignInRequired || !runtime.sessionStore.isAuthorized() || runtime.sessionStore.readAuthorizedUserId()?.value != userId) return@withContext

        val fresh =
            (runtime.client.getProfile(PeerId(0)) as? Outcome.Ok)?.value?.takeIf { it.id.value == userId }
                ?: return@withContext
        val updated = accountOperations.withLock {
            if (accountRegistry.get(userId) == null || !runtime.isValid() || accountRuntimeManager.all()
                    .none { it === runtime } || runtime.status == AccountRuntimeStatus.SignInRequired || !runtime.sessionStore.isAuthorized() || runtime.sessionStore.readAuthorizedUserId()?.value != userId
            ) return@withLock null
            currentCoroutineContext().ensureActive()
            runtime.sessionStore.upsertProfile(fresh)
            val stored = runtime.sessionStore.readProfile(userId)
            SettingsAccountDetails(
                title = stored?.title?.takeIf { it.isNotBlank() },
                avatarCacheKey = stored?.avatarCacheKey,
                mediaRepository = repository,
                username = stored?.username,
                phone = stored?.phone,
            )
        }
        currentCoroutineContext().ensureActive()
        if (updated != null) publish(updated)
    }

    suspend fun stopAccountWork(userId: Long) {
        accountWorkManager.stop(userId)
    }

    fun resumeActiveAccountWork() {
        val userId = accountRegistry.activeUserId ?: return
        settingsScope.launch { accountWorkManager.resume(userId) }
    }

    suspend fun awaitReady() = ready.await()

    fun handleFcm(payload: String?, onComplete: (() -> Unit)? = null) {
        pushRouter.handleFcm(payload, onComplete)
    }

    fun awaitReadyBlocking(timeoutMs: Long = 20_000L): Boolean = runBlocking {
        try {
            withTimeout(timeoutMs) { ready.await() }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun awaitPushReadyBlocking(timeoutMs: Long = 20_000L): Boolean = runBlocking {
        try {
            withTimeout(timeoutMs) { pushReady.await() }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun launchWhenReady(block: suspend () -> Unit): Job = settingsScope.launch {
        ready.await()
        block()
    }

    private fun createMediaRepository(client: BridgedMtprotoClient, root: File): MediaRepository {
        val repository = MediaRepository(
            cacheRoot = root,
            telegramChunkFetcher = TelegramChunkFetcher { chatId, messageId, path, offset ->
                client.downloadMessageMediaChunk(chatId, messageId, path, offset)
            },
            telegramFetcher = object : TelegramMediaFetcher {
                override fun promote(destPath: String, priority: Int) =
                    client.promoteDownload(destPath, priority)

                override suspend fun fetchMessageMedia(
                    chatId: PeerId,
                    messageId: Int,
                    destPath: String,
                    kind: MediaFetchKind,
                    priority: Int,
                ): Outcome<String> = when (kind) {
                    MediaFetchKind.Thumb -> client.downloadMessageThumb(
                        chatId, messageId, destPath, priority
                    )

                    MediaFetchKind.Display -> client.downloadMessageDisplay(
                        chatId, messageId, destPath, priority
                    )

                    MediaFetchKind.Full -> client.downloadMessageMedia(
                        chatId, messageId, destPath, priority
                    )
                }
            },
            customEmojiFetcher = { documentId, path, priority ->
                client.downloadCustomEmoji(documentId, path, priority)
            },
            inlineThumbPeek = TelegramInlineThumbPeek { chatId, messageId ->
                client.peekMessageInlineThumb(chatId, messageId)
            },
            peerInlineThumbPeek = TelegramPeerInlineThumbPeek { peerId ->
                client.peekPeerInlineThumb(peerId)
            },
            cacheLimitBytes = MediaCacheLimitStore.current.value.bytes,
        )
        client.setDownloadProgressListener { path, downloaded, _ ->
            repository.onNativeProgress(path, downloaded)
        }
        return repository
    }

    private fun accountMedia(runtime: AccountRuntime): MediaRepository =
        accountMediaRepositories.computeIfAbsent(runtime.account.userId) {
            createMediaRepository(runtime.client, runtime.account.mediaPath)
        }

    private fun leaseAccountMediaFile(file: File): AutoCloseable? =
        acquireAccountMediaLease(file, accountMediaRepositories.values, bootstrapMediaRepository)

    private fun applyDownloadSettings(state: DownloadState) {
        if (::accountRuntimeManager.isInitialized) {
            settingsScope.launch {
                accountRuntimeManager.all().forEach { runtime ->
                    applyDownloadSettings(runtime.client, state)
                }
            }
        } else {
            applyDownloadSettings(client, state)
        }
    }

    private fun applyDownloadSettings(client: BridgedMtprotoClient, state: DownloadState) {
        client.applyDownloadConcurrency(state.effectiveLanes, state.parts)
        client.setFilePartKib(state.filePartKib)
        client.setDownloadChunkKib(state.downloadChunkKib)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate() {
        super.onCreate()
        ComposeMaterial3Flags.isCheckboxStylingFixEnabled = true
        val startedAt = PerfLog.nowMs()
        perfSpan("app:settings") {
            AppLog.init(cacheDir)
        }
        RuntimePressure.networkConstrained = {
            getSystemService(android.net.ConnectivityManager::class.java)?.activeNetwork == null
        }
        RuntimePressure.thermal = {
            if (Build.VERSION.SDK_INT < 29) {
                false
            } else {
                val status =
                    getSystemService(android.os.PowerManager::class.java)?.currentThermalStatus ?: 0
                status >= android.os.PowerManager.THERMAL_STATUS_SEVERE
            }
        }
        RuntimePressure.databaseBusy = {
            DatabaseProvider.roomQueueDepth() > 4
        }
        settingsScope.launch(Dispatchers.IO) {
            perfSpan("app:debugStats") {
                DebugStats.install(BuildConfig.DEBUG, cacheDir)
                DebugLog.install(BuildConfig.DEBUG || BuildConfig.BUILD_TYPE == "beta")
            }
        }
        settingsScope.launch(Dispatchers.IO) {
            try {
                val steps = firstPaintSteps()
                runFirstPaintStartup(settingsScope, steps)
                ready.complete(Unit)
                PerfLog.mark("app:ready", PerfLog.nowMs() - startedAt)
                settingsScope.launch { steps.mediaMigration() }
                startAfterFirstPaint(steps)
                pushReady.complete(Unit)
            } catch (cancelled: CancellationException) {
                ready.completeExceptionally(cancelled)
                pushReady.completeExceptionally(cancelled)
                throw cancelled
            } catch (error: Throwable) {
                AppLog.warn("startup", "init failed")
                ready.completeExceptionally(error)
                pushReady.completeExceptionally(error)
            }
        }
        PerfLog.mark("app:onCreate", PerfLog.nowMs() - startedAt)
    }

    private fun firstPaintSteps(): StartupSteps {
        credentials = TelegramCredentials(
            apiId = BuildConfig.TELEGRAM_API_ID,
            apiHash = BuildConfig.TELEGRAM_API_HASH,
        )
        val durableMedia = File(filesDir, "media")
        val cacheMedia = File(cacheDir, "media")
        fun currentAccount(): AccountRecord? = if (::accountRegistry.isInitialized) {
            accountRegistry.activeUserId?.let(accountRegistry::get)
        } else {
            null
        }

        fun currentMediaRoot(): File = currentAccount()?.mediaPath ?: durableMedia
        lateinit var db: MonogramDatabase
        return StartupSteps(
            openDatabase = {
                accountRegistry = AccountRegistry(filesDir).also { it.load() }
                val account = accountRegistry.activeForStartup()
                db = perfSpan("app:database") {
                    if (account == null || account.databaseName == "monogram.db") {
                        DatabaseProvider.get(this)
                    } else {
                        DatabaseProvider.get(this, account.userId)
                    }
                }
                warmup = OfflineWarmup(
                    db, outgoingMediaFiles = OutgoingMediaFiles(
                        File(noBackupFilesDir, "outgoing-media")
                    )
                )
                sessionStore = SessionMetadataStore(db)
            },
            installAppearance = {
                perfSpan("app:appearance") {
                    AppearanceSettings.install(this)
                    AppUpdateSettings.install(this)
                }
            },
            createClient = {
                MtprotoNativeLoader.initializePlatformVerifier(this)
                val sessionFile =
                    currentAccount()?.sessionPath ?: File(filesDir, "mtproto.session.json")
                if (currentAccount()?.databaseName == null || currentAccount()?.databaseName == "monogram.db") {
                    perfSpan("app:tdlibImport") {
                        runCatching {
                            TdlibSessionImport.maybeImport(filesDir, sessionFile, cacheDir)
                        }.onFailure { AppLog.warn("session", "tdlib import failed") }
                    }
                }
                accountRuntimeManager = AccountRuntimeManager(
                    context = this,
                    registry = accountRegistry,
                    credentials = credentials,
                    initConnection = monogramInitConnection(),
                )
                val runtime = currentAccount()?.let {
                    accountRuntimeManager.switchTo(it.userId)
                }
                client = runtime?.client ?: perfSpan("app:client") {
                    BridgedMtprotoClient(
                        credentials = credentials,
                        sessionPath = sessionFile.absolutePath,
                        initConnection = monogramInitConnection(),
                    )
                }
                if (runtime != null) {
                    warmup = runtime.warmup
                    sessionStore = runtime.sessionStore
                }
                ProxySettingsStore.load(this)?.let { saved ->
                    val applied = runCatching {
                        val transportMode =
                            MtprotoTransportMode.valueOf(saved.transportMode.uppercase())
                        val proxyType = ProxyType.valueOf(saved.kind)
                        if (proxyType == ProxyType.NONE) false else client.setTransportMode(
                            transportMode
                        ) is Outcome.Ok && client.configureProxy(
                            ProxyConfig(
                                proxyType,
                                saved.host,
                                saved.port,
                                saved.username,
                                saved.password,
                                saved.secret
                            )
                        ) is Outcome.Ok
                    }.getOrDefault(false)
                    if (!applied) {
                        runCatching { client.clearProxy() }
                        runCatching { client.setTransportMode(MtprotoTransportMode.PADDED_INTERMEDIATE) }
                        ProxySettingsStore.clearActive(this)
                        ProxySettingsStore.markStartupFailure(this)
                        AppLog.warn("proxy", "stored proxy settings rejected")
                    }
                }
            },
            installImageCache = {
                perfSpan("app:imageCache") { ImageCache.install(this) }
            },
            cleanup = {
                sessionStore.readAuthorizedUserId()?.let { userId ->
                    check(currentAccount()?.userId?.let { it == userId.value } != false) {
                        "Account registry and database identity differ"
                    }
                    val dcId = sessionStore.readMeta(SessionMetadataStore.KEY_DC_ID)?.toIntOrNull()
                    if (dcId != null) {
                        val account = accountRegistry.adoptLegacy(AuthSession(userId, dcId))
                        accountRuntimeManager.adopt(
                            account = account,
                            client = client,
                            database = db,
                            sessionStore = sessionStore,
                            warmup = warmup,
                        )
                    }
                }
                runCatching { warmup.prepareStartupSnapshot(sessionStore) }.onFailure {
                    AppLog.warn(
                        "warmup", "startup cleanup failed"
                    )
                }
            },
            prewarm = {
                if (BuildConfig.STARTUP_PREWARM && sessionStore.readMeta(SessionMetadataStore.KEY_AUTHORIZED) != "0") {
                    val started = PerfLog.nowMs()
                    when (val result = runCatching { client.connect() }.getOrNull()) {
                        is Outcome.Ok -> PerfLog.mark(
                            "app:prewarm", PerfLog.nowMs() - started, "result=ok"
                        )

                        is Outcome.Err -> PerfLog.mark(
                            "app:prewarm", PerfLog.nowMs() - started, "result=err"
                        )

                        null -> PerfLog.mark(
                            "app:prewarm", PerfLog.nowMs() - started, "result=throw"
                        )
                    }
                }
            },
            mediaMigration = {
                TdlibSessionImport.wipeTdlib(filesDir, cacheDir)
                val durableMedia = currentMediaRoot()
                if ((currentAccount() == null || currentAccount()?.databaseName == "monogram.db") && !durableMedia.exists() && cacheMedia.exists()) {
                    runCatching {
                        cacheMedia.copyRecursively(
                            durableMedia, overwrite = false
                        )
                    }.onFailure { AppLog.warn("media", "cache migration failed") }
                }
            },
            installDownloadSettings = {
                perfSpan("app:downloadConcurrency") {
                    DownloadSettings.install(this, ::applyDownloadSettings)
                    DownloadSettings.setPremium(sessionStore.readPremium())
                    MediaCacheLimitStore.install(filesDir)
                }
            },
            createMedia = {
                val durableMedia = currentMediaRoot()
                val client = client
                val warmup = warmup
                mediaRepository = perfSpan("app:mediaRepository") {
                    createMediaRepository(client, durableMedia)
                }
                val accountId = currentAccount()?.userId
                if (accountId != null) {
                    accountMediaRepositories[accountId] = mediaRepository
                } else {
                    bootstrapMediaRepository = mediaRepository
                }
                val repository = mediaRepository
                FileLeases.leaser = ::leaseAccountMediaFile
                settingsScope.launch {
                    ready.await()
                    warmup.prepareRecentHistory()
                    warmup.recentHistory.warmedMessages().forEach { message ->
                        if (!message.thumbCacheKey.isNullOrBlank()) {
                            repository.ensureLocalMessageThumb(message, MediaPriority.THUMB)
                        }
                    }
                }
            },
            createPush = {
                notifications = NotificationLocalStore(
                    this,
                    currentAccount()?.userId,
                    migrateLegacy = currentAccount()?.databaseName == "monogram.db",
                )
                push = perfSpan("app:push") {
                    PushCoordinator(
                        this,
                        client,
                        notifications,
                        mediaRepository,
                        DefaultStoreFactory(),
                        sessionStore,
                        otherAccountUserIds = {
                            accountRegistry.all().map { it.userId }
                        },
                        sharedPushSecret = sharedPushSecret::get,
                        notificationCache = warmup,
                    )
                }
                pushRouter = AccountPushRouter()
                currentAccount()?.userId?.let { userId ->
                    push.setAccountUserId(userId)
                    pushRouter.register(userId, push)
                    accountPushes[userId] = push
                    pushRouterCoordinatorIds += userId
                }
                sponsorSync = SponsorSyncManager(
                    settingsScope,
                    client,
                    db.sponsorDao(),
                    sessionStore,
                )
                appUpdate = AppUpdateManager(
                    scope = settingsScope,
                    isAuthorized = {
                        resolveStartOnHome(
                            sessionAuthorized = { sessionStore.isAuthorized() },
                            locallyAuthorized = { client.isLocallyAuthorized() },
                            explicitlySignedOut = {
                                sessionStore.readMeta(SessionMetadataStore.KEY_AUTHORIZED) == "0"
                            },
                        )
                    },
                    betaUpdates = { AppUpdateSettings.betaUpdates.value },
                    isSupporter = {
                        val id = sessionStore.readAuthorizedUserId()?.value
                        id != null && id in SponsorRegistry.sponsorIds.value
                    },
                    resolveUsername = { username ->
                        when (val outcome = client.resolveUsername(username)) {
                            is Outcome.Ok -> Outcome.Ok(outcome.value.peerId.value)
                            is Outcome.Err -> outcome
                        }
                    },
                    getHistoryPage = { chatId, limit, offsetId ->
                        client.getHistoryPage(PeerId(chatId), limit, offsetId)
                    },
                    telegramDownload = { message ->
                        mediaRepository.ensureLocalMessageMedia(
                            message,
                            MediaPriority.USER,
                        )
                    },
                    cancelDownloadKey = { key -> mediaRepository.cancel(key) },
                    progress = mediaRepository.downloadProgress,
                    currentVersionCode = { BuildConfig.VERSION_CODE },
                    currentCommit = { BuildConfig.GIT_COMMIT },
                    buildType = BuildConfig.BUILD_TYPE,
                    supportedAbis = { Build.SUPPORTED_ABIS.toList() },
                    installer = AndroidAppUpdateInstaller(this),
                    updateDir = File(cacheDir, "updates"),
                )
            },
            startPush = {
                runCatching { push.start() }.onFailure {
                    AppLog.warn(
                        "startup", "push start failed"
                    )
                }
            },
            startAccounts = {
                try {
                    val readyUserIds = accountOperations.withLock {
                        val ready = prepareStartupAccounts(
                            accounts = accountRegistry.all(),
                            activeUserId = accountRegistry.activeUserId,
                            onUnavailable = {
                                AppLog.warn(
                                    "startup", "account storage unavailable"
                                )
                            },
                        ) { account ->
                            val runtime = accountRuntimeManager.open(account)
                            runtime.client.installDurableUpdateSink(
                                AccountDurableUpdateSink(runtime.warmup)::commit,
                            )
                        }
                        startAccountPushes(ready)
                        pushReady.complete(Unit)
                        ready
                    }
                    accountRuntimeManager.startAll(readyUserIds)
                    accountOperations.withLock {
                        for (runtime in accountRuntimeManager.all()) {
                            if (runtime.account.userId !in readyUserIds) continue
                            try {
                                if (runtime.sessionStore.readMeta(SessionMetadataStore.KEY_AUTHORIZED) == "0") continue
                                if (runtime.client.isLocallyAuthorized() == Outcome.Ok(true)) {
                                    accountWorkManager.start(runtime)
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                AppLog.warn("startup", "account background work unavailable")
                            }
                        }
                        applyDownloadSettings(DownloadSettings.state.value)
                        startAccountPushes(readyUserIds)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    AppLog.warn("startup", "account clients start failed")
                    throw error
                }
            },
            startSponsor = {
                runCatching { sponsorSync.start() }.onFailure {
                    AppLog.warn(
                        "startup", "sponsor start failed"
                    )
                }
            },
            startAppUpdate = {
                runCatching { appUpdate.start() }.onFailure {
                    AppLog.warn(
                        "startup", "app update start failed"
                    )
                }
            },
        )
    }

    private fun monogramInitConnection(): ClientInitInfo {
        val locale = Locale.getDefault()
        return ClientInitInfo(
            deviceModel = initDeviceModel(),
            systemVersion = Build.VERSION.RELEASE.orEmpty().ifBlank {
                "SDK ${Build.VERSION.SDK_INT}"
            },
            appVersion = BuildConfig.VERSION_NAME.ifBlank { "0" },
            systemLangCode = locale.language.ifBlank { "en" },
            langPack = "android",
            langCode = locale.toLanguageTag().ifBlank { "en" },
        )
    }
}

internal fun acquireAccountMediaLease(
    file: File,
    repositories: Collection<MediaRepository>,
    bootstrap: MediaRepository?,
): AutoCloseable? =
    repositories.firstNotNullOfOrNull { it.leaseFile(file) } ?: bootstrap?.leaseFile(file)

internal fun canRemoveAccount(accountCount: Int): Boolean = accountCount > 1

private fun initDeviceModel(): String {
    val manufacturer = Build.MANUFACTURER.orEmpty().trim()
    val model = Build.MODEL.orEmpty().trim()
    if (isEmulatorDevice(model)) return "Android Emulator"
    return when {
        model.isEmpty() && manufacturer.isEmpty() -> "Android"
        model.isEmpty() -> manufacturer
        manufacturer.isEmpty() || model.startsWith(manufacturer, ignoreCase = true) -> model
        else -> "$manufacturer $model"
    }
}

private fun isEmulatorDevice(model: String): Boolean {
    val fingerprint = Build.FINGERPRINT.orEmpty()
    val product = Build.PRODUCT.orEmpty()
    val hardware = Build.HARDWARE.orEmpty()
    return fingerprint.startsWith("generic") || fingerprint.contains(
        "emulator", ignoreCase = true
    ) || product.contains("sdk", ignoreCase = true) || hardware.contains(
        "goldfish", ignoreCase = true
    ) || hardware.contains("ranchu", ignoreCase = true) || model.contains(
        "sdk_gphone", ignoreCase = true
    ) || model.contains("emulator", ignoreCase = true) || model.contains(
        "google_sdk", ignoreCase = true
    )
}
