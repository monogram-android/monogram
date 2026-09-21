package org.monogram.root

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import kotlinx.serialization.Serializable
import org.monogram.core.models.UploadItem

@Serializable
data class IncomingShare(
    val text: String = "",
    val attachments: List<IncomingShareAttachment> = emptyList(),
) {
    fun isEmpty(): Boolean = text.isBlank() && attachments.isEmpty()

    fun toBundle(): Bundle = Bundle().apply {
        putString(KEY_TEXT, text)
        putParcelableArrayList(KEY_ATTACHMENTS, ArrayList(attachments.map { it.toBundle() }))
    }

    companion object {
        private const val KEY_TEXT = "incoming_share_text"
        private const val KEY_ATTACHMENTS = "incoming_share_attachments"

        fun fromBundle(bundle: Bundle?): IncomingShare? {
            if (bundle == null) return null
            val text = bundle.getString(KEY_TEXT).orEmpty()
            @Suppress("DEPRECATION")
            val attachments = bundle.getParcelableArrayList<Bundle>(KEY_ATTACHMENTS)
                .orEmpty()
                .mapNotNull(IncomingShareAttachment::fromBundle)
            return IncomingShare(text, attachments).takeUnless { it.isEmpty() }
        }
    }
}

@Serializable
data class IncomingShareAttachment(
    val path: String,
    val kind: String,
    val mimeType: String,
    val fileName: String,
) {
    fun toUploadItem(): UploadItem = UploadItem(
        path = path,
        kind = kind,
        mimeType = mimeType,
        fileName = fileName,
    )

    fun toBundle(): Bundle = Bundle().apply {
        putString(KEY_PATH, path)
        putString(KEY_KIND, kind)
        putString(KEY_MIME, mimeType)
        putString(KEY_NAME, fileName)
    }

    companion object {
        private const val KEY_PATH = "path"
        private const val KEY_KIND = "kind"
        private const val KEY_MIME = "mime"
        private const val KEY_NAME = "name"

        fun fromBundle(bundle: Bundle): IncomingShareAttachment? {
            val path = bundle.getString(KEY_PATH)?.takeIf { it.isNotBlank() } ?: return null
            return IncomingShareAttachment(
                path = path,
                kind = bundle.getString(KEY_KIND).orEmpty().ifBlank { "document" },
                mimeType = bundle.getString(KEY_MIME).orEmpty(),
                fileName = bundle.getString(KEY_NAME).orEmpty().ifBlank { File(path).name },
            )
        }
    }
}

object IncomingShareStager {
    private const val MAX_ATTACHMENTS = 10
    private const val MAX_FILE_BYTES = 2L * 1024L * 1024L * 1024L
    private const val BUFFER_SIZE = 64 * 1024

    fun stage(context: Context, intent: Intent): IncomingShare? {
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
        val uris = streamUris(intent)
        if (uris.size > MAX_ATTACHMENTS) return null
        val attachments = uris.mapNotNullIndexed { index, uri ->
            stageUri(context, uri, intent.type.orEmpty(), index)
        }
        return IncomingShare(text = text, attachments = attachments)
            .takeUnless { it.isEmpty() }
    }

    internal fun streamUris(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(singleUri(intent) ?: clipUris(intent).firstOrNull())
        Intent.ACTION_SEND_MULTIPLE -> (multipleUris(intent) + clipUris(intent)).distinct()
        else -> emptyList()
    }

    @Suppress("DEPRECATION")
    private fun singleUri(intent: Intent): Uri? =
        intent.getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun multipleUris(intent: Intent): List<Uri> =
        intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()

    private fun clipUris(intent: Intent): List<Uri> = buildList {
        val clip = intent.clipData ?: return@buildList
        for (index in 0 until clip.itemCount) clip.getItemAt(index).uri?.let(::add)
    }

    private fun stageUri(context: Context, uri: Uri, fallbackMime: String, index: Int): IncomingShareAttachment? {
        if (!isAllowedUri(context, uri)) return null
        val resolver = context.contentResolver
        val mime = resolver.getType(uri).orEmpty().ifBlank { fallbackMime }
        val displayName = queryDisplayName(resolver, uri)
            ?.substringAfterLast('/')
            ?.ifBlank { null }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { null }
            ?: "shared-$index"
        val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val destinationDir = File(context.cacheDir, "incoming-shares").apply { mkdirs() }
        val destination = File(destinationDir, "${System.nanoTime()}-$safeName")
        val input = runCatching {
            if (uri.scheme == ContentResolver.SCHEME_CONTENT) resolver.openInputStream(uri)
            else FileInputStream(File(uri.path.orEmpty()))
        }.getOrNull() ?: return null
        val copied = runCatching { copyBounded(input, destination) }.getOrDefault(false)
        runCatching { input.close() }
        if (!copied) {
            runCatching { destination.delete() }
            return null
        }
        val kind = when {
            mime.startsWith("image/") -> "photo"
            mime.startsWith("video/") -> "video"
            else -> "document"
        }
        return IncomingShareAttachment(destination.absolutePath, kind, mime, displayName)
    }

    private fun copyBounded(input: InputStream, destination: File): Boolean {
        var total = 0L
        val buffer = ByteArray(BUFFER_SIZE)
        destination.outputStream().use { output ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_FILE_BYTES) return false
                output.write(buffer, 0, count)
            }
        }
        return total > 0L
    }

    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()

    private fun isAllowedUri(context: Context, uri: Uri): Boolean = when (uri.scheme?.lowercase()) {
        ContentResolver.SCHEME_CONTENT -> uri.authority != "${context.packageName}.files"
        "file" -> runCatching {
            val file = File(uri.path.orEmpty()).canonicalFile
            val externalRoot = Environment.getExternalStorageDirectory().canonicalFile.toPath()
            val privateRoots = buildList {
                add(context.filesDir.canonicalFile.toPath())
                add(context.cacheDir.canonicalFile.toPath())
                add(context.noBackupFilesDir.canonicalFile.toPath())
                context.getExternalFilesDirs(null).forEach { add(it.canonicalFile.toPath()) }
                context.externalCacheDirs.forEach { add(it.canonicalFile.toPath()) }
            }
            file.toPath().startsWith(externalRoot) && privateRoots.none { file.toPath().startsWith(it) }
        }.getOrDefault(false)
        else -> false
    }

    private inline fun <T, R> Iterable<T>.mapNotNullIndexed(transform: (Int, T) -> R?): List<R> {
        val result = ArrayList<R>()
        forEachIndexed { index, value -> transform(index, value)?.let(result::add) }
        return result
    }
}
