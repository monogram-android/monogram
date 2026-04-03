package org.monogram.presentation.features.gallery

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore

fun queryImages(context: Context): List<GalleryMediaItem> {
    val result = mutableListOf<GalleryMediaItem>()
    val projection = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DATE_ADDED,
        MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
        MediaStore.Images.Media.RELATIVE_PATH
    )
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
                    duration = 0,
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
    val projection = arrayOf(
        MediaStore.Video.Media._ID,
        MediaStore.Video.Media.DATE_ADDED,
        MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
        MediaStore.Video.Media.RELATIVE_PATH,
        MediaStore.Video.Media.DURATION
    )
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
        val durationColumn = cursor.getColumnIndex(MediaStore.Video.Media.DURATION)
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
                    duration = cursor.getLong(durationColumn),
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
