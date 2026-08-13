package org.monogram.presentation.features.gallery

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore

fun queryImages(context: Context): List<GalleryMediaItem> {
    val result = mutableListOf<GalleryMediaItem>()
    val projection = imageProjection(Build.VERSION.SDK_INT)
    context.contentResolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        projection,
        null,
        null,
        "${MediaStore.Images.Media.DATE_ADDED} DESC"
    )?.use { cursor ->
        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
        val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
        val bucketColumn = cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
        val relColumn = cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
        while (cursor.moveToNext()) {
            val bucket = if (bucketColumn != -1) cursor.getString(bucketColumn).orEmpty() else ""
            val relative = if (relColumn != -1) cursor.getString(relColumn).orEmpty() else ""
            result.add(
                GalleryMediaItem(
                    uri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        cursor.getLong(idColumn)
                    ),
                    dateAdded = cursor.getLong(dateColumn),
                    isVideo = false,
                    durationMs = null,
                    bucketName = bucket,
                    relativePath = relative,
                    isCamera = isCameraBucket(bucket, relative),
                    isScreenshot = isScreenshotsBucket(bucket, relative)
                )
            )
        }
    }
    return result
}

fun queryVideos(context: Context): List<GalleryMediaItem> {
    val result = mutableListOf<GalleryMediaItem>()
    val projection = videoProjection(Build.VERSION.SDK_INT)
    context.contentResolver.query(
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        projection,
        null,
        null,
        "${MediaStore.Video.Media.DATE_ADDED} DESC"
    )?.use { cursor ->
        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
        val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
        val bucketColumn = cursor.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
        val relColumn = cursor.getColumnIndex(MediaStore.Video.Media.RELATIVE_PATH)
        val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
        while (cursor.moveToNext()) {
            val bucket = if (bucketColumn != -1) cursor.getString(bucketColumn).orEmpty() else ""
            val relative = if (relColumn != -1) cursor.getString(relColumn).orEmpty() else ""
            result.add(
                GalleryMediaItem(
                    uri = ContentUris.withAppendedId(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        cursor.getLong(idColumn)
                    ),
                    dateAdded = cursor.getLong(dateColumn),
                    isVideo = true,
                    durationMs = cursor.getLong(durationColumn).takeIf { it > 0L },
                    bucketName = bucket,
                    relativePath = relative,
                    isCamera = isCameraBucket(bucket, relative),
                    isScreenshot = isScreenshotsBucket(bucket, relative)
                )
            )
        }
    }
    return result
}

internal fun imageProjection(sdkInt: Int): Array<String> = buildList {
    add(MediaStore.Images.Media._ID)
    add(MediaStore.Images.Media.DATE_ADDED)
    add(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
    if (sdkInt >= Build.VERSION_CODES.Q) {
        add(MediaStore.Images.Media.RELATIVE_PATH)
    }
}.toTypedArray()

internal fun videoProjection(sdkInt: Int): Array<String> = buildList {
    add(MediaStore.Video.Media._ID)
    add(MediaStore.Video.Media.DATE_ADDED)
    add(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
    if (sdkInt >= Build.VERSION_CODES.Q) {
        add(MediaStore.Video.Media.RELATIVE_PATH)
    }
    add(MediaStore.Video.Media.DURATION)
}.toTypedArray()

private fun isCameraBucket(bucket: String, relativePath: String): Boolean {
    val b = bucket.lowercase()
    val p = relativePath.lowercase()
    return b.contains("camera") || p.contains("/camera")
}

private fun isScreenshotsBucket(bucket: String, relativePath: String): Boolean {
    val b = bucket.lowercase()
    val p = relativePath.lowercase()
    return b.contains("screenshot") || p.contains("screenshots")
}