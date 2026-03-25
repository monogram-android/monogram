package org.monogram.data.repository

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import org.monogram.core.ScopeProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.monogram.data.datasource.remote.UpdateRemoteDateSource
import org.monogram.data.infra.FileDownloadQueue
import org.monogram.data.infra.FileUpdateHandler
import org.monogram.data.service.UpdateInstallReceiver
import org.monogram.domain.models.*
import org.monogram.domain.repository.AuthRepository
import org.monogram.domain.repository.AuthStep
import org.monogram.domain.repository.UpdateRepository
import java.io.File
import java.io.FileInputStream

class UpdateRepositoryImpl(
    private val context: Context,
    private val remote: UpdateRemoteDateSource,
    private val fileQueue: FileDownloadQueue,
    private val fileUpdateHandler: FileUpdateHandler,
    private val authRepository: AuthRepository,
    scopeProvider: ScopeProvider
) : UpdateRepository {

    private val scope = scopeProvider.appScope

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    override val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    private var currentUpdateInfo: UpdateInfo? = null

    init {
        scope.launch {
            fileUpdateHandler.downloadProgress.collect { (id, progress) ->
                val info = currentUpdateInfo ?: return@collect
                val current = _updateState.value
                if (info.fileId.toLong() == id &&
                    (current is UpdateState.Downloading || current is UpdateState.UpdateAvailable)
                ) {
                    _updateState.value = UpdateState.Downloading(progress, info.fileSize)
                }
            }
        }

        scope.launch {
            fileUpdateHandler.downloadCompleted.collect { (id, path) ->
                val info = currentUpdateInfo ?: return@collect
                if (info.fileId.toLong() == id) {
                    _updateState.value = UpdateState.ReadyToInstall(path)
                }
            }
        }
    }

    override suspend fun checkForUpdates() {
        if (authRepository.authState.value !is AuthStep.Ready) return

        _updateState.value = UpdateState.Checking

        runCatching {
            val info = remote.fetchLatestUpdate() ?: return@runCatching _updateState.value.let {
                _updateState.value = UpdateState.Error("No update found")
            }

            val currentVersionCode = try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionCode
            } catch (e: Exception) {
                0
            }

            Log.d("UpdateRepository", "Current version code: $currentVersionCode, Latest version code: ${info.versionCode}")

            if (info.versionCode <= currentVersionCode) {
                _updateState.value = UpdateState.UpToDate
                return@runCatching
            }

            currentUpdateInfo = info
            _updateState.value = UpdateState.UpdateAvailable(info)
        }.onFailure {
            _updateState.value = UpdateState.Error(it.message ?: "Failed to check for updates")
        }
    }

    override fun downloadUpdate() {
        val info = currentUpdateInfo ?: return
        _updateState.value = UpdateState.Downloading(0f, info.fileSize)
        fileQueue.enqueue(
            fileId = info.fileId,
            priority = 32,
            type = FileDownloadQueue.DownloadType.DEFAULT,
            synchronous = true
        )
    }

    override fun cancelDownload() {
        val info = currentUpdateInfo ?: return
        fileQueue.cancelDownload(info.fileId, force = true)
        _updateState.value = UpdateState.UpdateAvailable(info)
    }

    override fun installUpdate() {
        val state = _updateState.value as? UpdateState.ReadyToInstall ?: return
        val file = File(state.filePath)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val packageInstaller = context.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                    .apply {
                        setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                    }

                val sessionId = packageInstaller.createSession(params)
                val session = packageInstaller.openSession(sessionId)

                FileInputStream(file).use { input ->
                    session.openWrite("package", 0, file.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }

                val intent = Intent(context, UpdateInstallReceiver::class.java)
                val pendingIntent = PendingIntent.getBroadcast(
                    context, 0, intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                session.commit(pendingIntent.intentSender)
                session.close()
                return
            } catch (e: Exception) {
                Log.e("UpdateRepository", "PackageInstaller flow failed, using fallback", e)
            }
        }

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        context.startActivity(
            Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                data = uri
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    override suspend fun getTdLibVersion() = remote.getTdLibVersion()

    override suspend fun getTdLibCommitHash() = remote.getTdLibCommitHash()
}