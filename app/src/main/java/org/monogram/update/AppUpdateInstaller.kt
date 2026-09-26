package org.monogram.update

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import org.monogram.core.common.AppLog

fun interface AppUpdateInstaller {
    fun install(file: File)
}

class AndroidAppUpdateInstaller(
    private val context: Context,
) : AppUpdateInstaller {
    override fun install(file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                installWithSession(file)
                return
            } catch (error: Exception) {
                AppLog.warn("update", "session install failed ${error.javaClass.simpleName}")
            }
        }
        installWithIntent(file)
    }

    private fun installWithSession(file: File) {
        val packageInstaller = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
            }
        }
        val sessionId = packageInstaller.createSession(params)
        val session = packageInstaller.openSession(sessionId)
        try {
            FileInputStream(file).use { input ->
                session.openWrite("package", 0, file.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            val intent = Intent(context, UpdateInstallReceiver::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_MUTABLE
                } else {
                    0
                }
            val pendingIntent = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            session.commit(pendingIntent.intentSender)
        } finally {
            session.close()
        }
    }

    private fun installWithIntent(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(view)
        } catch (_: ActivityNotFoundException) {
            AppLog.warn("update", "no installer activity")
        }
    }
}

class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE,
        )
        AppLog.api("update", "install status=$status")
        if (status != PackageInstaller.STATUS_PENDING_USER_ACTION) return
        val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        } ?: return
        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(confirm)
        } catch (_: ActivityNotFoundException) {
            AppLog.warn("update", "no confirm installer activity")
        }
    }
}
