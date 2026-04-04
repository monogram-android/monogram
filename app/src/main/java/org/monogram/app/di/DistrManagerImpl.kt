package org.monogram.app.di

import android.content.Context
import android.os.Build
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.firebase.FirebaseApp
import org.monogram.domain.managers.DistrManager

class DistrManagerImpl(private val context: Context) : DistrManager {
    override fun isGmsAvailable(): Boolean {
        return GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    }

    override fun isFcmAvailable(): Boolean {
        return FirebaseApp.getApps(context).isNotEmpty()
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