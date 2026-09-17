package org.monogram

import android.app.Application
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.monogram.core.common.AppLog
import org.monogram.core.common.PerfLog
import org.monogram.core.common.TelegramCredentials
import org.monogram.core.common.push.NotificationLocalStore
import org.monogram.core.database.DatabaseProvider
import org.monogram.core.database.OfflineWarmup
import org.monogram.core.database.SessionMetadataStore
import org.monogram.core.ui.AppearanceSettings
import org.monogram.core.ui.DownloadSettings
import org.monogram.core.ui.DownloadState
import org.monogram.core.ui.ImageCache
import org.monogram.core.ui.perf.perfSpan
import org.monogram.network.bridge.BridgedMtprotoClient
import org.monogram.network.http.MediaFetchKind
import org.monogram.network.http.MediaRepository
import org.monogram.network.http.TelegramChunkFetcher
import org.monogram.network.http.TelegramMediaFetcher
import org.monogram.push.PushCoordinator
import org.monogram.sponsor.SponsorSyncManager
import java.io.File

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
    lateinit var sessionStore: SessionMetadataStore
        private set
    lateinit var warmup: OfflineWarmup
        private set

    private val settingsScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun applyDownloadSettings(state: DownloadState) {
        client.applyDownloadConcurrency(state.lanes, state.parts)
        client.setFilePartKib(state.filePartKib)
    }

    override fun onCreate() {
        super.onCreate()
        val startedAt = PerfLog.nowMs()
        perfSpan("app:settings") {
            AppLog.init(cacheDir)
            ImageCache.install(this)
            AppearanceSettings.install(this)
        }
        val credentials = TelegramCredentials(
            apiId = BuildConfig.TELEGRAM_API_ID,
            apiHash = BuildConfig.TELEGRAM_API_HASH,
        )
        val database = perfSpan("app:database") { DatabaseProvider.get(this) }
        warmup = OfflineWarmup(database)
        settingsScope.launch(Dispatchers.IO) {
            runCatching { warmup.ensureStartupCleanup() }
                .onFailure { AppLog.warn("warmup", "startup cleanup failed") }
        }
        sessionStore = SessionMetadataStore(database)
        val sessionFile = File(filesDir, "mtproto.session.json")
        client = perfSpan("app:client") {
            BridgedMtprotoClient(
                credentials = credentials,
                sessionPath = sessionFile.absolutePath,
            )
        }
        val durableMedia = File(filesDir, "media")
        val cacheMedia = File(cacheDir, "media")
        if (!durableMedia.exists() && cacheMedia.exists()) {
            settingsScope.launch(Dispatchers.IO) {
                runCatching { cacheMedia.copyRecursively(durableMedia, overwrite = false) }
                    .onFailure { AppLog.warn("media", "cache migration failed") }
            }
        }
        perfSpan("app:downloadConcurrency") {
            DownloadSettings.install(this, ::applyDownloadSettings)
        }
        mediaRepository = perfSpan("app:mediaRepository") {
            MediaRepository(
                cacheRoot = durableMedia,
                telegramChunkFetcher = TelegramChunkFetcher { chatId, messageId, destPath, offset ->
                    client.downloadMessageMediaChunk(chatId, messageId, destPath, offset)
                },
                telegramFetcher = TelegramMediaFetcher { chatId, messageId, destPath, kind, priority ->
                    when (kind) {
                        MediaFetchKind.Thumb ->
                            client.downloadMessageThumb(chatId, messageId, destPath, priority)
                        MediaFetchKind.Display ->
                            client.downloadMessageDisplay(chatId, messageId, destPath, priority)
                        MediaFetchKind.Full ->
                            client.downloadMessageMedia(chatId, messageId, destPath, priority)
                    }
                },
                customEmojiFetcher = { documentId, destPath, priority ->
                    client.downloadCustomEmoji(documentId, destPath, priority)
                },
            )
        }
        notifications = NotificationLocalStore(this)
        push = perfSpan("app:push") {
            PushCoordinator(this, client, notifications, mediaRepository, DefaultStoreFactory())
                .also { it.start() }
        }
        sponsorSync = perfSpan("app:sponsorSync") {
            SponsorSyncManager(settingsScope, client, database.sponsorDao(), sessionStore)
        }
        PerfLog.mark("app:onCreate", PerfLog.nowMs() - startedAt)
    }
}
