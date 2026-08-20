package org.monogram.data.mtproto

import org.monogram.data.db.dao.MtProtoDocumentLocationDao
import org.monogram.data.db.model.MtProtoDocumentLocationEntity
import org.monogram.mtproto.tl.generated.cloud.layer223.DocumentAttributeAnimated
import org.monogram.mtproto.tl.generated.cloud.layer223.DocumentAttributeAudio
import org.monogram.mtproto.tl.generated.cloud.layer223.DocumentAttributeFilename
import org.monogram.mtproto.tl.generated.cloud.layer223.DocumentAttributeImageSize
import org.monogram.mtproto.tl.generated.cloud.layer223.DocumentAttributeVideo
import org.monogram.mtproto.tl.generated.cloud.layer223.Document_be725c3b31

internal enum class MtProtoDocumentMediaKind {
    DOCUMENT,
    VIDEO,
    VIDEO_NOTE,
    GIF,
    AUDIO,
    VOICE,
}

internal data class MtProtoDocumentLocation(
    val documentId: Long,
    val accessHash: Long,
    val fileReference: ByteArray,
    val documentDcId: Int,
    val mimeType: String,
    val size: Long,
    val fileName: String,
    val mediaKind: MtProtoDocumentMediaKind,
    val width: Int?,
    val height: Int?,
    val duration: Int?,
    val supportsStreaming: Boolean,
    val title: String?,
    val performer: String?,
    val waveform: ByteArray?,
)

internal interface MtProtoDocumentLocationStore {
    suspend fun upsert(scope: MtProtoAuthKeyScope, document: Document_be725c3b31)
    suspend fun get(scope: MtProtoAuthKeyScope, documentId: Long): MtProtoDocumentLocation?
    suspend fun deleteAccount(accountSlot: String, environment: MtProtoEnvironment)
}

internal object NoOpMtProtoDocumentLocationStore : MtProtoDocumentLocationStore {
    override suspend fun upsert(scope: MtProtoAuthKeyScope, document: Document_be725c3b31) = Unit
    override suspend fun get(scope: MtProtoAuthKeyScope, documentId: Long): MtProtoDocumentLocation? = null
    override suspend fun deleteAccount(accountSlot: String, environment: MtProtoEnvironment) = Unit
}

internal class MtProtoRoomDocumentLocationStore(
    private val dao: MtProtoDocumentLocationDao,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : MtProtoDocumentLocationStore {
    override suspend fun upsert(scope: MtProtoAuthKeyScope, document: Document_be725c3b31) {
        val video = document.attributes.filterIsInstance<DocumentAttributeVideo>().firstOrNull()
        val audio = document.attributes.filterIsInstance<DocumentAttributeAudio>().firstOrNull()
        val dimensions = document.attributes.filterIsInstance<DocumentAttributeImageSize>().firstOrNull()
        val mediaKind = when {
            audio?.voice == true -> MtProtoDocumentMediaKind.VOICE
            audio != null -> MtProtoDocumentMediaKind.AUDIO
            document.attributes.any { it is DocumentAttributeAnimated } -> MtProtoDocumentMediaKind.GIF
            video?.roundMessage == true -> MtProtoDocumentMediaKind.VIDEO_NOTE
            video != null -> MtProtoDocumentMediaKind.VIDEO
            else -> MtProtoDocumentMediaKind.DOCUMENT
        }
        dao.upsert(
            MtProtoDocumentLocationEntity(
                accountSlot = scope.accountSlot,
                environment = scope.environment.storageName,
                sessionDcId = scope.dcId,
                documentId = document.id,
                accessHash = document.accessHash,
                fileReference = document.fileReference.toByteArray(),
                documentDcId = document.dcId,
                mimeType = document.mimeType,
                size = document.size,
                fileName = document.attributes.filterIsInstance<DocumentAttributeFilename>().firstOrNull()?.fileName.orEmpty(),
                mediaKind = mediaKind.name,
                width = video?.w ?: dimensions?.w,
                height = video?.h ?: dimensions?.h,
                duration = video?.duration?.toInt() ?: audio?.duration,
                supportsStreaming = video?.supportsStreaming == true,
                title = audio?.title,
                performer = audio?.performer,
                waveform = audio?.waveform?.toByteArray(),
                updatedAt = nowMillis(),
            )
        )
    }

    override suspend fun get(scope: MtProtoAuthKeyScope, documentId: Long): MtProtoDocumentLocation? =
        dao.get(scope.accountSlot, scope.environment.storageName, scope.dcId, documentId)?.let {
            MtProtoDocumentLocation(
                documentId = it.documentId,
                accessHash = it.accessHash,
                fileReference = it.fileReference,
                documentDcId = it.documentDcId,
                mimeType = it.mimeType,
                size = it.size,
                fileName = it.fileName,
                mediaKind = MtProtoDocumentMediaKind.valueOf(it.mediaKind),
                width = it.width,
                height = it.height,
                duration = it.duration,
                supportsStreaming = it.supportsStreaming,
                title = it.title,
                performer = it.performer,
                waveform = it.waveform,
            )
        }

    override suspend fun deleteAccount(accountSlot: String, environment: MtProtoEnvironment) =
        dao.deleteAccount(accountSlot, environment.storageName)
}
