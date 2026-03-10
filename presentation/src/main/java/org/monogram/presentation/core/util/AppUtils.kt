package org.monogram.presentation.core.util

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build

object AppUtils {
    private fun getPackageInfo(context: Context): PackageInfo? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
        } catch (e: Exception) {
            null
        }
    }

    fun getVersionName(context: Context): String {
        return getPackageInfo(context)?.versionName ?: "1.0.0"
    }

    fun getVersionCode(context: Context): Long {
        val packageInfo = getPackageInfo(context) ?: return 1L
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
    }

    fun getFullVersionString(context: Context): String {
        val name = getVersionName(context)
        val code = getVersionCode(context)
        return "$name ($code)"
    }
}