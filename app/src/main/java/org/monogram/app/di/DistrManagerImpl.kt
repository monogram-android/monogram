package org.monogram.app.di

import android.content.Context
import android.os.Build
import org.monogram.domain.managers.DistrManager
import org.unifiedpush.android.connector.UnifiedPush

class DistrManagerImpl(
    private val context: Context,
    private val gmsRuntime: GmsRuntime
) : DistrManager {
    override fun isGmsAvailable(): Boolean {
        return gmsRuntime.isGmsAvailable
    }

    override fun isFcmAvailable(): Boolean {
        return gmsRuntime.isFcmConfigured
    }

    override fun isUnifiedPushDistributorAvailable(): Boolean {
        return UnifiedPush.getDistributors(context).isNotEmpty()
    }

    override fun isInstalledFromGooglePlay(): Boolean {
        val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getInstallerPackageName(context.packageName)
        }
        return installer == "com.android.vending"
    }
}