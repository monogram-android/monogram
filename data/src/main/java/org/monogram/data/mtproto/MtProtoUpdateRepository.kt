package org.monogram.data.mtproto

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.monogram.data.service.UpdateInstallReceiver
import org.monogram.domain.models.UpdateState
import org.monogram.domain.repository.UpdateRepository
import java.io.File
import java.io.FileInputStream

/**
 * App update state for the Kotlin MTProto build.
 *
 * APK retrieval is intentionally kept separate from Telegram file handles: the old implementation
 * downloaded a Telegram file id, which no longer exists. Until the update channel reader exposes a
 * direct APK URL, checks report an actionable state instead of leaving the repository unbound.
 */
internal class MtProtoUpdateRepository(
    private val context: Context,
) : UpdateRepository {
    private val mutableUpdateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    override val updateState: StateFlow<UpdateState> = mutableUpdateState.asStateFlow()

    override suspend fun checkForUpdates() {
        mutableUpdateState.value = UpdateState.Checking
        mutableUpdateState.value = UpdateState.Error(
            "Application update checks are being migrated to the Kotlin MTProto backend",
        )
    }

    override fun downloadUpdate() = Unit

    override fun cancelDownload() {
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
                    val intent = Intent(context, UpdateInstallReceiver::class.java)
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
}
