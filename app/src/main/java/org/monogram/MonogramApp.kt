package org.monogram

import android.app.Application
import android.os.Build
import androidx.compose.material3.ComposeMaterial3Flags
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.monogram.core.common.AppLog
import org.monogram.core.common.DebugLog
import org.monogram.core.common.DebugStats
import org.monogram.core.common.Outcome
import org.monogram.core.common.PerfLog
import org.monogram.core.common.SponsorRegistry
import org.monogram.core.common.TelegramCredentials
import org.monogram.core.common.push.NotificationLocalStore
import org.monogram.core.database.DatabaseProvider
import org.monogram.core.database.MonogramDatabase
import org.monogram.core.database.OfflineWarmup
import org.monogram.core.database.SessionMetadataStore
import org.monogram.core.models.PeerId
import org.monogram.core.ui.AppUpdateSettings
import org.monogram.core.ui.AppearanceSettings
import org.monogram.core.ui.DownloadSettings
import org.monogram.core.ui.DownloadState
import org.monogram.core.ui.ImageCache
import org.monogram.core.ui.perf.perfSpan
import org.monogram.feature.settings.ProxySettingsStore
import org.monogram.mtproto.MtprotoNativeLoader
import org.monogram.network.bridge.BridgedMtprotoClient
import org.monogram.network.bridge.ClientInitInfo
import org.monogram.network.bridge.MtprotoTransportMode
import org.monogram.network.bridge.ProxyConfig
import org.monogram.network.bridge.ProxyType
import org.monogram.network.http.MediaFetchKind
import org.monogram.network.http.MediaPriority
import org.monogram.network.http.MediaRepository
import org.monogram.network.http.TelegramChunkFetcher
import org.monogram.network.http.TelegramInlineThumbPeek
import org.monogram.network.http.TelegramMediaFetcher
import org.monogram.network.http.TelegramPeerInlineThumbPeek
import org.monogram.push.PushCoordinator
import org.monogram.sponsor.SponsorSyncManager
import org.monogram.update.AndroidAppUpdateInstaller
import org.monogram.update.AppUpdateManager
import java.io.File
import java.util.Locale

class MonogramApp : Application() {
    lateinit var client: BridgedMtprotoClient
        private set
    lateinit var mediaRepository: MediaRepository
        private set
    lateinit var notifications: NotificationLocalStore
        private set
    lateinit var push: PushCoordinator

    lateinit var sponsorSync: SponsorSyncManager
        private set
    lateinit var appUpdate: AppUpdateManager
        private set
    lateinit var sessionStore: SessionMetadataStore
        private set
    lateinit var warmup: OfflineWarmup
        private set

    private val settingsScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ready = CompletableDeferred<Unit>()

    suspend fun awaitReady() = ready.await()

    fun awaitReadyBlocking(timeoutMs: Long = 20_000L): Boolean =
        runBlocking {
            try {
                withTimeout(timeoutMs) { ready.await() }
                true
            } catch (_: Exception) {
                false
            }
        }

    fun launchWhenReady(block: suspend () -> Unit): Job =
        settingsScope.launch {
            ready.await()
            block()
        }

