package org.monogram.update

import io.ktor.client.HttpClient
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.monogram.core.common.AppLog
import org.monogram.core.common.Outcome
import org.monogram.core.common.SponsorRegistry
import org.monogram.core.models.AppUpdate
import org.monogram.core.models.AppUpdateInfo
import org.monogram.core.models.AppUpdateState
import org.monogram.core.models.Message
import org.monogram.core.models.MessageId
import org.monogram.core.models.PeerId
import org.monogram.core.models.rememberHiddenInternalChat
import org.monogram.feature.settings.AppUpdateController
import org.monogram.network.http.HttpModule

class AppUpdateManager(
    private val scope: CoroutineScope,
    private val isAuthorized: suspend () -> Boolean,
    private val betaUpdates: () -> Boolean,
    private val isSupporter: suspend () -> Boolean,
    private val resolveUsername: suspend (String) -> Outcome<Long>,
    private val getHistoryPage: suspend (Long, Int, Int) -> Outcome<List<Message>>,
    private val telegramDownload: suspend (Message) -> Outcome<File>,
    private val cancelDownloadKey: (String) -> Unit,
    private val progress: StateFlow<Map<String, Long>>,
    private val currentVersionCode: () -> Int,
    private val currentCommit: () -> String,
    private val buildType: String,
    private val supportedAbis: () -> List<String>,
    private val installer: AppUpdateInstaller,
    private val updateDir: File,
    private val httpClient: HttpClient = HttpModule.createClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AppUpdateController {
    private val _state = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    override val state: StateFlow<AppUpdateState> = _state.asStateFlow()

    private var downloadJob: Job? = null
    private var progressJob: Job? = null
    private var currentInfo: AppUpdateInfo? = null
    private val started = AtomicBoolean(false)

    fun start() {
        if (!AppUpdate.inAppUpdatesEnabled(buildType)) {
            AppLog.api(TAG, "check skipped debug")
            return
        }
        if (!started.compareAndSet(false, true)) return
        scope.launch(ioDispatcher) {
            while (isActive) {
                if (betaUpdates() || isAuthorized()) {
                    checkForUpdatesInternal()
                    if (checkSettled()) break
                }
                delay(AUTH_CHECK_INTERVAL_MS)
            }
            var wasSupporter = isSupporter()
            SponsorRegistry.sponsorIds.collect {
                val now = isSupporter()
                if (now && !wasSupporter && AppUpdate.actionsBetaAllowed(betaUpdates(), now)) {
                    checkForUpdatesInternal()
                }
                wasSupporter = now
            }
        }
    }

    override fun checkForUpdates() {
        if (!AppUpdate.inAppUpdatesEnabled(buildType)) return
        scope.launch(ioDispatcher) { checkForUpdatesInternal() }
    }

    override fun downloadUpdate() {
        if (!AppUpdate.inAppUpdatesEnabled(buildType)) return
        val info = when (val current = _state.value) {
            is AppUpdateState.Available -> current.info
            is AppUpdateState.Error -> current.info ?: currentInfo
            else -> currentInfo
        } ?: return
        currentInfo = info
        _state.value = AppUpdateState.Downloading(info, 0L)
        progressJob?.cancel()
        downloadJob?.cancel()
        if (info.downloadUrl != null) {
            downloadJob = scope.launch(ioDispatcher) {
                val zip = File(updateDir, "artifact.zip")
                when (
                    val result = downloadHttpApk(httpClient, info.downloadUrl!!, zip) { bytes ->
                        val current = _state.value
                        if (current is AppUpdateState.Downloading || current is AppUpdateState.Available) {
                            _state.value = AppUpdateState.Downloading(info, bytes)
                        }
                    }
                ) {
                    is Outcome.Ok -> {
                        val unpacked = File(updateDir, "unpacked")
                        val preferred = buildList {
                            add(info.fileName)
                            add("monogram-universal-${info.version}-${info.buildType}.apk")
                            for (abi in supportedAbis()) {
                                add("monogram-$abi-${info.version}-${info.buildType}.apk")
                            }
                        }.distinct()
                        val apk = extractApkFromZip(result.value, unpacked, preferred)
                        zip.delete()
                        if (apk == null) {
                            _state.value = AppUpdateState.Error(AppUpdate.NO_UPDATE, info)
                        } else {
                            _state.value = AppUpdateState.ReadyToInstall(info, apk.absolutePath)
                        }
                    }
                    is Outcome.Err ->
                        _state.value = if (result.message == "cancelled") {
                            AppUpdateState.Available(info)
                        } else {
                            AppUpdateState.Error(result.message, info)
                        }
                }
            }
            return
        }
        progressJob = scope.launch(ioDispatcher) {
            progress.collect { map ->
                val bytes = map[info.mediaCacheKey] ?: return@collect
                val current = _state.value
                if (current is AppUpdateState.Downloading || current is AppUpdateState.Available) {
                    _state.value = AppUpdateState.Downloading(info, bytes)
                }
            }
        }
        downloadJob = scope.launch(ioDispatcher) {
            when (val result = telegramDownload(info.toMessage())) {
                is Outcome.Ok -> {
                    progressJob?.cancel()
                    _state.value = AppUpdateState.ReadyToInstall(info, result.value.absolutePath)
                }
                is Outcome.Err -> {
                    progressJob?.cancel()
                    _state.value = if (result.message == "cancelled") {
                        AppUpdateState.Available(info)
                    } else {
                        AppUpdateState.Error(result.message, info)
                    }
                }
            }
        }
    }

    override fun cancelDownload() {
        val info = currentInfo ?: return
        if (info.downloadUrl == null) cancelDownloadKey(info.mediaCacheKey)
        downloadJob?.cancel()
        progressJob?.cancel()
        _state.value = AppUpdateState.Available(info)
    }

    override fun installUpdate() {
        if (!AppUpdate.inAppUpdatesEnabled(buildType)) return
        val ready = _state.value as? AppUpdateState.ReadyToInstall ?: return
        installer.install(File(ready.filePath))
    }

    private suspend fun checkForUpdatesInternal() {
        if (!AppUpdate.inAppUpdatesEnabled(buildType)) {
            AppLog.api(TAG, "check skipped debug")
            _state.value = AppUpdateState.Idle
            return
        }
        val localCode = currentVersionCode()
        val localCommit = currentCommit()
        val abis = supportedAbis()
        val betaPref = betaUpdates()
        if (betaPref) awaitSponsorLookup()
        val supporter = isSupporter()
        if (AppUpdate.actionsBetaAllowed(betaPref, supporter)) {
            AppLog.api(
                TAG,
                "check source=github local=$localCode commit=$localCommit type=$buildType abis=${abis.joinToString()}",
            )
            checkGitHub()
            return
        }
        if (betaPref) {
            AppLog.api(TAG, "github skipped not_supporter")
        }
        val authorized = isAuthorized()
        if (!authorized) {
            AppLog.api(TAG, "check source=channel skipped unauthorized")
            _state.value = AppUpdateState.Idle
            return
        }
        _state.value = AppUpdateState.Checking
        AppLog.api(
            TAG,
            "check source=channel local=$localCode type=$buildType abis=${abis.joinToString()}",
        )
        val chatId = when (val resolved = resolveUsername(AppUpdate.CHANNEL_USERNAME)) {
            is Outcome.Ok -> resolved.value
            is Outcome.Err -> AppUpdate.FALLBACK_CHAT_ID
        }.let { id -> if (id == 0L) AppUpdate.FALLBACK_CHAT_ID else id }
        rememberHiddenInternalChat(chatId)
        val messages = when (
            val page = getHistoryPage(chatId, AppUpdate.HISTORY_LIMIT, 0)
        ) {
            is Outcome.Ok -> page.value
            is Outcome.Err -> {
                AppLog.warn(TAG, "channel history failed chat=$chatId ${page.message}")
                _state.value = AppUpdateState.Error(page.message)
                return
            }
        }
        val infos = messages.mapNotNull(AppUpdate::fromMessage)
        AppLog.api(
            TAG,
            "channel chat=$chatId messages=${messages.size} apks=${infos.joinToString { it.fileName }}",
        )
        applySelected(infos)
    }

    private suspend fun checkGitHub() {
        _state.value = AppUpdateState.Checking
        val artifacts = when (val fetched = fetchGitHubArtifacts(httpClient)) {
            is Outcome.Ok -> fetched.value
            is Outcome.Err -> {
                AppLog.warn(TAG, "github list failed ${fetched.message}")
                _state.value = AppUpdateState.Error(fetched.message)
                return
            }
        }
        AppLog.api(
            TAG,
            "github artifacts=${artifacts.size} ${artifacts.joinToString { "${it.name} id=${it.id} bytes=${it.sizeInBytes}" }}",
        )
        val picked = gitHubUpdateInfo(artifacts, buildType, supportedAbis())
        AppLog.api(
            TAG,
            if (picked == null) {
                "github pick=none type=$buildType want=${AppUpdate.compatibleBuildTypes(buildType).joinToString()} abis=${supportedAbis().joinToString()}"
            } else {
                "github pick=${picked.fileName} code=${picked.versionCode} abi=${picked.abi} " +
                    "commit=${picked.commit} bytes=${picked.fileSize}"
            },
        )
        applySelected(listOfNotNull(picked))
    }

    private fun applySelected(infos: List<AppUpdateInfo>) {
        val selected = AppUpdate.select(infos, supportedAbis(), buildType)
        val next = AppUpdate.afterCheck(true, currentVersionCode(), selected, currentCommit())
        if (next is AppUpdateState.Available) currentInfo = next.info
        AppLog.api(
            TAG,
            when (next) {
                is AppUpdateState.Available ->
                    "result=available ${next.info.fileName} code=${next.info.versionCode} commit=${next.info.commit}"
                AppUpdateState.UpToDate -> "result=up_to_date local=${currentVersionCode()} commit=${currentCommit()}"
                is AppUpdateState.Error -> "result=error ${next.message}"
                else -> "result=${next::class.simpleName}"
            },
        )
        _state.value = next
    }

    private fun AppUpdateInfo.toMessage(): Message = Message(
        id = MessageId(PeerId(chatId), messageId),
        senderId = null,
        text = description,
        date = 0L,
        outgoing = false,
        mediaCacheKey = mediaCacheKey,
        mediaKind = "document",
        fileName = fileName,
        fileSize = fileSize,
    )

    private suspend fun awaitSponsorLookup() {
        if (SponsorRegistry.loaded.value) return
        withTimeoutOrNull(SPONSOR_WAIT_MS) {
            SponsorRegistry.loaded.first { it }
        }
    }

    private fun checkSettled(): Boolean = when (val state = _state.value) {
        is AppUpdateState.Available,
        AppUpdateState.UpToDate,
        is AppUpdateState.Downloading,
        is AppUpdateState.ReadyToInstall -> true
        is AppUpdateState.Error -> state.message == AppUpdate.NO_UPDATE
        else -> false
    }

    private companion object {
        const val TAG = "update"
        const val AUTH_CHECK_INTERVAL_MS = 5_000L
        const val SPONSOR_WAIT_MS = 5_000L
    }
}
