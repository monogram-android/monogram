package org.monogram.data.mtproto

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.content.FileProvider
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.core.isEmpty
import io.ktor.utils.io.core.readBytes
import io.ktor.utils.io.core.remaining
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.monogram.data.datasource.remote.GitHubRemoteDataSource
import org.monogram.domain.models.RichText
import org.monogram.domain.models.UpdateInfo
import org.monogram.domain.models.UpdateState
import org.monogram.domain.repository.UpdateRepository
import java.io.File
import java.io.FileInputStream

/** One pending APK advertised by the update channel and not yet downloaded. */
internal data class MtProtoPendingRelease(
    val version: String,
    val description: String,
    val changelog: List<RichText>,
    val assetId: Long,
    val fileName: String,
    val fileSize: Long,
    val downloadUrl: String,
)

/**
 * Downloads one APK from the update channel, reporting progress through [onProgress].
 * Returns the fully written file.
 */
internal fun interface MtProtoUpdateApkDownloader {
    suspend fun download(
        url: String,
        targetFile: File,
        totalSize: Long,
        onProgress: suspend (Float) -> Unit,
    )
}

/**
 * App update channel for the Kotlin MTProto build.
 *
 * Updates are retrieved from the project's GitHub releases; install reuses the existing
 * package-installer flow. Check/download failures surface explicit [UpdateState.Error] values.
 */
internal class MtProtoUpdateRepository(
    private val context: Context,
    private val releaseSource: suspend () -> Result<GitHubRemoteDataSource.GitHubReleaseResponse>,
    private val apkDownloader: MtProtoUpdateApkDownloader,
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val currentVersion: String = resolveCurrentVersion(context),
    private val updatesDirectory: File = File(context.cacheDir, "updates"),
) : UpdateRepository {
    private val mutableUpdateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    override val updateState: StateFlow<UpdateState> = mutableUpdateState.asStateFlow()

    private val pendingMutex = Mutex()
    private var pendingRelease: MtProtoPendingRelease? = null
    private var downloadJob: Job? = null

    override suspend fun checkForUpdates() {
        if (mutableUpdateState.value is UpdateState.Downloading || mutableUpdateState.value is UpdateState.ReadyToInstall) {
            return
        }
        mutableUpdateState.value = UpdateState.Checking
        val release = releaseSource().fold(
            onSuccess = { it },
            onFailure = { failure ->
                mutableUpdateState.value = UpdateState.Error(
                    failure.message ?: "Unable to reach the update channel",
                )
                return
            },
        )
        val apk = release.assets.firstOrNull { it.name.endsWith(APK_SUFFIX, ignoreCase = true) }
        when {
            apk == null -> mutableUpdateState.value =
                UpdateState.Error("Update channel has no APK asset for ${release.tagName}")

            isSameVersion(release.tagName, currentVersion) ->
                mutableUpdateState.value = UpdateState.UpToDate

            else -> {
                pendingMutex.withLock {
                    pendingRelease = MtProtoPendingRelease(
                        version = release.tagName.removePrefix(VERSION_PREFIX),
                        description = release.name.orEmpty(),
                        changelog = release.body.orEmpty()
                            .lines()
                            .filter { it.isNotBlank() }
                            .map { RichText(it.trim()) },
                        assetId = apk.id,
                        fileName = apk.name,
                        fileSize = apk.size,
                        downloadUrl = apk.browserDownloadUrl,
                    )
                }
                mutableUpdateState.value = UpdateState.UpdateAvailable(updateInfo())
            }
        }
    }

    override fun downloadUpdate() {
        synchronized(this) {
            if (downloadJob?.isActive == true) return
            val state = mutableUpdateState.value
            if (state !is UpdateState.UpdateAvailable && state !is UpdateState.Error) return
        }
        val pending = pendingRelease ?: run {
            mutableUpdateState.value = UpdateState.Error("No update is available to download")
            return
        }
        downloadJob = scope.launch {
            try {
                updatesDirectory.mkdirs()
                val target = File(updatesDirectory, pending.fileName)
                apkDownloader.download(pending.downloadUrl, target, pending.fileSize) { progress ->
                    mutableUpdateState.value = UpdateState.Downloading(progress, pending.fileSize)
                }
                mutableUpdateState.value = UpdateState.ReadyToInstall(target.absolutePath)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                mutableUpdateState.value = UpdateState.Error(failure.message ?: "Unable to download update")
            }
        }
    }

    override fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        if (mutableUpdateState.value is UpdateState.Downloading) {
            mutableUpdateState.value = UpdateState.Idle
        }
    }

    override fun installUpdate() {
        val update = mutableUpdateState.value as? UpdateState.ReadyToInstall ?: return
        val apk = File(update.filePath)
        if (!apk.isFile) {
            mutableUpdateState.value = UpdateState.Error("Update APK is no longer available")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                val installer = context.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
                val sessionId = installer.createSession(params)
                installer.openSession(sessionId).use { session ->
                    FileInputStream(apk).use { input ->
                        session.openWrite("package", 0, apk.length()).use { output ->
                            input.copyTo(output)
                            session.fsync(output)
                        }
                    }
                    val intent = Intent(context, org.monogram.data.service.UpdateInstallReceiver::class.java)
                    val pendingIntent = PendingIntent.getBroadcast(
                        context,
                        0,
                        intent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                    session.commit(pendingIntent.intentSender)
                }
            }.onSuccess { return }.onFailure {
                mutableUpdateState.value = UpdateState.Error(it.message ?: "Unable to install update")
            }
        }

        runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", apk)
            context.startActivity(
                Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                    data = uri
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }.onFailure {
            mutableUpdateState.value = UpdateState.Error(it.message ?: "Unable to install update")
        }
    }

    override suspend fun getProtocolVersion(): String = "MTProto layer 223"

    override suspend fun getProtocolRevision(): String = "Kotlin MTProto"

    private fun updateInfo(): UpdateInfo {
        val pending = requireNotNull(pendingRelease)
        return UpdateInfo(
            version = pending.version,
            versionCode = 0,
            description = pending.description,
            changelog = pending.changelog,
            fileId = pending.assetId.toInt(),
            fileName = pending.fileName,
            fileSize = pending.fileSize,
        )
    }

    private companion object {
        const val APK_SUFFIX = ".apk"
        const val VERSION_PREFIX = "v"

        /** Release tags like `v0.3.1` compare equal to `0.3.1`; other formats are treated as newer. */
        fun isSameVersion(tag: String, currentVersion: String): Boolean =
            tag.equals(currentVersion, ignoreCase = true) ||
                tag.removePrefix(VERSION_PREFIX).equals(currentVersion, ignoreCase = true)

        fun resolveCurrentVersion(context: Context): String = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"
        } catch (_: Exception) {
            "1.0"
        }
    }
}

