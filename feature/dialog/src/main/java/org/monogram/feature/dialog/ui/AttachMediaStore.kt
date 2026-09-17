package org.monogram.feature.dialog.ui

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.ContentResolver
import android.content.pm.PackageManager
import android.net.Uri
import android.database.Cursor
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A device photo or video offered by the attach sheet's gallery body. */
internal data class DeviceMediaItem(
    val uri: Uri,
    val kind: String,
    val durationSeconds: Int,
)

/** Gallery permission for Android 13+ split media permissions and older read storage access. */
internal object AttachGalleryAccess {

    fun required(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    /** True for full access and for the Android 14+ "selected photos" partial grant. */
    fun granted(context: Context): Boolean {
        if (required().all { granted(context, it) }) return true
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            granted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    }

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

/**
 * Reads recent photos and videos from MediaStore, newest first, one bounded page at a time.
 * Callers must already hold [AttachGalleryAccess]: without it the query is not made at all, so the
 * sheet shows its permission fallback instead of an empty grid.
 */
internal object AttachMediaStore {

    /** First page is a 3x4 grid; later pages prefetch two more screens. */
    const val FirstPage = 12
    const val PageSize = 24
    private const val ExternalVolume = "external"

    private val projection = arrayOf(
        MediaStore.Files.FileColumns._ID,
        MediaStore.Files.FileColumns.MEDIA_TYPE,
        MediaStore.Video.VideoColumns.DURATION,
    )
    private const val selection =
        "${MediaStore.Files.FileColumns.MEDIA_TYPE}=? OR ${MediaStore.Files.FileColumns.MEDIA_TYPE}=?"
    private val selectionArgs = arrayOf(
        MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
        MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
    )

    suspend fun page(context: Context, offset: Int, limit: Int): Result<List<DeviceMediaItem>> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (offset < 0 || limit <= 0) return@runCatching emptyList()
                val isR = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                val sort = MediaStore.MediaColumns.DATE_ADDED + " DESC, " +
                    MediaStore.Files.FileColumns._ID + " DESC"
                val cursor = if (isR) {
                    context.contentResolver.query(collectionUri(), projection, pageArgs(sort, offset, limit), null)
                } else {
                    runCatching {
                        context.contentResolver.query(
                            collectionUri(),
                            projection,
                            selection,
                            selectionArgs,
                            "$sort LIMIT $limit OFFSET $offset",
                        )
                    }.getOrElse {
                        context.contentResolver.query(
                            collectionUri(),
                            projection,
                            selection,
                            selectionArgs,
                            sort,
                        )
                    }
                }
                val skip = if (isR) 0 else offset
                cursor?.use { read(it, skip, limit) } ?: emptyList()
            }
        }

    private fun collectionUri(): Uri = MediaStore.Files.getContentUri(ExternalVolume)

    @RequiresApi(Build.VERSION_CODES.R)
    private fun pageArgs(sort: String, offset: Int, limit: Int): Bundle = Bundle().apply {
        putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
        putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs)
        putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sort)
        putInt(ContentResolver.QUERY_ARG_OFFSET, offset)
        putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
    }

    private fun read(cursor: Cursor, skip: Int, limit: Int): List<DeviceMediaItem> {
        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
        val typeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
        val durationColumn = cursor.getColumnIndex(MediaStore.Video.VideoColumns.DURATION)
        val items = ArrayList<DeviceMediaItem>(limit)
        var rows = 0
        while (cursor.moveToNext()) {
            if (rows < skip) {
                rows++
                continue
            }
            if (items.size >= limit) break
            val video = cursor.getInt(typeColumn) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
            val collection = if (video) {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            val durationMs = if (durationColumn >= 0) cursor.getLong(durationColumn) else 0L
            items += DeviceMediaItem(
                uri = ContentUris.withAppendedId(collection, cursor.getLong(idColumn)),
                kind = if (video) "video" else "photo",
                durationSeconds = ((durationMs + 500L) / 1000L).toInt(),
            )
            rows++
        }
        return items
    }
}

/** Chip text for a video tile, e.g. `0:12`. */
internal fun mediaDurationLabel(seconds: Int): String {
    val safe = seconds.coerceAtLeast(0)
    val minutes = safe / 60
    val remainder = safe % 60
    return "$minutes:${remainder.toString().padStart(2, '0')}"
}