    private fun applyDownloadSettings(state: DownloadState) {
        client.applyDownloadConcurrency(state.effectiveLanes, state.parts)
        client.setFilePartKib(state.filePartKib)
        client.setDownloadChunkKib(state.downloadChunkKib)
    }

    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    override fun onCreate() {
        super.onCreate()
        ComposeMaterial3Flags.isCheckboxStylingFixEnabled = true
        val startedAt = PerfLog.nowMs()
        perfSpan("app:settings") {
            AppLog.init(cacheDir)
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
            } catch (cancelled: CancellationException) {
                ready.completeExceptionally(cancelled)
                throw cancelled
            } catch (error: Throwable) {
                AppLog.warn("startup", "init failed")
                ready.completeExceptionally(error)
            }
        }
        PerfLog.mark("app:onCreate", PerfLog.nowMs() - startedAt)
    }

    private fun firstPaintSteps(): StartupSteps {
        val credentials = TelegramCredentials(
            apiId = BuildConfig.TELEGRAM_API_ID,
            apiHash = BuildConfig.TELEGRAM_API_HASH,
        )
        val durableMedia = File(filesDir, "media")
        val cacheMedia = File(cacheDir, "media")
        lateinit var db: MonogramDatabase
        return StartupSteps(
            openDatabase = {
                db = perfSpan("app:database") { DatabaseProvider.get(this) }
                warmup = OfflineWarmup(db)
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
                val sessionFile = File(filesDir, "mtproto.session.json")
                perfSpan("app:tdlibImport") {
                    runCatching { TdlibSessionImport.maybeImport(filesDir, sessionFile, cacheDir) }
                        .onFailure { AppLog.warn("session", "tdlib import failed") }
                }
                client = perfSpan("app:client") {
                    BridgedMtprotoClient(
                        credentials = credentials,
                        sessionPath = sessionFile.absolutePath,
                        initConnection = monogramInitConnection(),
                    )
                }
                ProxySettingsStore.load(this)?.let { saved ->
                    val applied = runCatching {
                        val transportMode =
                            MtprotoTransportMode.valueOf(saved.transportMode.uppercase())
                        val proxyType = ProxyType.valueOf(saved.kind)
                        if (proxyType == ProxyType.NONE) false else
                            client.setTransportMode(transportMode) is Outcome.Ok &&
                                    client.configureProxy(
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
                runCatching { warmup.prepareStartupSnapshot(sessionStore) }
                    .onFailure { AppLog.warn("warmup", "startup cleanup failed") }
            },
            prewarm = {
                if (BuildConfig.STARTUP_PREWARM) {
                    val started = PerfLog.nowMs()
                    when (val result = runCatching { client.connect() }.getOrNull()) {
                        is Outcome.Ok ->
                            PerfLog.mark("app:prewarm", PerfLog.nowMs() - started, "result=ok")

                        is Outcome.Err ->
                            PerfLog.mark("app:prewarm", PerfLog.nowMs() - started, "result=err")

                        null ->
                            PerfLog.mark("app:prewarm", PerfLog.nowMs() - started, "result=throw")
                    }
                }
            },
            mediaMigration = {
                TdlibSessionImport.wipeTdlib(filesDir, cacheDir)
                if (!durableMedia.exists() && cacheMedia.exists()) {
                    runCatching { cacheMedia.copyRecursively(durableMedia, overwrite = false) }
                        .onFailure { AppLog.warn("media", "cache migration failed") }
                }
            },
            installDownloadSettings = {
                perfSpan("app:downloadConcurrency") {
                    DownloadSettings.install(this, ::applyDownloadSettings)
                    DownloadSettings.setPremium(sessionStore.readPremium())
                }
            },
            createMedia = {
                mediaRepository = perfSpan("app:mediaRepository") {
                    MediaRepository(
                        cacheRoot = durableMedia,
                        telegramChunkFetcher = TelegramChunkFetcher { chatId, messageId, destPath, offset ->
                            client.downloadMessageMediaChunk(chatId, messageId, destPath, offset)
                        },
                        telegramFetcher = TelegramMediaFetcher { chatId, messageId, destPath, kind, priority ->
                            when (kind) {
                                MediaFetchKind.Thumb ->
                                    client.downloadMessageThumb(
                                        chatId,
                                        messageId,
                                        destPath,
                                        priority
                                    )

                                MediaFetchKind.Display ->
                                    client.downloadMessageDisplay(
                                        chatId,
                                        messageId,
                                        destPath,
                                        priority
                                    )

                                MediaFetchKind.Full ->
                                    client.downloadMessageMedia(
                                        chatId,
                                        messageId,
                                        destPath,
                                        priority
                                    )
                            }
                        },
                        customEmojiFetcher = { documentId, destPath, priority ->
                            client.downloadCustomEmoji(documentId, destPath, priority)
                        },
                        inlineThumbPeek = TelegramInlineThumbPeek { chatId, messageId ->
                            client.peekMessageInlineThumb(chatId, messageId)
                        },
                        peerInlineThumbPeek = TelegramPeerInlineThumbPeek { peerId ->
                            client.peekPeerInlineThumb(peerId)
                        },
                    )
                }
                client.setDownloadProgressListener { path, downloaded, _ ->
                    mediaRepository.onNativeProgress(path, downloaded)
                }
            },
            createPush = {
                notifications = NotificationLocalStore(this)
                push = perfSpan("app:push") {
                    PushCoordinator(
                        this,
                        client,
                        notifications,
                        mediaRepository,
                        DefaultStoreFactory(),
                        sessionStore,
                    )
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
                        if (sessionStore.isAuthorized()) {
                            true
                        } else {
                            when (val result = client.isLocallyAuthorized()) {
                                is Outcome.Ok -> result.value
                                is Outcome.Err -> false
                            }
                        }
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
                runCatching { push.start() }
                    .onFailure { AppLog.warn("startup", "push start failed") }
            },
            startSponsor = {
                runCatching { sponsorSync.start() }
                    .onFailure { AppLog.warn("startup", "sponsor start failed") }
            },
            startAppUpdate = {
                runCatching { appUpdate.start() }
                    .onFailure { AppLog.warn("startup", "app update start failed") }
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
    return fingerprint.startsWith("generic") ||
        fingerprint.contains("emulator", ignoreCase = true) ||
        product.contains("sdk", ignoreCase = true) ||
        hardware.contains("goldfish", ignoreCase = true) ||
        hardware.contains("ranchu", ignoreCase = true) ||
        model.contains("sdk_gphone", ignoreCase = true) ||
        model.contains("emulator", ignoreCase = true) ||
        model.contains("google_sdk", ignoreCase = true)
}