/** Streams an APK from the update channel into [targetFile] with coarse progress callbacks. */
internal class KtorMtProtoUpdateApkDownloader(
    private val httpClient: HttpClient,
    private val progressChunkBytes: Long = DEFAULT_PROGRESS_CHUNK_BYTES,
) : MtProtoUpdateApkDownloader {
    @Suppress("UNSAFE_EXPERIMENTAL_API_USAGE")
    override suspend fun download(
        url: String,
        targetFile: File,
        totalSize: Long,
        onProgress: suspend (Float) -> Unit,
    ) {
        val response: HttpResponse = httpClient.get(url)
        if (response.status != HttpStatusCode.OK) {
            throw IllegalStateException("Update download failed with code ${response.status.value}")
        }
        val channel = response.bodyAsChannel()
        targetFile.outputStream().use { output ->
            var written = 0L
            var nextProgressAt = progressChunkBytes
            while (!channel.isClosedForRead) {
                val packet = channel.readRemaining(PROGRESS_READ_CHUNK)
                while (!packet.isEmpty) {
                    val bytes = packet.readBytes()
                    output.write(bytes)
                    written += bytes.size
                    if (written >= nextProgressAt) {
                        nextProgressAt += progressChunkBytes
                        val fraction = if (totalSize > 0) {
                            (written.toDouble() / totalSize.toDouble()).toFloat().coerceIn(0f, 1f)
                        } else {
                            0f
                        }
                        onProgress(fraction)
                    }
                }
            }
            output.flush()
            if (totalSize in 1..written) onProgress(1f)
        }
    }

    private companion object {
        const val DEFAULT_PROGRESS_CHUNK_BYTES = 256L * 1024L
        const val PROGRESS_READ_CHUNK = 64L * 1024L
    }
}
