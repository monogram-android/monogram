package org.monogram.app

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.monogram.presentation.core.util.CopiedUriFile
import org.monogram.presentation.core.util.copyUriToTempDocumentFile
import org.monogram.presentation.core.util.copyUriToTempMediaFile
import org.monogram.presentation.features.share.IncomingShareRequest
import org.monogram.presentation.features.share.PendingAttachment
import org.monogram.presentation.features.share.PendingAttachmentKind

class IncomingShareIntentResolver(
    private val copyUriToAttachment: (Uri) -> PendingAttachment?
) {
    constructor(context: android.content.Context) : this(
        copyUriToAttachment = { uri -> copyUriToAttachment(context, uri) }
    )

    suspend fun resolve(intent: Intent): IncomingShareRequest? = withContext(Dispatchers.IO) {
        val payload = extractIncomingSharePayload(intent) ?: return@withContext null
        val attachments = payload.uris.mapNotNull(copyUriToAttachment)

        IncomingShareRequest(
            requestId = System.currentTimeMillis(),
            text = payload.text,
            attachments = attachments
        ).takeIf { it.text.isNotBlank() || it.attachments.isNotEmpty() }
    }
}

internal data class ExtractedIncomingSharePayload(
    val text: String,
    val uris: List<Uri>
)

internal data class IncomingSharePayloadInput(
    val action: String?,
    val text: String?,
    val clipDataUris: List<String> = emptyList(),
    val extraStreamUris: List<String> = emptyList()
)

internal data class NormalizedIncomingSharePayload(
    val text: String,
    val uriStrings: List<String>
)

internal fun extractIncomingSharePayload(intent: Intent): ExtractedIncomingSharePayload? {
    val extraStreamUris = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(
            intent.parcelableExtraCompat<Uri>(Intent.EXTRA_STREAM)?.toString()
        )

        Intent.ACTION_SEND_MULTIPLE -> intent.parcelableArrayListExtraCompat<Uri>(Intent.EXTRA_STREAM)
            ?.map(Uri::toString)
            .orEmpty()

        else -> emptyList()
    }

    val normalized = normalizeIncomingSharePayload(
        IncomingSharePayloadInput(
            action = intent.action,
            text = intent.getStringExtra(Intent.EXTRA_TEXT),
            clipDataUris = intent.clipData?.collectUriStrings().orEmpty(),
            extraStreamUris = extraStreamUris
        )
    ) ?: return null

    return ExtractedIncomingSharePayload(
        text = normalized.text,
        uris = normalized.uriStrings.map(Uri::parse)
    )
}

internal fun normalizeIncomingSharePayload(
    input: IncomingSharePayloadInput
): NormalizedIncomingSharePayload? {
    if (input.action != Intent.ACTION_SEND && input.action != Intent.ACTION_SEND_MULTIPLE) return null

    val uris = LinkedHashSet<String>()
    input.clipDataUris.asSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .forEach(uris::add)
    input.extraStreamUris.asSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .forEach(uris::add)

    val text = input.text.orEmpty().trim()
    if (text.isBlank() && uris.isEmpty()) return null

    return NormalizedIncomingSharePayload(
        text = text,
        uriStrings = uris.toList()
    )
}

internal fun copyUriToAttachment(context: android.content.Context, uri: Uri): PendingAttachment? {
    val mediaCopy = context.copyUriToTempMediaFile(uri)
    val kind = mediaCopy?.classifyAsMediaKind()
    if (kind != null) {
        return PendingAttachment(
            localPath = mediaCopy.localPath,
            kind = kind,
            deleteAfterUse = true
        )
    }

    val documentCopy = context.copyUriToTempDocumentFile(uri) ?: return null
    return PendingAttachment(
        localPath = documentCopy.localPath,
        kind = PendingAttachmentKind.DOCUMENT,
        deleteAfterUse = true
    )
}

private fun Intent.isShareIntent(): Boolean {
    return action == Intent.ACTION_SEND || action == Intent.ACTION_SEND_MULTIPLE
}

private fun ClipData.collectUriStrings(): List<String> {
    return buildList {
        for (index in 0 until itemCount) {
            getItemAt(index)?.uri?.toString()?.let(::add)
        }
    }
}

@Suppress("DEPRECATION")
private inline fun <reified T : android.os.Parcelable> Intent.parcelableExtraCompat(name: String): T? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(name, T::class.java)
    } else {
        getParcelableExtra(name)
    }
}

@Suppress("DEPRECATION")
private inline fun <reified T : android.os.Parcelable> Intent.parcelableArrayListExtraCompat(name: String): ArrayList<T>? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableArrayListExtra(name, T::class.java)
    } else {
        getParcelableArrayListExtra(name)
    }
}

private fun CopiedUriFile.classifyAsMediaKind(): PendingAttachmentKind? {
    val mime = mimeType.orEmpty().lowercase()
    val name = (displayName ?: localPath).lowercase()
    return when {
        mime == "image/gif" || name.endsWith(".gif") -> PendingAttachmentKind.GIF
        mime.startsWith("video/") -> PendingAttachmentKind.VIDEO
        mime.startsWith("image/") -> PendingAttachmentKind.PHOTO
        else -> null
    }
}
