package org.monogram.presentation.core.util

import android.graphics.Bitmap
import androidx.compose.runtime.Stable

@Stable
interface IDownloadUtils {

    fun saveFileToDownloads(filePath: String)

    fun saveBitmapToGallery(bitmap: Bitmap)

    fun copyBitmapToClipboard(bitmap: Bitmap)

    fun openFile(filePath: String)

    fun copyImageToClipboard(filePath: String)

    fun isWifiConnected(): Boolean

    fun isMobileDataConnected(): Boolean

    fun isRoaming(): Boolean
}
